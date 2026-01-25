package com.normtronix.meringue

import com.google.api.core.ApiFutures
import com.google.cloud.firestore.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

internal class EmailAddressServiceTest {

    private lateinit var db: Firestore
    private lateinit var collection: CollectionReference

    @BeforeEach
    fun setup() {
        db = mockk()
        collection = mockk()
        every { db.collection("emailVerifications") } returns collection
    }

    @Test
    fun testDeliverableEmail() = runBlocking {
        mockCacheMiss("test@example.com")
        mockCacheWrite("test@example.com")

        val service = createService(kickboxResponse("deliverable"))
        val result = service.isDeliverable("Test@Example.com")
        assertTrue(result)

        verify { collection.document("test@example.com") }
    }

    @Test
    fun testUndeliverableEmail() = runBlocking {
        mockCacheMiss("bad@example.com")
        mockCacheWrite("bad@example.com")

        val service = createService(kickboxResponse("undeliverable"))
        val result = service.isDeliverable("bad@example.com")
        assertFalse(result)
    }

    @Test
    fun testCacheHitFreshDeliverable() = runBlocking {
        mockCacheHit("cached@example.com", deliverable = true, ageMs = 1000)

        val service = createService(MockEngine { error("Should not be called") })
        val result = service.isDeliverable("cached@example.com")
        assertTrue(result)
    }

    @Test
    fun testCacheHitFreshUndeliverable() = runBlocking {
        mockCacheHit("cached@example.com", deliverable = false, ageMs = 1000)

        val service = createService(MockEngine { error("Should not be called") })
        val result = service.isDeliverable("cached@example.com")
        assertFalse(result)
    }

    @Test
    fun testExpiredCacheCallsKickbox() = runBlocking {
        val sevenMonthsMs = 210L * 24 * 60 * 60 * 1000
        mockCacheHit("expired@example.com", deliverable = true, ageMs = sevenMonthsMs)
        mockCacheWrite("expired@example.com")

        val service = createService(kickboxResponse("undeliverable"))
        val result = service.isDeliverable("expired@example.com")
        assertFalse(result)
    }

    @Test
    fun testKickboxErrorFailsOpen() = runBlocking {
        mockCacheMiss("error@example.com")

        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"message":"Internal Server Error","success":false}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val service = createService(mockEngine)
        val result = service.isDeliverable("error@example.com")
        assertTrue(result)
    }

    @Test
    fun testRateLimitFailsOpen() = runBlocking {
        mockCacheMiss("ratelimited@example.com")

        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.TooManyRequests
            )
        }
        val service = createService(mockEngine)
        val result = service.isDeliverable("ratelimited@example.com")
        assertTrue(result)
    }

    @Test
    fun testNoApiKeyFailsOpen() = runBlocking {
        val mockEngine = MockEngine { error("Should not be called") }
        val service = EmailAddressService(db, HttpClient(mockEngine), "")
        val result = service.isDeliverable("nokey@example.com")
        assertTrue(result)
    }

    @Test
    fun testEmailNormalization() = runBlocking {
        mockCacheMiss("user@example.com")
        mockCacheWrite("user@example.com")

        val service = createService(kickboxResponse("deliverable"))
        service.isDeliverable("  USER@Example.COM  ")

        verify { collection.document("user@example.com") }
    }

    @Test
    fun testRiskyResultIsUndeliverable() = runBlocking {
        mockCacheMiss("risky@example.com")
        mockCacheWrite("risky@example.com")

        val service = createService(kickboxResponse("risky"))
        val result = service.isDeliverable("risky@example.com")
        assertFalse(result)
    }

    private fun mockCacheMiss(email: String) {
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        every { collection.document(email) } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        every { snapshot.exists() } returns false
    }

    private fun mockCacheHit(email: String, deliverable: Boolean, ageMs: Long) {
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        every { collection.document(email) } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        every { snapshot.exists() } returns true
        every { snapshot.getLong("verifiedAt") } returns (System.currentTimeMillis() - ageMs)
        every { snapshot.getBoolean("deliverable") } returns deliverable
    }

    private fun mockCacheWrite(email: String) {
        val docRef = mockk<DocumentReference>()

        every { collection.document(email) } returns docRef
        every { docRef.set(any<Map<String, Any>>()) } returns ApiFutures.immediateFuture(mockk<WriteResult>())
    }

    private fun kickboxResponse(result: String): MockEngine {
        return MockEngine {
            respond(
                content = ByteReadChannel("""
                    {
                      "result":"$result",
                      "reason":"accepted_email",
                      "role":false,
                      "free":true,
                      "disposable":false,
                      "accept_all":false,
                      "did_you_mean":null,
                      "sendex":1,
                      "email":"test@example.com",
                      "user":"test",
                      "domain":"example.com",
                      "success":true,
                      "message":null
                    }
                """.trimIndent()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
    }

    private fun createService(mockEngine: MockEngine): EmailAddressService {
        return EmailAddressService(db, HttpClient(mockEngine), "test-api-key")
    }
}
