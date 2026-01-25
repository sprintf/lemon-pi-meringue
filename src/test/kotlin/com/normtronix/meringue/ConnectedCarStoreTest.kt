package com.normtronix.meringue

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.*

internal class ConnectedCarStoreTest {

    private lateinit var db: Firestore
    private lateinit var collection: CollectionReference
    private lateinit var store: ConnectedCarStore

    @BeforeEach
    fun setup() {
        db = mockk()
        collection = mockk()
        every { db.collection("onlineCars") } returns collection
        store = ConnectedCarStore(db)
    }

    @Test
    fun findTrackByIp() = runBlocking {
        val querySnapshot = mockQuerySnapshot(listOf(
            mockQueryDocSnapshot("thil:181", mapOf("ip" to "127.0.0.1", "key" to "mykey"))
        ))
        every { db.collectionGroup("onlineCars") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(querySnapshot)
        }

        assertEquals("thil", store.findTrack("181", "127.0.0.1", null))
    }

    @Test
    fun findTrackByKey() = runBlocking {
        val querySnapshot = mockQuerySnapshot(listOf(
            mockQueryDocSnapshot("thil:181", mapOf("ip" to "127.0.0.1", "key" to "mykey"))
        ))
        every { db.collectionGroup("onlineCars") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(querySnapshot)
        }

        assertEquals("thil", store.findTrack("181", "different-ip", "mykey"))
    }

    @Test
    fun findTrackNotFound() = runBlocking {
        val querySnapshot = mockQuerySnapshot(emptyList())
        every { db.collectionGroup("onlineCars") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(querySnapshot)
        }

        assertNull(store.findTrack("181", "127.0.0.1", null))
    }

    @Test
    fun findTrackWrongKey() = runBlocking {
        val querySnapshot = mockQuerySnapshot(listOf(
            mockQueryDocSnapshot("thil:181", mapOf("ip" to "127.0.0.1", "key" to "mykey"))
        ))
        every { db.collectionGroup("onlineCars") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(querySnapshot)
        }

        assertNull(store.findTrack("181", "different-ip", "wrongkey"))
    }

    @Test
    fun testGettingConnectedCars() = runBlocking {
        val recentTimestamp = Timestamp.now()
        val docRef1 = mockDocRef("test1:183", mapOf("ip" to "good-ip", "key" to "k1", "ttl" to recentTimestamp))
        val docRef2 = mockDocRef("test1:182", mapOf("ip" to "good-ip", "key" to "k2", "ttl" to recentTimestamp))

        every { collection.listDocuments() } returns listOf(docRef1, docRef2)

        val cars = store.getConnectedCars("good-ip")
        assertEquals(2, cars.size)
    }

    @Test
    fun testGettingConnectedCarsWrongIp() = runBlocking {
        val recentTimestamp = Timestamp.now()
        val docRef = mockDocRef("test1:183", mapOf("ip" to "other-ip", "key" to "k1", "ttl" to recentTimestamp))

        every { collection.listDocuments() } returns listOf(docRef)

        val cars = store.getConnectedCars("bad-ip")
        assertEquals(0, cars.size)
    }

    @Test
    fun testExpiredDataIgnored() = runBlocking {
        val oldTimestamp = Timestamp.of(GregorianCalendar(2023, 5, 1).time)
        val docRef = mockDocRef("test1:183", mapOf("ip" to "some-ip", "key" to "k1", "ttl" to oldTimestamp))

        every { collection.listDocuments() } returns listOf(docRef)

        val cars = store.getConnectedCars("some-ip")
        assertEquals(0, cars.size)
    }

    @Test
    fun testGettingStatusOnline() = runBlocking {
        val recentTimestamp = Timestamp.now()
        val snapshot = mockDocSnapshot(mapOf("ip" to "ip-address", "ttl" to recentTimestamp))

        every { collection.document("tr1:100") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(snapshot)
        }

        val status = store.getStatus("tr1", "100")
        assertEquals(true, status!!.isOnline)
        assertEquals("ip-address", status.ipAddress)
    }

    @Test
    fun testGettingStatusOffline() = runBlocking {
        val oldTimestamp = Timestamp.of(GregorianCalendar(2023, 5, 1).time)
        val snapshot = mockDocSnapshot(mapOf("ip" to "outdated-ip", "ttl" to oldTimestamp))

        every { collection.document("tr1:101") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(snapshot)
        }

        val status = store.getStatus("tr1", "101")
        assertEquals(false, status!!.isOnline)
        assertEquals("outdated-ip", status.ipAddress)
    }

    @Test
    fun testGettingStatusNotFound() = runBlocking {
        val snapshot = mockk<DocumentSnapshot> {
            every { contains("ttl") } returns false
            every { contains("ip") } returns false
        }

        every { collection.document("tr1:999") } returns mockk {
            every { get() } returns ApiFutures.immediateFuture(snapshot)
        }

        assertNull(store.getStatus("tr1", "999"))
    }

    @Test
    fun testStoreConnectedCarDetails() {
        val docRef = mockk<DocumentReference>()
        every { collection.document("tr1:100") } returns docRef
        every { docRef.set(any<Map<String, Any>>()) } returns ApiFutures.immediateFuture(mockk<WriteResult>())

        store.storeConnectedCarDetails(RequestDetails("tr1", "100", "mykey", "device1", "1.2.3.4"))

        verify {
            docRef.set(match<Map<String, Any>> {
                it["key"] == "mykey" && it["ip"] == "1.2.3.4" && it["dId"] == "device1"
            })
        }
    }

    private fun mockDocSnapshot(fields: Map<String, Any>): DocumentSnapshot {
        return mockk {
            every { contains("ttl") } returns fields.containsKey("ttl")
            every { contains("ip") } returns fields.containsKey("ip")
            every { getString("ip") } returns fields["ip"] as? String
            every { getTimestamp("ttl") } returns fields["ttl"] as? Timestamp
        }
    }

    private fun mockDocRef(id: String, fields: Map<String, Any>): DocumentReference {
        val snapshot = mockDocSnapshot(fields)
        every { snapshot.id } returns id
        return mockk {
            every { get() } returns ApiFutures.immediateFuture(snapshot)
        }
    }

    private fun mockQueryDocSnapshot(id: String, fields: Map<String, Any>): QueryDocumentSnapshot {
        val snapshot = mockk<QueryDocumentSnapshot>()
        every { snapshot.id } returns id
        every { snapshot.get(any<String>()) } answers { fields[firstArg<String>()] }
        return snapshot
    }

    private fun mockQuerySnapshot(docs: List<QueryDocumentSnapshot>): QuerySnapshot {
        return mockk {
            every { documents } returns docs
        }
    }
}
