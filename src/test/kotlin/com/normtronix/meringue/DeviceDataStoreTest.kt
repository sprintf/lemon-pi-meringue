package com.normtronix.meringue

import com.google.api.core.ApiFutures
import com.google.cloud.firestore.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

internal class DeviceDataStoreTest {

    private lateinit var db: Firestore
    private lateinit var collection: CollectionReference
    private lateinit var store: DeviceDataStore

    @BeforeEach
    fun setup() {
        db = mockk()
        collection = mockk()
        every { db.collection("DeviceIds") } returns collection
        store = DeviceDataStore()
        store.db = db
    }

    // --- addEmailAddress ---

    @Test
    fun testAddEmailToExistingDevice() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("existing@test.com"))
        every { collection.document("device1") } returns docRef
        every { docRef.update("emailAddresses", listOf("existing@test.com", "new@test.com")) } returns
                ApiFutures.immediateFuture(mockk<WriteResult>())

        store.addEmailAddress("device1", "new@test.com")

        verify { docRef.update("emailAddresses", listOf("existing@test.com", "new@test.com")) }
    }

    @Test
    fun testAddDuplicateEmailNotStored() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("already@test.com"))
        every { collection.document("device1") } returns docRef

        store.addEmailAddress("device1", "already@test.com")

        verify(exactly = 0) { docRef.update(any<String>(), any()) }
    }

    @Test
    fun testAddEmailToDeviceWithNoExistingEmails() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = null)
        every { collection.document("device1") } returns docRef
        every { docRef.update("emailAddresses", listOf("first@test.com")) } returns
                ApiFutures.immediateFuture(mockk<WriteResult>())

        store.addEmailAddress("device1", "first@test.com")

        verify { docRef.update("emailAddresses", listOf("first@test.com")) }
    }

    @Test
    fun testAddEmailToNonExistentDevice() = runBlocking {
        val docRef = mockDocRef(exists = false, emails = null)
        every { collection.document("missing") } returns docRef

        store.addEmailAddress("missing", "test@test.com")

        verify(exactly = 0) { docRef.update(any<String>(), any()) }
    }

    @Test
    fun testAddEmailWhenStoreHasNullInList() = runBlocking {
        // Simulates corrupted data where null values exist in the list
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.exists() } returns true
        every { snapshot.get("emailAddresses") } returns listOf("good@test.com", null, "other@test.com")

        val docRef = mockk<DocumentReference>()
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        every { collection.document("device1") } returns docRef
        every { docRef.update("emailAddresses", listOf("good@test.com", "other@test.com", "new@test.com")) } returns
                ApiFutures.immediateFuture(mockk<WriteResult>())

        store.addEmailAddress("device1", "new@test.com")

        // Null values should be filtered out, and new email added
        verify { docRef.update("emailAddresses", listOf("good@test.com", "other@test.com", "new@test.com")) }
    }

    // --- removeEmailAddress ---

    @Test
    fun testRemoveEmailFromDevice() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("keep@test.com", "remove@test.com"))
        every { collection.document("device1") } returns docRef
        every { docRef.update("emailAddresses", listOf("keep@test.com")) } returns
                ApiFutures.immediateFuture(mockk<WriteResult>())

        store.removeEmailAddress("device1", "remove@test.com")

        verify { docRef.update("emailAddresses", listOf("keep@test.com")) }
    }

    @Test
    fun testRemoveLastEmailReturnsEmptyList() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("only@test.com"))
        every { collection.document("device1") } returns docRef
        every { docRef.update("emailAddresses", emptyList<String>()) } returns
                ApiFutures.immediateFuture(mockk<WriteResult>())

        store.removeEmailAddress("device1", "only@test.com")

        verify { docRef.update("emailAddresses", emptyList<String>()) }
    }

    @Test
    fun testRemoveNonExistentEmailIsNoOp() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("existing@test.com"))
        every { collection.document("device1") } returns docRef

        store.removeEmailAddress("device1", "nothere@test.com")

        verify(exactly = 0) { docRef.update(any<String>(), any()) }
    }

    @Test
    fun testRemoveFromNonExistentDevice() = runBlocking {
        val docRef = mockDocRef(exists = false, emails = null)
        every { collection.document("missing") } returns docRef

        store.removeEmailAddress("missing", "test@test.com")

        verify(exactly = 0) { docRef.update(any<String>(), any()) }
    }

    @Test
    fun testRemoveFromDeviceWithNullEmailList() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = null)
        every { collection.document("device1") } returns docRef

        store.removeEmailAddress("device1", "test@test.com")

        verify(exactly = 0) { docRef.update(any<String>(), any()) }
    }

    // --- getEmailAddresses ---

    @Test
    fun testGetEmailAddresses() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = listOf("a@test.com", "b@test.com"))
        every { collection.document("device1") } returns docRef

        val result = store.getEmailAddresses("device1")

        assertEquals(listOf("a@test.com", "b@test.com"), result)
    }

    @Test
    fun testGetEmailAddressesReturnsEmptyWhenNone() = runBlocking {
        val docRef = mockDocRef(exists = true, emails = null)
        every { collection.document("device1") } returns docRef

        val result = store.getEmailAddresses("device1")

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun testGetEmailAddressesReturnsEmptyForMissingDevice() = runBlocking {
        val docRef = mockDocRef(exists = false, emails = null)
        every { collection.document("missing") } returns docRef

        val result = store.getEmailAddresses("missing")

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun testGetEmailAddressesFiltersNulls() = runBlocking {
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.exists() } returns true
        every { snapshot.get("emailAddresses") } returns listOf("valid@test.com", null, "also@test.com")

        val docRef = mockk<DocumentReference>()
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        every { collection.document("device1") } returns docRef

        val result = store.getEmailAddresses("device1")

        assertEquals(listOf("valid@test.com", "also@test.com"), result)
    }

    @Test
    fun testGetEmailAddressesReturnsEmptyOnException() = runBlocking {
        val docRef = mockk<DocumentReference>()
        every { docRef.get() } throws RuntimeException("Firestore unavailable")
        every { collection.document("device1") } returns docRef

        val result = store.getEmailAddresses("device1")

        assertEquals(emptyList<String>(), result)
    }

    // --- helpers ---

    private fun mockDocRef(exists: Boolean, emails: List<String?>?): DocumentReference {
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.exists() } returns exists
        every { snapshot.get("emailAddresses") } returns emails

        val docRef = mockk<DocumentReference>()
        every { docRef.get() } returns ApiFutures.immediateFuture(snapshot)
        return docRef
    }
}
