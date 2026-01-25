package com.normtronix.meringue

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
class MailService() {

    @Value("\${mailgun.apikey}")
    lateinit var apiKey: String

    @Value("\${mailgun.domain:lemons-racer.com}")
    lateinit var mailgunDomain: String

    internal var httpClient: HttpClient = HttpClient(CIO)

    constructor(testHttpClient: HttpClient, apiKey: String, domain: String) : this() {
        this.httpClient = testHttpClient
        this.apiKey = apiKey
        this.mailgunDomain = domain
    }

    suspend fun sendWelcomeEmail(toEmail: String, carNumber: String): Boolean {
        if (apiKey.isBlank()) {
            log.warn("Mailgun API key not configured, skipping email to {}", toEmail)
            return false
        }

        return try {
            val response = httpClient.post("https://api.mailgun.net/v3/$mailgunDomain/messages") {
                header(HttpHeaders.Authorization, "Basic ${Base64.getEncoder().encodeToString("api:$apiKey".toByteArray())}")
                setBody(FormDataContent(Parameters.build {
                    append("from", "Lemons Racer <noreply@$mailgunDomain>")
                    append("to", toEmail)
                    append("template", "welcome-pit")
                    append("h:X-Mailgun-Variables", """{"email":"$toEmail","domain":"$mailgunDomain","carNumber":"$carNumber"}""")
                }))
            }

            when (response.status.value) {
                200 -> {
                    log.info("Successfully sent welcome email to {} for car {}", toEmail, carNumber)
                    true
                }
                else -> {
                    val body = response.bodyAsText()
                    log.error("Mailgun API error for {}: status={}, body={}", toEmail, response.status.value, body)
                    false
                }
            }
        } catch (e: Exception) {
            log.error("Failed to send welcome email to {}: {}", toEmail, e.message)
            false
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MailService::class.java)
    }
}
