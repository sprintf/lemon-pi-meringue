package com.normtronix.meringue

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.OAuth2Credentials
import com.google.cloud.NoCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class TestFireStoreConfiguration {

    @Bean
    fun getFirestore() : Firestore {
        val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
            .setCredentials(NoCredentials.getInstance())
            .build()
        return firestoreOptions.getService()
    }

}