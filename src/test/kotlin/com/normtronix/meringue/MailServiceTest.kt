package com.normtronix.meringue

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

internal class MailServiceTest {

    @Test
    fun testSendWelcomeEmailSuccess() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertEquals("api.mailgun.net", request.url.host)
            assertEquals("/v3/test.lemons-racer.com/messages", request.url.encodedPath)
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Basic ") == true)

            respond(
                content = ByteReadChannel("""{"id":"<123@test.lemons-racer.com>","message":"Queued. Thank you."}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val service = MailService(HttpClient(mockEngine), "test-api-key", "test.lemons-racer.com")
        val result = service.sendWelcomeEmail("user@example.com", "181")
        assertTrue(result)
    }

    @Test
    fun testSendWelcomeEmailFailure() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"message":"Invalid API key"}"""),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val service = MailService(HttpClient(mockEngine), "bad-api-key", "test.lemons-racer.com")
        val result = service.sendWelcomeEmail("user@example.com", "181")
        assertFalse(result)
    }

    @Test
    fun testSendWelcomeEmailNoApiKey() = runBlocking {
        val mockEngine = MockEngine { error("Should not be called") }
        val service = MailService(HttpClient(mockEngine), "", "test.lemons-racer.com")
        val result = service.sendWelcomeEmail("user@example.com", "181")
        assertFalse(result)
    }

    @Test
    fun testSendWelcomeEmailNetworkError() = runBlocking {
        val mockEngine = MockEngine {
            throw Exception("Network error")
        }

        val service = MailService(HttpClient(mockEngine), "test-api-key", "test.lemons-racer.com")
        val result = service.sendWelcomeEmail("user@example.com", "181")
        assertFalse(result)
    }

    @Test
    fun testRequestContainsCorrectFormData() = runBlocking {
        var capturedBody: String? = null

        val mockEngine = MockEngine { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = ByteReadChannel("""{"id":"<123>","message":"Queued."}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val service = MailService(HttpClient(mockEngine), "test-api-key", "test.lemons-racer.com")
        service.sendWelcomeEmail("driver@example.com", "42")

        assertNotNull(capturedBody)
        assertTrue(capturedBody!!.contains("to=driver%40example.com"))
        assertTrue(capturedBody!!.contains("template=welcome-pit"))
        assertTrue(capturedBody!!.contains("from="))
        assertTrue(capturedBody!!.contains("X-Mailgun-Variables"))
    }
}
