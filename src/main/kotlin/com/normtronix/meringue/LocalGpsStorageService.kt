package com.normtronix.meringue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Year
import java.util.UUID

@Service
@Profile("dev")
class LocalGpsStorageService : GpsStorage {

    private val baseDir = Path.of("/tmp/gps-data")

    override suspend fun storeGpsData(deviceId: String, payload: ByteArray): String {
        val fileName = UUID.randomUUID().toString()
        val year = Year.now().value
        val objectPath = "$deviceId/$year/$fileName"
        val filePath = baseDir.resolve(objectPath)
        withContext(Dispatchers.IO) {
            Files.createDirectories(filePath.parent)
            Files.write(filePath, payload)
            log.info("stored GPS data to $filePath (${payload.size} bytes)")
        }
        return objectPath
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalGpsStorageService::class.java)
    }
}
