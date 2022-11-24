package com.normtronix.meringue

import com.normtronix.meringue.event.CarLeavingPitEvent
import com.normtronix.meringue.event.CarPittingEvent
import com.normtronix.meringue.event.CarTelemetryEvent
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import java.text.SimpleDateFormat
import java.util.*

internal class SlackIntegrationServiceTest {

    @Test
    fun testHandleTelemetryEvent() { runBlocking {
        val s = mockk<SlackIntegrationService>()
        setupMocks(s)
        val e = CarTelemetryEvent("thil", "8", 5, 212.43F, 219, 78)
        s.handleEvent(e)
        coVerify() { s.sendSlackMessage("info", "5:55 ->   Car 8  lap:5  time:3:32   temp:219F ", "key")}
    }
    }

    @Test
    fun testHandleTelemetryEvent2() { runBlocking {
        val s = mockk<SlackIntegrationService>()
        setupMocks(s)
        val e = CarTelemetryEvent("thil", "8", 5, 212.43F, 220, 78)
        s.handleEvent(e)
        coVerify() { s.sendSlackMessage("info", "5:55 ->   Car 8  lap:5  time:3:32   temp:220F <!channel>", "key")}
    }
    }

    @Test
    fun testHandlePittingEvent() { runBlocking {
        val s = mockk<SlackIntegrationService>()
        setupMocks(s)
        val e = CarPittingEvent("thil", "8")
        s.handleEvent(e)
        coVerify() { s.sendSlackMessage("pit", "5:55 -> <!here> Car 8 Leaving Pits", "key")}
    }
    }

    @Test
    fun testHandlePitExitEvent() { runBlocking {
        val s = mockk<SlackIntegrationService>()
        setupMocks(s)
        val e = CarLeavingPitEvent("thil", "8")
        s.handleEvent(e)
        coVerify() { s.sendSlackMessage("pit", "5:55 -> <!here> Car 8 Lewaving Pits", "key")}
    }
    }

    @Test
    fun testGetTime() {
        val s = SlackIntegrationService()
        assertEquals(s.getTime(), SimpleDateFormat("h:mm:ss a").format(Date()).toString())
    }

    private fun setupMocks(s: SlackIntegrationService) {
        every { s.getTime() } answers { "5:55" }
        every { s.buildKey(any(), any()) } answers { callOriginal() }
        coEvery { s.handleEvent(any()) } answers { callOriginal() }
        every { s.slackInfoChannels } answers { mutableMapOf("thil:8" to "info") }
        every { s.slackPitChannels } answers { mutableMapOf("thil:8" to "pit") }
        every { s.slackKeys } answers { mutableMapOf("thil:8" to "key") }
        every { s.db } answers { mockk() }
        coEvery { s.sendSlackMessage(any(), any(), any()) } returns Unit
    }

    @Test
    fun testSendingMessage() {
    }
}