package com.normtronix.meringue

import com.normtronix.meringue.event.Event
import com.normtronix.meringue.event.EventHandler
import com.normtronix.meringue.event.Events
import com.normtronix.meringue.event.GpsPositionEvent
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

@Component
class CarIdleMonitor(
    @Value("\${car.idle.minutes:15}") private val idleMinutes: Long,
    @Value("\${car.idle.radius.meters:50}") private val idleRadiusMeters: Double,
) : EventHandler, InitializingBean {

    @Autowired lateinit var server: Server
    @Autowired lateinit var adminService: AdminService

    internal data class TimedPosition(val lat: Float, val lon: Float, val at: Instant)

    internal val positionHistory = ConcurrentHashMap<String, ArrayDeque<TimedPosition>>()

    override fun afterPropertiesSet() {
        Events.register(GpsPositionEvent::class.java, this)
    }

    override suspend fun handleEvent(e: Event) {
        if (e !is GpsPositionEvent) return
        val pos = e.position
        if (pos.lat == 0f && pos.long == 0f) return

        val key = "${e.trackCode}:${e.carNumber}"
        val deque = positionHistory.getOrPut(key) { ArrayDeque() }
        val cutoff = Instant.now().minusSeconds(idleMinutes * 60)
        synchronized(deque) {
            deque.addLast(TimedPosition(pos.lat, pos.long, Instant.now()))
            while (deque.isNotEmpty() && deque.first().at.isBefore(cutoff)) {
                deque.removeFirst()
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    fun checkForIdleCars() {
        val idleDuration = Duration.ofMinutes(idleMinutes)
        val now = Instant.now()

        val trackSnapshot = server.toCarIndex.toMap()

        for ((trackCode, carMap) in trackSnapshot) {
            if (adminService.raceMap.containsKey(trackCode)) continue

            for (carNumber in carMap.keys.toList()) {
                val key = "$trackCode:$carNumber"
                val snapshot = positionHistory[key]?.let { deque ->
                    synchronized(deque) { deque.toList() }
                } ?: continue

                if (snapshot.isEmpty()) continue
                if (Duration.between(snapshot.first().at, now) < idleDuration) continue

                if (spreadMeters(snapshot) < idleRadiusMeters) {
                    log.info("car $carNumber at $trackCode idle for ${idleMinutes}m " +
                            "(spread=${spreadMeters(snapshot).toInt()}m) — sending sleep")
                    runBlocking { server.sendSleepApp(trackCode, carNumber) }
                    positionHistory.remove(key)
                }
            }
        }
    }

    private fun spreadMeters(points: List<TimedPosition>): Double {
        if (points.size < 2) return 0.0
        val avgLatRad = Math.toRadians(points.map { it.lat.toDouble() }.average())
        val metersPerDegreeLat = 111_000.0
        val metersPerDegreeLon = 111_000.0 * cos(avgLatRad)
        val latSpread = (points.maxOf { it.lat } - points.minOf { it.lat }) * metersPerDegreeLat
        val lonSpread = (points.maxOf { it.lon } - points.minOf { it.lon }) * metersPerDegreeLon
        return sqrt(latSpread.pow(2) + lonSpread.pow(2))
    }

    companion object {
        private val log = LoggerFactory.getLogger(CarIdleMonitor::class.java)
    }
}
