package com.normtronix.meringue

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit4.SpringRunner
import java.util.*

// this test class is not able to be tested in the cloud as part of CI very easily
// due to the need to get the firestore emulator running there, and it doesn't run
// appear to work nicely with gradle so leaving this as a manual test for now.
//@RunWith(SpringRunner::class)
//@SpringBootTest(classes = [ConnectedCarStore::class, TestFireStoreConfiguration::class])
//@TestPropertySource(locations=["classpath:test.properties"])
internal class ConnectedCarStoreTest {

//    @Autowired
//    lateinit var store: ConnectedCarStore

//    @AfterEach
//    fun wipeDb() {
//        store.wipe()
//    }

    @Test
    fun noTest() {

    }

    //@Test
//    fun findTrack() {
//        assertNull(store.findTrack("181", "127.0.0.1", null))
//
//        store.storeConnectedCarDetails(RequestDetails("thil", "181", "mykey", "127.0.0.1"))
//        assertEquals("thil", store.findTrack("181", "127.0.0.1", null))
//        assertEquals("thil", store.findTrack("181", "127.0.0.2", "mykey"))
//        assertNull(store.findTrack("181", "127.0.0.2", "mykey2"))
//    }

    //@Test
//    fun testGettingConnectedCars() {
//        assertEquals(emptyList<TrackAndCar>(),  store.getConnectedCars("bad-ip-address"))
//
//        store.storeConnectedCarDetails(RequestDetails("test1", "183", "mykey", "good-ip-address"))
//        store.storeConnectedCarDetails(RequestDetails("test1", "182", "mykey", "good-ip-address"))
//
//        val cars = store.getConnectedCars("good-ip-address")
//        assertEquals(2, cars.size)
//    }

    //@Test
//    fun testExpiredDataIgnored() {
//        val storeProxy = spyk(store)
//        every { storeProxy.getTimeNow() } returns Timestamp.of(GregorianCalendar(2023, 5, 1).time)
//        storeProxy.storeConnectedCarDetails(RequestDetails("test1", "183", "mykey", "outdated-ip-address"))
//        assertEquals(emptyList<TrackAndCar>(), store.getConnectedCars("outdated-ip-address"))
//    }

    //@Test
//    fun testGettingStatusOnline() {
//        store.storeConnectedCarDetails(RequestDetails("tr1", "100", "mykey", "ip-address"))
//        val status = store.getStatus("tr1", "100")
//        assertEquals(true, status!!.isOnline)
//        assertEquals("ip-address", status.ipAddress)
//    }

    //@Test
//    fun testGettingStatusOffline() {
//        val storeProxy = spyk(store)
//        every { storeProxy.getTimeNow() } returns Timestamp.of(GregorianCalendar(2023, 5, 1).time)
//        storeProxy.storeConnectedCarDetails(RequestDetails("tr1", "101", "mykey", "outdated-ip-address"))
//        val status = store.getStatus("tr1", "101")
//        assertEquals(false, status!!.isOnline)
//        assertEquals("outdated-ip-address", status.ipAddress)
//    }

    //@Test
//    fun testNoDupeEntriesOnWrite() {
//        // should be unique between track + car combo
//        store.storeConnectedCarDetails(RequestDetails("tr2", "99", "mykey1", "ip1"))
//        store.storeConnectedCarDetails(RequestDetails("tr2", "99", "mykey2", "ip2"))
//        assertNull(store.findTrack("99", "ip1", null))
//        assertEquals("tr2", store.findTrack("99", "ip2", null))
//    }

}

private fun ConnectedCarStore.wipe() {
    val future = db.collection(ONLINE_CARS).get()
    future.get().documents.stream().forEach {
        it.reference.delete()
    }
}

@Component
internal class FireStoreTestProvider {

    @Bean
    fun getFirestore() : Firestore {
        val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
            .setProjectId("test")
            .setEmulatorHost("localhost:8080")
            .build()
        return firestoreOptions.service
    }
}

/* to test
  curl -sL https://firebase.tools | bash
  # firebase login ...may not be needed in cloud
  curl -sL https://firebase.tools | upgrade=true bash
  firebase emulators:start --only firestore
 */