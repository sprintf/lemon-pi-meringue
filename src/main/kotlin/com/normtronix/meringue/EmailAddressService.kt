package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class EmailAddressService() {

    @Autowired
    lateinit var db: Firestore

    @Value("\${kickbox.apikey}")
    lateinit var apiKey: String

    internal var httpClient: HttpClient = HttpClient(CIO)
    private val gson = Gson()

    constructor(testDb: Firestore, testHttpClient: HttpClient, apiKey: String) : this() {
        this.db = testDb
        this.httpClient = testHttpClient
        this.apiKey = apiKey
    }

    companion object {
        private val log = LoggerFactory.getLogger(EmailAddressService::class.java)
        private const val EMAIL_VERIFICATIONS = "emailVerifications"
        private const val DELIVERABLE = "deliverable"
        private const val VERIFIED_AT = "verifiedAt"
        private const val SIX_MONTHS_MS = 180L * 24 * 60 * 60 * 1000
    }

    suspend fun isDeliverable(email: String): Boolean {
        val normalizedEmail = email.lowercase().trim()

        val cached = getCachedResult(normalizedEmail)
        if (cached != null) {
            log.info("email verification cache hit for {}: {}", normalizedEmail, cached)
            return cached
        }

        if (apiKey.isBlank()) {
            log.warn("KickBox API key not configured, treating email as deliverable: {}", normalizedEmail)
            return true
        }

        return try {
            val result = verifyWithKickbox(normalizedEmail)
            cacheResult(normalizedEmail, result)
            result
        } catch (e: Exception) {
            log.warn("KickBox verification failed for {}, treating as deliverable: {}", normalizedEmail, e.message)
            true
        }
    }

    private suspend fun getCachedResult(email: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val doc = db.collection(EMAIL_VERIFICATIONS).document(email).get().get(500, TimeUnit.MILLISECONDS)
            if (doc.exists()) {
                val verifiedAt = doc.getLong(VERIFIED_AT) ?: return@withContext null
                if (System.currentTimeMillis() - verifiedAt < SIX_MONTHS_MS) {
                    return@withContext doc.getBoolean(DELIVERABLE)
                }
            }
            null
        } catch (e: Exception) {
            log.warn("Firestore cache lookup failed for {}: {}", email, e.message)
            null
        }
    }

    private suspend fun verifyWithKickbox(email: String): Boolean {
        val response = httpClient.get("https://api.kickbox.com/v2/verify") {
            parameter("email", email)
            parameter("apikey", apiKey)
        }
        return when (response.status.value) {
            200 -> {
                val body = response.bodyAsText()
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.get("result")?.asString
                log.info("KickBox verification for {}: result={}", email, result)
                result == "deliverable"
            }
            else -> {
                log.warn("KickBox API error for {}: status={}", email, response.status.value)
                throw RuntimeException("KickBox API returned ${response.status.value}")
            }
        }
    }

    private suspend fun cacheResult(email: String, deliverable: Boolean) = withContext(Dispatchers.IO) {
        try {
            db.collection(EMAIL_VERIFICATIONS)
                .document(email)
                .set(mapOf(
                    DELIVERABLE to deliverable,
                    VERIFIED_AT to System.currentTimeMillis()
                ))
                .get(500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.warn("Failed to cache email verification for {}: {}", email, e.message)
        }
    }
}
