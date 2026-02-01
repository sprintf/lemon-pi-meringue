package com.normtronix.meringue

import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SharedFirestoreAccessTest {

    private lateinit var db: Firestore
    private lateinit var sharedAccess: SharedFirestoreAccess

    @BeforeEach
    fun setup() {
        db = mockk()
        sharedAccess = SharedFirestoreAccess(db)
    }

    @Test
    fun testGetDeviceInfo() {
        val docRef = mockk<DocumentReference>()
        val docSnapshot = mockk<DocumentSnapshot>()
        val collection = mockk<CollectionReference>()

        every { db.collection("DeviceIds") } returns collection
        every { collection.document("device123") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(docSnapshot)
        every { docSnapshot.exists() } returns true
        every { docSnapshot.getString("trackCode") } returns "thil"
        every { docSnapshot.getString("carNumber") } returns "181"
        every { docSnapshot.getString("teamCode") } returns "team1"
        every { docSnapshot.get("emailAddresses") } returns listOf("test@example.com")

        val info = sharedAccess.getDeviceInfo("device123")

        assertNotNull(info)
        assertEquals("thil", info?.trackCode)
        assertEquals("181", info?.carNumber)
        assertEquals("team1", info?.teamCode)
        assertEquals(listOf("test@example.com"), info?.emailAddresses)
    }

    @Test
    fun testGetDeviceInfoNotFound() {
        val docRef = mockk<DocumentReference>()
        val docSnapshot = mockk<DocumentSnapshot>()
        val collection = mockk<CollectionReference>()

        every { db.collection("DeviceIds") } returns collection
        every { collection.document("unknown") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(docSnapshot)
        every { docSnapshot.exists() } returns false

        val info = sharedAccess.getDeviceInfo("unknown")

        assertNull(info)
    }

    @Test
    fun testGetCarStatusOnline() {
        val docRef = mockk<DocumentReference>()
        val docSnapshot = mockk<DocumentSnapshot>()
        val collection = mockk<CollectionReference>()

        // Recent timestamp (within 10 minutes)
        val recentTimestamp = Timestamp.ofTimeSecondsAndNanos(
            System.currentTimeMillis() / 1000 - 60, 0
        )

        every { db.collection("onlineCars") } returns collection
        every { collection.document("thil:181") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(docSnapshot)
        every { docSnapshot.contains("ttl") } returns true
        every { docSnapshot.contains("ip") } returns true
        every { docSnapshot.getTimestamp("ttl") } returns recentTimestamp
        every { docSnapshot.getString("ip") } returns "192.168.1.100"
        every { docSnapshot.getString("dId") } returns "device123"

        val status = sharedAccess.getCarStatus("thil", "181")

        assertNotNull(status)
        assertTrue(status!!.isOnline)
        assertEquals("192.168.1.100", status.ipAddress)
        assertEquals("device123", status.deviceId)
    }

    @Test
    fun testGetCarStatusOffline() {
        val docRef = mockk<DocumentReference>()
        val docSnapshot = mockk<DocumentSnapshot>()
        val collection = mockk<CollectionReference>()

        // Old timestamp (more than 10 minutes ago)
        val oldTimestamp = Timestamp.ofTimeSecondsAndNanos(
            System.currentTimeMillis() / 1000 - 700, 0
        )

        every { db.collection("onlineCars") } returns collection
        every { collection.document("thil:181") } returns docRef
        every { docRef.get() } returns ApiFutures.immediateFuture(docSnapshot)
        every { docSnapshot.contains("ttl") } returns true
        every { docSnapshot.contains("ip") } returns true
        every { docSnapshot.getTimestamp("ttl") } returns oldTimestamp
        every { docSnapshot.getString("ip") } returns "192.168.1.100"
        every { docSnapshot.getString("dId") } returns "device123"

        val status = sharedAccess.getCarStatus("thil", "181")

        assertNotNull(status)
        assertFalse(status!!.isOnline)
    }

    @Test
    fun testFindDevicesByEmailAndTeamCode() {
        val collection = mockk<CollectionReference>()
        val query = mockk<Query>()
        val querySnapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<QueryDocumentSnapshot>()
        val doc2 = mockk<QueryDocumentSnapshot>()

        every { db.collection("DeviceIds") } returns collection
        every { collection.whereArrayContains("emailAddresses", "test@example.com") } returns query
        every { query.get() } returns ApiFutures.immediateFuture(querySnapshot)
        every { querySnapshot.documents } returns listOf(doc1, doc2)
        every { doc1.getString("teamCode") } returns "team1"
        every { doc1.id } returns "device1"
        every { doc2.getString("teamCode") } returns "team2"
        every { doc2.id } returns "device2"

        val devices = sharedAccess.findDevicesByEmailAndTeamCode("test@example.com", "team1")

        assertEquals(1, devices.size)
        assertEquals("device1", devices[0])
    }
}
