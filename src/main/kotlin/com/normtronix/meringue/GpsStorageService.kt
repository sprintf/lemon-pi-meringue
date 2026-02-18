package com.normtronix.meringue

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.Year
import java.util.UUID

interface GpsStorage {
    suspend fun storeGpsData(deviceId: String, payload: ByteArray): String
}

@Service
@Profile("!dev")
class GcsGpsStorageService : GpsStorage {

    @Value("\${gcs.bucket}")
    lateinit var bucketName: String

    private val storage by lazy { StorageOptions.getDefaultInstance().service }

    override suspend fun storeGpsData(deviceId: String, payload: ByteArray): String {
        val fileName = UUID.randomUUID().toString()
        val year = Year.now().value
        val objectPath = "$deviceId/$year/$fileName"
        withContext(Dispatchers.IO) {
            val blobId = BlobId.of(bucketName, objectPath)
            val blobInfo = BlobInfo.newBuilder(blobId).build()
            storage.create(blobInfo, payload)
            log.info("stored GPS data to gs://$bucketName/$objectPath (${payload.size} bytes)")
        }
        return objectPath
    }

    companion object {
        private val log = LoggerFactory.getLogger(GcsGpsStorageService::class.java)
    }
}
