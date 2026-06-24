package com.normtronix.meringue

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import com.normtronix.meringue.event.CarGpsDataEvent
import com.normtronix.meringue.event.CarPittingEvent
import com.normtronix.meringue.event.CarTelemetryEvent
import com.normtronix.meringue.event.Event
import com.normtronix.meringue.event.EventHandler
import com.normtronix.meringue.event.Events
import com.normtronix.meringue.event.GpsPositionEvent
import com.normtronix.meringue.event.NewDeviceRegisteredEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

/**
 * Per-car capture of all GPS data for later coaching analysis. Pure event handler
 * (no Server coupling). Everything for a car lands under one directory:
 *
 *   car-gps/YEAR/MONTH/TRACK-CODE/CAR-NUM/
 *       data-file-N.csv      <- 1Hz position stream (flushed every 10 min + on shutdown)
 *       sector-...pb         <- 25Hz detailed sector uploads (written on arrival)
 *       meta.json            <- { deviceId, trackCode, carNum, firstSeen }
 *
 * The deviceId in meta.json lets us trace a directory back to the team captain's
 * email later — otherwise TRACK/CAR alone is hard for a team to recognise as theirs.
 * 1Hz CSV rows match the 7-column format the coaching toolchain consumes
 *   (timeOfDay, unixTs, lap, lat, lng, speed_mph, heading;  lap 999 = unknown).
 */
@Service
class CarGpsLogger : EventHandler {

    @Autowired
    lateinit var store: CarGpsStore

    @Autowired
    lateinit var trackMetaData: TrackMetaDataLoader

