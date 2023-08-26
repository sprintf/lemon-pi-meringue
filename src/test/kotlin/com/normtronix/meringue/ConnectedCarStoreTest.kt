package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.FirestoreOptions
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.FirestoreEmulatorContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.*

@Testcontainers
internal class ConnectedCarStoreTest {

    companion object {
        @Container
        private val emulator = FirestoreEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:441.0.0-emulators")
        ).withStartupTimeout(Duration.ofSeconds(120))

        private var store: ConnectedCarStore? = null

//        @BeforeAll
//        @JvmStatic
//        fun connectToStore(): Unit {
//            val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
//                .setProjectId("test")
//                .setEmulatorHost(emulator.emulatorEndpoint)
//                .build()
//            store = ConnectedCarStore(firestoreOptions.service)
//        }

        @AfterAll
        @JvmStatic
        fun shutdownFirestore(): Unit {
            emulator.stop()
        }
    }

//    @AfterEach
//    fun wipeDb() {
//        store?.wipe()
//    }

    @Test
    fun testEmulatorIsWorking() {
        assertTrue(emulator.isRunning)
    }

    @Test
    fun findTrack() {
        val store = getFirestore(emulator)
        assertNull(store.findTrack("181", "127.0.0.1", null))

        store.storeConnectedCarDetails(RequestDetails("thil", "181", "mykey", "127.0.0.1"))
        assertEquals("thil", store.findTrack("181", "127.0.0.1", null))
        assertEquals("thil", store.findTrack("181", "127.0.0.2", "mykey"))
        assertNull(store.findTrack("181", "127.0.0.2", "mykey2"))
    }

    private fun getFirestore(emulator: FirestoreEmulatorContainer): ConnectedCarStore {
        if (store == null) {
            val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId("test")
                .setEmulatorHost(emulator.emulatorEndpoint)
                .build()
            store = ConnectedCarStore(firestoreOptions.service)
        }
        return store!!
    }

//    @Test
//    fun testGettingConnectedCars() {
//        assertEquals(emptyList<TrackAndCar>(),  store?.getConnectedCars("bad-ip-address"))
//
//        store?.storeConnectedCarDetails(RequestDetails("test1", "183", "mykey", "good-ip-address"))
//        store?.storeConnectedCarDetails(RequestDetails("test1", "182", "mykey", "good-ip-address"))
//
//        val cars = store?.getConnectedCars("good-ip-address")
//        assertEquals(2, cars?.size)
//    }

//    @Test
//    fun testExpiredDataIgnored() {
//        val storeProxy = spyk(store!!)
//        every { storeProxy.getTimeNow() } returns Timestamp.of(GregorianCalendar(2023, 5, 1).time)
//        storeProxy.storeConnectedCarDetails(RequestDetails("test1", "183", "mykey", "outdated-ip-address"))
//        assertEquals(emptyList<TrackAndCar>(), store?.getConnectedCars("outdated-ip-address"))
//    }

//    @Test
//    fun testGettingStatusOnline() {
//        store?.storeConnectedCarDetails(RequestDetails("tr1", "100", "mykey", "ip-address"))
//        val status = store?.getStatus("tr1", "100")
//        assertEquals(true, status!!.isOnline)
//        assertEquals("ip-address", status.ipAddress)
//    }

//    @Test
//    fun testGettingStatusOffline() {
//        val storeProxy = spyk(store!!)
//        every { storeProxy.getTimeNow() } returns Timestamp.of(GregorianCalendar(2023, 5, 1).time)
//        storeProxy.storeConnectedCarDetails(RequestDetails("tr1", "101", "mykey", "outdated-ip-address"))
//        val status = store?.getStatus("tr1", "101")
//        assertEquals(false, status!!.isOnline)
//        assertEquals("outdated-ip-address", status.ipAddress)
//    }

//    @Test
//    fun testNoDupeEntriesOnWrite() {
//        // should be unique between track + car combo
//        store?.storeConnectedCarDetails(RequestDetails("tr2", "99", "mykey1", "ip1"))
//        store?.storeConnectedCarDetails(RequestDetails("tr2", "99", "mykey2", "ip2"))
//        assertNull(store?.findTrack("99", "ip1", null))
//        assertEquals("tr2", store?.findTrack("99", "ip2", null))
//    }

}

private fun ConnectedCarStore.wipe() {
    val future = db.collection(ONLINE_CARS).get()
    future.get().documents.stream().forEach {
        it.reference.delete()
    }
}