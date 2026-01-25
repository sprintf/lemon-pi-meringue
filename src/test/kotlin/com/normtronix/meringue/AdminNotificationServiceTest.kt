package com.normtronix.meringue

import com.normtronix.meringue.event.NewDeviceRegisteredEvent
import com.normtronix.meringue.event.NewEmailAddressAddedEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*

internal class AdminNotificationServiceTest {

    private lateinit var service: AdminNotificationService

    @BeforeEach
    fun setup() {
        service = AdminNotificationService()
    }

    @Test
    fun testHandleDeviceEventWithNoToken() = runBlocking {
        service.slackToken = ""
        val event = NewDeviceRegisteredEvent("device123", "thil", "181")

        // Should not throw, just log warning
        service.handleEvent(event)
    }

    @Test
    fun testHandleEmailEventWithNoToken() = runBlocking {
        service.slackToken = ""
        val event = NewEmailAddressAddedEvent("test@example.com", "181")

        // Should not throw, just log warning
        service.handleEvent(event)
    }
}
