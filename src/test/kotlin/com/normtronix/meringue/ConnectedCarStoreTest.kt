package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit4.SpringRunner

// this test class is not able to be tested in the cloud as part of CI very easily
// due to the need to get the firestore emulator running there, and it doesn't run
// appear to work nicely with gradle so leaving this as a manual test for now.
@RunWith(SpringRunner::class)
@SpringBootTest(classes = [ConnectedCarStore::class, FireStoreTestProvider::class])
@TestPropertySource(locations=["classpath:test.properties"])
internal class ConnectedCarStoreTest {

    @Autowired
    lateinit var store: ConnectedCarStore

    // uncomment to run @AfterEach
    fun wipeDb() {
        store.wipe()
    }

    // uncomment to run @Test
    fun findTrack() {
        assertNull(store.findTrack("181", "127.0.0.1", null))

        store.storeConnectedCarDetails(RequestDetails("thil", "181", "mykey", "127.0.0.1"))
        assertEquals("thil", store.findTrack("181", "127.0.0.1", null))
        assertEquals("thil", store.findTrack("181", "127.0.0.2", "mykey"))
        assertNull(store.findTrack("181", "127.0.0.2", "mykey2"))
    }

    @Test
    fun testNothing() {
        assertTrue(true)
    }
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