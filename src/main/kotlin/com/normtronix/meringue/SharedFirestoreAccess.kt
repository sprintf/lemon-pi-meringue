package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Shared Firestore read-only access for pitcrew functionality.
 *
 * *** IMPORTANT: This file is shared between projects ***
 * - lemon-pi-meringue (this project)
 * - lemons-racer-dot-com
 *
 * When modifying this file, manually copy changes to the other project
 * and update the package name accordingly.
 *
 * This class provides direct Firestore access for checking car status,
 * allowing the web app to bypass gRPC calls when just polling for status.
 */
class SharedFirestoreAccess(private val db: Firestore) {

    data class DeviceInfo(
        val trackCode: String,
        val carNumber: String,
        val teamCode: String,
        val emailAddresses: List<String>
    )

    data class CarStatus(
        val isOnline: Boolean,
        val ipAddress: String?,
        val deviceId: String?
    )

    /**
     * Find device IDs associated with an email address and team code.
     * Used for authentication lookup.
     */
    fun findDevicesByEmailAndTeamCode(email: String, teamCode: String): List<String> {
        return withForkedGrpcContext {
            try {
                val query = db.collection(DEVICE_IDS)
                    .whereArrayContains(EMAIL_ADDRESSES, email)
                    .get()
                    .get(10, TimeUnit.SECONDS)
                query.documents
                    .filter { it.getString(TEAM_CODE) == teamCode }
                    .map { it.id }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Get device information by device ID.
     */
    fun getDeviceInfo(deviceId: String): DeviceInfo? {
        return withForkedGrpcContext {
            try {
                val doc = db.collection(DEVICE_IDS).document(deviceId).get().get(10, TimeUnit.SECONDS)
                if (doc.exists()) {
                    val track = doc.getString(TRACK_CODE) ?: return@withForkedGrpcContext null
                    val car = doc.getString(CAR_NUMBER) ?: return@withForkedGrpcContext null
                    val team = doc.getString(TEAM_CODE) ?: return@withForkedGrpcContext null
                    val emails = (doc.get(EMAIL_ADDRESSES) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    DeviceInfo(track, car, team, emails)
                } else {
                    null
                }
            } catch (e: Exception) {
                log.error("failed to get device info for $deviceId", e)
                null
            }
        }
    }

    /**
     * Get the online status of a car.
     * Cars are considered online if their TTL timestamp is within the last 10 minutes.
     */
    fun getCarStatus(trackCode: String, carNumber: String): CarStatus? {
        val docKey = "$trackCode:$carNumber"
        return withForkedGrpcContext {
            try {
                val doc = db.collection(ONLINE_CARS).document(docKey).get().get(10, TimeUnit.SECONDS)
                if (doc.contains(TTL) && doc.contains(IP)) {
                    val ttl = doc.getTimestamp(TTL)
                    val ageSeconds = if (ttl != null) System.currentTimeMillis() / 1000 - ttl.seconds else null
                    val recent = ageSeconds != null && ageSeconds < TEN_MINUTES
                    log.debug("onlineCars doc '$docKey': ttl=$ttl ageSeconds=$ageSeconds isRecent=$recent")
                    CarStatus(
                        isOnline = recent,
                        ipAddress = doc.getString(IP),
                        deviceId = doc.getString(DEVICE_ID)
                    )
                } else {
                    log.debug("onlineCars doc '$docKey': exists=${doc.exists()} hasTTL=${doc.contains(TTL)} hasIP=${doc.contains(IP)} — returning null")
                    null
                }
            } catch (e: Exception) {
                log.error("failed to get car status for $docKey", e)
                null
            }
        }
    }

    /**
     * Convenience method to get car status for all devices associated with given device IDs.
     * Returns a list of car statuses with their track/car info.
     */
    fun getCarStatusForDevices(deviceIds: List<String>): List<CarStatusWithInfo> {
        return deviceIds.mapNotNull { deviceId ->
            val info = getDeviceInfo(deviceId) ?: return@mapNotNull null
            val status = getCarStatus(info.trackCode, info.carNumber)
            CarStatusWithInfo(
                carNumber = info.carNumber,
                trackCode = info.trackCode,
                isOnline = status?.isOnline ?: false,
                ipAddress = status?.ipAddress
            )
        }
    }

    data class CarStatusWithInfo(
        val carNumber: String,
        val trackCode: String,
        val isOnline: Boolean,
        val ipAddress: String?
    )

    companion object {
        val log: Logger = LoggerFactory.getLogger(SharedFirestoreAccess::class.java)

        // DeviceIds collection
        internal const val DEVICE_IDS = "DeviceIds"
        internal const val TRACK_CODE = "trackCode"
        internal const val CAR_NUMBER = "carNumber"
        internal const val TEAM_CODE = "teamCode"
        internal const val EMAIL_ADDRESSES = "emailAddresses"

        // OnlineCars collection
        internal const val ONLINE_CARS = "onlineCars"
        internal const val IP = "ip"
        internal const val DEVICE_ID = "dId"
        internal const val TTL = "ttl"

        internal const val TEN_MINUTES = 10 * 60
    }
}
