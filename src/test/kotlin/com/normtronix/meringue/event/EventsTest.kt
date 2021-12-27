package com.normtronix.meringue.event

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class EventsTest {

    @Test
    fun testCallingBack() {
        val h = TestHandler()
        Events.register(RaceStatusEvent::class.java, h)
        runBlocking {
            RaceStatusEvent("red").emit()
        }
        assertEquals(1, h.calledCount)
    }

    internal class TestHandler() : EventHandler {

        var calledCount = 0

        override suspend fun handleEvent(e: Event) {
            calledCount += 1
        }

    }
}