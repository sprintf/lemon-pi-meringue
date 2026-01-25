package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.normtronix.meringue.event.NewDeviceRegisteredEvent
import com.normtronix.meringue.event.NewEmailAddressAddedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import javax.annotation.PreDestroy

@Component
class DeviceDataStore {

    internal val DEVICE_IDS = "DeviceIds"
    private val TRACK_CODE = "trackCode"
    private val CAR_NUMBER = "carNumber"
    private val TEAM_CODE = "teamCode"
    private val CREATED_AT = "createdAt"
    private val UPDATED_AT = "updatedAt"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Autowired
    lateinit var db: Firestore

    @PreDestroy
    fun cleanup() {
        scope.cancel()
    }

    fun storeDeviceDetails(request: RequestDetails?) {
        request?.apply {
            if (deviceId.isBlank()) {
                return
            }
            scope.launch {
                try {
                    val docRef = db.collection(DEVICE_IDS).document(deviceId)
                    val doc = docRef.get().get(10, TimeUnit.SECONDS)
                    val now = Timestamp.now()
                    if (doc.exists()) {
                        val existingTrack = doc.getString(TRACK_CODE)
                        val existingCar = doc.getString(CAR_NUMBER)
                        val existingTeam = doc.getString(TEAM_CODE)
                        if (existingTrack == trackCode && existingCar == carNum && existingTeam == teamCode) {
                            return@launch
                        }
                        docRef.update(mapOf(
                            TRACK_CODE to trackCode,
                            CAR_NUMBER to carNum,
                            TEAM_CODE to teamCode,
                            UPDATED_AT to now
                        )).get(10, TimeUnit.SECONDS)
                    } else {
                        docRef.set(mapOf(
                            TRACK_CODE to trackCode,
                            CAR_NUMBER to carNum,
                            TEAM_CODE to teamCode,
                            CREATED_AT to now,
                            UPDATED_AT to now
                        )).get(10, TimeUnit.SECONDS)
                        NewDeviceRegisteredEvent(deviceId, trackCode, carNum).emitAsync()
                    }
                    log.info("stored device $deviceId -> $trackCode/$carNum")
                } catch (e: Exception) {
                    log.error("failed to write device details to firestore", e)
                }
            }
        }
    }

    suspend fun addEmailAddress(deviceId: String, email: String) {
        try {
            val docRef = db.collection(DEVICE_IDS).document(deviceId)
            val doc = docRef.get().get(10, TimeUnit.SECONDS)
            if (doc.exists()) {
                val existing = (doc.get(EMAIL_ADDRESSES) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                if (!existing.contains(email)) {
                    docRef.update(EMAIL_ADDRESSES, existing + email).get(10, TimeUnit.SECONDS)
                    log.info("added email {} to device {}", email, deviceId)
                    val carNumber = doc.getString(CAR_NUMBER) ?: ""
                    NewEmailAddressAddedEvent(email, carNumber).emitAsync()
                }
            }
        } catch (e: Exception) {
            log.error("failed to add email address to device $deviceId", e)
        }
    }

    suspend fun removeEmailAddress(deviceId: String, email: String) {
        try {
            val docRef = db.collection(DEVICE_IDS).document(deviceId)
            val doc = docRef.get().get(10, TimeUnit.SECONDS)
            if (doc.exists()) {
                val existing = (doc.get(EMAIL_ADDRESSES) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                if (existing.contains(email)) {
                    docRef.update(EMAIL_ADDRESSES, existing - email).get(10, TimeUnit.SECONDS)
                    log.info("removed email {} from device {}", email, deviceId)
                }
            }
        } catch (e: Exception) {
            log.error("failed to remove email address from device $deviceId", e)
        }
    }

    suspend fun getEmailAddresses(deviceId: String): List<String> {
        return try {
            val doc = db.collection(DEVICE_IDS).document(deviceId).get().get(10, TimeUnit.SECONDS)
            if (doc.exists()) {
                (doc.get(EMAIL_ADDRESSES) as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.error("failed to get email addresses for device $deviceId", e)
            emptyList()
        }
    }

    data class DeviceInfo(val trackCode: String, val carNumber: String, val teamCode: String)

    suspend fun findDevicesByEmailAndTeamCode(email: String, teamCode: String): List<String> {
        return try {
            val query = db.collection(DEVICE_IDS)
                .whereArrayContains(EMAIL_ADDRESSES, email)
                .get()
                .get(10, TimeUnit.SECONDS)
            query.documents
                .filter { it.getString(TEAM_CODE) == teamCode }
                .map { it.id }
        } catch (e: Exception) {
            log.error("failed to find devices by email and team code", e)
            emptyList()
        }
    }

    suspend fun getDeviceInfo(deviceId: String): DeviceInfo? {
        return try {
            val doc = db.collection(DEVICE_IDS).document(deviceId).get().get(10, TimeUnit.SECONDS)
            if (doc.exists()) {
                val track = doc.getString(TRACK_CODE) ?: return null
                val car = doc.getString(CAR_NUMBER) ?: return null
                val team = doc.getString(TEAM_CODE) ?: return null
                DeviceInfo(track, car, team)
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("failed to get device info for $deviceId", e)
            null
        }
    }

    companion object {
        private const val EMAIL_ADDRESSES = "emailAddresses"
        val log: Logger = LoggerFactory.getLogger(DeviceDataStore::class.java)
    }
}
