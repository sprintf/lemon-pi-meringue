package com.normtronix.meringue

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
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
        return try {
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

    /**
     * Get device information by device ID.
     */
    fun getDeviceInfo(deviceId: String): DeviceInfo? {
        return try {
            val doc = db.collection(DEVICE_IDS).document(deviceId).get().get(10, TimeUnit.SECONDS)
            if (doc.exists()) {
                val track = doc.getString(TRACK_CODE) ?: return null
                val car = doc.getString(CAR_NUMBER) ?: return null
                val team = doc.getString(TEAM_CODE) ?: return null
                val emails = (doc.get(EMAIL_ADDRESSES) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                DeviceInfo(track, car, team, emails)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the online status of a car.
     * Cars are considered online if their TTL timestamp is within the last 10 minutes.
     */
    fun getCarStatus(trackCode: String, carNumber: String): CarStatus? {
        return try {
            val doc = db.collection(ONLINE_CARS).document("$trackCode:$carNumber").get().get(10, TimeUnit.SECONDS)
            if (doc.contains(TTL) && doc.contains(IP)) {
                CarStatus(
                    isOnline = isRecentTTL(doc),
                    ipAddress = doc.getString(IP),
                    deviceId = doc.getString(DEVICE_ID)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
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

    private fun isRecentTTL(doc: DocumentSnapshot): Boolean {
        val ttl = doc.getTimestamp(TTL) ?: return false
        return System.currentTimeMillis() / 1000 - ttl.seconds < TEN_MINUTES
    }

    companion object {
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
