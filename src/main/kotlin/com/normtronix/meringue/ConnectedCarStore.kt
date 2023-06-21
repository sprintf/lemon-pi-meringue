package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit



/**
 * This class keeps an up to date set of cars that are connected up to meringue.
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
                        "ttl" to Timestamp.now()).toMap())
                    .get(500, TimeUnit.MILLISECONDS)
                log.info("stored car ${request.carNum} details in connected db ip=${request.remoteIpAddr}")
            } catch (e: Exception) {
                log.error("failed to write to firestore", e)
            }
        }
    }

    fun findTrack(carNumber: String, ipAddr: String, key: String?): String? {
        db.collectionGroup(ONLINE_CARS).get().get()?.documents?.forEach {
            if (it.id.contains(":$carNumber")) {
                val trackAndCar = it.id.split(":")
                if (ipAddr == it.get(IP)) {
                    return trackAndCar[0]
                }
                if (it.get(KEY) == key) {
                    return trackAndCar[0]
                }
            }
        }
        return null
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(ConnectedCarStore::class.java)
    }
}