package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class TestFireStoreConfiguration {

    @Bean
    fun getFirestore() : Firestore {
        return mockk(relaxed = true)
    }

}