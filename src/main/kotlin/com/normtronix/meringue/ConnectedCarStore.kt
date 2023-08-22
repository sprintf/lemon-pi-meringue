package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
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
class ConnectedCarStore {

    internal val ONLINE_CARS = "onlineCars"
    private val IP = "ip"
    private val KEY = "key"
    private val TTL = "ttl"
    private val TEN_MINUTES = 10 * 60

    @Autowired
    lateinit var db : Firestore

    fun storeConnectedCarDetails(request: RequestDetails?) {
        request?.apply {
            try {
                db.collection(ONLINE_CARS)
                    .document("${request.trackCode}:${request.carNum}")
                    .set(hashMapOf(
                        KEY to request.key,
                        IP to request.remoteIpAddr,
                        TTL to getTimeNow()
                    ).toMap())
                    .get(500, TimeUnit.MILLISECONDS)
                log.info("stored car ${request.carNum} details in connected db ip=${request.remoteIpAddr}")
            } catch (e: Exception) {
                log.error("failed to write to firestore", e)
            }
        }
    }

    fun getTimeNow() = Timestamp.now()

    data class CarConnectedStatus(
        val isOnline: Boolean,
        val ipAddress: String?
    )

    fun getStatus(trackCode: String, carNumber: String): CarConnectedStatus? {
        val doc = db.collection(ONLINE_CARS).document("$trackCode:$carNumber").get().get()
        return when (doc.contains(TTL) and doc.contains(IP)) {
            true -> CarConnectedStatus(isRecentTTL(doc), doc.getString(IP))
            else -> null
        }
    }

    fun getConnectedCars(ipAddress: String) : List<TrackAndCar> {
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
        return result
    }

    private fun isRecentTTL(doc: DocumentSnapshot) =
        System.currentTimeMillis() / 1000 - doc.getTimestamp(TTL)!!.seconds < TEN_MINUTES

    fun findTrack(carNumber: String, ipAddr: String, key: String?): String? {
        db.collectionGroup(ONLINE_CARS).get().get()?.documents?.forEach {
            if (it.id.contains(":$carNumber")) {
                val trackAndCar = TrackAndCar.from(it.id)
                if (ipAddr == it.get(IP)) {
                    return trackAndCar.trackCode
                }
                if (it.get(KEY) == key) {
                    return trackAndCar.trackCode
                }
            }
        }
        return null
    }


    companion object {
        val log: Logger = LoggerFactory.getLogger(ConnectedCarStore::class.java)
    }
}