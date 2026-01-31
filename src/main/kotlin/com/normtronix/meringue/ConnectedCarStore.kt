package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit



/**
 * This class keeps an up-to-date set of cars that are connected up to meringue.
 * There's a 5 minute TTL policy applied to these documents in firestore, so
 * Records are deleted after a car has disconnected.
 * The purpose of this class is to be able to link to a slack install and tie
 * together the slack instance with a car. For that purpose we keep the ip address
 * of the lemon-pi as well as its key in this table. When connecting via slack to
 * connect up to a car, if you come in on the same ip address then it's a free pass,
 * otherwise you'll need to match the keys in order to connect up to a car from slack
 */
@Component
class ConnectedCarStore() {

    constructor(testDb: Firestore) : this() {
        this.db = testDb
    }

    @Autowired
    lateinit var db : Firestore

    /**
     * Store connected car details and detect reinstall scenario.
     * @return the previous deviceId if this appears to be a reinstall (same IP, different deviceId), null otherwise
     */
    fun storeConnectedCarDetails(request: RequestDetails?): String? {
        if (request == null) return null

        var previousDeviceId: String? = null
        try {
            val docRef = db.collection(ONLINE_CARS).document("${request.trackCode}:${request.carNum}")
            val existing = docRef.get().get(500, TimeUnit.MILLISECONDS)

            if (existing.exists()) {
                val oldDeviceId = existing.getString(DEVICE_ID)
                val oldIp = existing.getString(IP)
                // Reinstall detected: same IP but different deviceId
                if (oldDeviceId != null && oldDeviceId != request.deviceId && oldIp == request.remoteIpAddr) {
                    previousDeviceId = oldDeviceId
                    log.info("reinstall detected for car ${request.carNum}: old device=$oldDeviceId, new device=${request.deviceId}")
                }
            }

            docRef.set(hashMapOf(
                KEY to request.teamCode,
                IP to request.remoteIpAddr,
                DEVICE_ID to request.deviceId,
                TTL to getTimeNow()
            ).toMap()).get(500, TimeUnit.MILLISECONDS)
            log.info("stored car ${request.carNum} details in connected db ip=${request.remoteIpAddr}")

        } catch (e: Exception) {
            log.error("failed to write to firestore", e)
        }
        return previousDeviceId
    }

    fun getTimeNow() = Timestamp.now()

    data class CarConnectedStatus(
        val isOnline: Boolean,
        val ipAddress: String?,
        val deviceId: String?
    )

    suspend fun getStatus(trackCode: String, carNumber: String): CarConnectedStatus? = withContext(Dispatchers.IO) {
        val doc = db.collection(ONLINE_CARS).document("$trackCode:$carNumber").get().get()
        when (doc.contains(TTL) and doc.contains(IP)) {
            true -> CarConnectedStatus(isRecentTTL(doc),
                doc.getString(IP),
                doc.getString(DEVICE_ID))
            else -> null
        }
    }

    suspend fun getConnectedCars(ipAddress: String) : List<TrackAndCar> = withContext(Dispatchers.IO) {
        // performance hint : we could work out the track from the ip address
        val result = mutableListOf<TrackAndCar>()
        db.collection(ONLINE_CARS).listDocuments().forEach {
            val doc = it.get().get()
            if (doc.contains(TTL) and doc.contains(IP)) {
                if (isRecentTTL(doc) && doc.getString(IP) == ipAddress) {
                    result.add(TrackAndCar.from(doc.id))
                }
            }
        }
        result
    }

    private fun isRecentTTL(doc: DocumentSnapshot) =
        System.currentTimeMillis() / 1000 - doc.getTimestamp(TTL)!!.seconds < TEN_MINUTES

    suspend fun findTrack(carNumber: String, ipAddr: String, key: String?): String? = withContext(Dispatchers.IO) {
        var result: String? = null
        db.collectionGroup(ONLINE_CARS).get().get()?.documents?.forEach {
            if (result == null && it.id.contains(":$carNumber")) {
                val trackAndCar = TrackAndCar.from(it.id)
                if (ipAddr == it.get(IP) || it.get(KEY) == key) {
                    result = trackAndCar.trackCode
                }
            }
        }
        result
    }


    companion object {
        val log: Logger = LoggerFactory.getLogger(ConnectedCarStore::class.java)

        internal const val ONLINE_CARS = "onlineCars"
        private const val IP = "ip"
        private const val KEY = "key"
        private const val DEVICE_ID = "dId"
        private const val TTL = "ttl"
        private const val TEN_MINUTES = 10 * 60
    }
}