    private val buffers  = ConcurrentHashMap<String, MutableList<String>>()  // "track/car" -> 1Hz rows
    private val lastLap  = ConcurrentHashMap<String, Int>()
    private val deviceId = ConcurrentHashMap<String, String>()               // "track/car" -> deviceId
    private val metaDone = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())  // dirs with meta.json

    @PostConstruct
    fun init() {
        Events.register(GpsPositionEvent::class.java, this)
        Events.register(CarTelemetryEvent::class.java, this)
        Events.register(NewDeviceRegisteredEvent::class.java, this)
        Events.register(CarGpsDataEvent::class.java, this)
        Events.register(CarPittingEvent::class.java, this)
    }

    override suspend fun handleEvent(e: Event) {
        when (e) {
            is GpsPositionEvent -> {
                val g = e.position
                record(e.trackCode, e.carNumber, g.lat, g.long, g.speedMph, g.heading, g.gpsTimestamp)
            }
            is CarTelemetryEvent -> lastLap[key(e.trackCode, e.carNumber)] = e.lapCount
            is NewDeviceRegisteredEvent -> deviceId[key(e.trackCode, e.carNumber)] = e.deviceId
            is CarGpsDataEvent -> storeSectorData(e)
            // flush this car's buffer when it pits — bounds data loss and aligns
            // each data-file with a stint (pit-to-pit)
            is CarPittingEvent -> withContext(Dispatchers.IO) { flushCar(key(e.trackCode, e.carNumber)) }
        }
    }

    /** Buffer one 1Hz GPS fix. Cheap + non-blocking. */
    private fun record(trackCode: String, carNum: String,
                       lat: Float, lng: Float, speedMph: Int, heading: Int, gpsTimestamp: Long) {
        // privacy + data quality: never persist a fix that isn't at the track
        // (e.g. the car being trailered home with the device powered on)
        if (!isAtTrack(trackCode, lat.toDouble(), lng.toDouble())) return
        val k = key(trackCode, carNum)
        val tsSec = if (gpsTimestamp > 100_000_000_000L) gpsTimestamp / 1000.0 else gpsTimestamp.toDouble()
        val tod = Instant.ofEpochMilli((tsSec * 1000).toLong()).atZone(ZoneOffset.UTC).format(TOD)
        val lap = lastLap[k] ?: 999
        val line = "%s,%.3f,%d,%.7f,%.7f,%d,%d".format(tod, tsSec, lap, lat, lng, speedMph, heading)
        buffers.getOrPut(k) { Collections.synchronizedList(ArrayList()) }.add(line)
    }

    /** Persist a 25Hz detailed-sector upload immediately, alongside the 1Hz CSVs. */
    private suspend fun storeSectorData(e: CarGpsDataEvent) {
        deviceId[key(e.trackCode, e.carNumber)] = e.deviceId
        val dir = carDir(e.trackCode, e.carNumber)
        val s = e.sectorData
        val name = "sector-lap${s.lapNumber}-s${s.sectorNumber}-${System.currentTimeMillis()}.pb"
        withContext(Dispatchers.IO) {
            try {
                store.write("$dir/$name", s.toByteArray())
                ensureMeta(dir, e.trackCode, e.carNumber)
                log.info("stored sector GPS data to $dir/$name (${s.payload.size()} bytes)")
            } catch (ex: Exception) {
                log.error("failed storing sector GPS data for ${e.carNumber}", ex)
            }
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    fun scheduledFlush() = flushAll()

    @PreDestroy
    fun onShutdown() {
        log.info("shutdown — flushing car GPS buffers")
        flushAll()
    }

    private fun flushAll() {
        for (k in buffers.keys.toList()) flushCar(k)
    }

    /** Drain and write one car's buffered 1Hz rows. Blocking — callers on a
     *  coroutine should wrap in withContext(Dispatchers.IO). */
    private fun flushCar(k: String) {
        val buf = buffers[k] ?: return
        val lines = synchronized(buf) {
            if (buf.isEmpty()) emptyList() else ArrayList(buf).also { buf.clear() }
        }
        if (lines.isEmpty()) return
        val (track, car) = k.split("/", limit = 2)
        val dir = carDir(track, car)
        val path = "$dir/data-file-${ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond()}.csv"
        try {
            store.write(path, (lines.joinToString("\n") + "\n").toByteArray())
            writeMeta(dir, track, car)   // refresh meta (deviceId may have arrived since)
            log.info("flushed ${lines.size} GPS rows to $path")
        } catch (e: Exception) {
            log.error("failed flushing GPS for $k — re-buffering ${lines.size} rows", e)
            synchronized(buf) { buf.addAll(0, lines) }
        }
    }

    private fun ensureMeta(dir: String, track: String, car: String) {
        if (dir !in metaDone) writeMeta(dir, track, car)
    }

    private fun writeMeta(dir: String, track: String, car: String) {
        val dev = deviceId[key(track, car)] ?: "unknown"
        val json = """{"deviceId":"$dev","trackCode":"$track","carNum":"$car","firstSeen":"${Instant.now()}"}"""
        try {
            store.write("$dir/meta.json", json.toByteArray())
            metaDone.add(dir)
        } catch (e: Exception) {
            log.warn("failed writing meta.json to $dir", e)
        }
    }

    private fun carDir(track: String, car: String): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        return "car-gps/${now.year}/%02d/%s/%s".format(now.monthValue, track, car)
    }

    private fun key(trackCode: String, carNum: String) = "$trackCode/$carNum"

    /** True unless the fix is more than MAX_DISTANCE_MILES from the track. Same
     *  3-mile rule the dot-com track map uses. Unknown track → don't drop. */
    private fun isAtTrack(trackCode: String, lat: Double, lng: Double): Boolean {
        val ref = trackMetaData.getTrackLatLng(trackCode) ?: return true
        return haversineMiles(ref.first, ref.second, lat, lng) <= MAX_DISTANCE_MILES
    }

    private fun haversineMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_MILES * asin(sqrt(a))
    }

    companion object {
        private val log = LoggerFactory.getLogger(CarGpsLogger::class.java)
        private val TOD = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private const val MAX_DISTANCE_MILES = 3.0
        private const val EARTH_RADIUS_MILES = 3958.8
    }
}

/** Pluggable sink for the car-gps files — GCS in prod, local disk in dev. */
interface CarGpsStore {
    fun write(objectPath: String, content: ByteArray)
}

@Service
@Profile("!dev")
class GcsCarGpsStore : CarGpsStore {

    @Value("\${gcs.bucket}")
    lateinit var bucketName: String

    private val storage by lazy { StorageOptions.getDefaultInstance().service }

    override fun write(objectPath: String, content: ByteArray) {
        val contentType = if (objectPath.endsWith(".csv")) "text/csv"
            else if (objectPath.endsWith(".json")) "application/json"
            else "application/octet-stream"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectPath))
            .setContentType(contentType).build()
        storage.create(blobInfo, content)
    }
}

@Service
@Profile("dev")
class LocalCarGpsStore : CarGpsStore {

    private val baseDir = Path.of("/tmp/car-gps")

    override fun write(objectPath: String, content: ByteArray) {
        val p = baseDir.resolve(objectPath)
        Files.createDirectories(p.parent)
        Files.write(p, content)
    }
}
