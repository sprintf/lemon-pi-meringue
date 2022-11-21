package com.normtronix.meringue

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FireStoreConfiguration {

    @Bean
    fun getFirestore() : Firestore {
        val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
            .setProjectId("meringue")
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .build()
        return firestoreOptions.getService()
    }

}