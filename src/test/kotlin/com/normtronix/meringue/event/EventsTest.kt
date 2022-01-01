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
            RaceStatusEvent("thil","red").emit()
        }
        assertEquals(1, h.calledCount)
    }

    @Test
    fun testFiltering() {
        val h = TestHandler()
        Events.register(RaceStatusEvent::class.java, h,
            filter={it is RaceStatusEvent && it.trackCode == "t-1"} )
        runBlocking {
            RaceStatusEvent("t-2","red").emit()
            assertEquals(0, h.calledCount)
            RaceStatusEvent("t-1","black").emit()
            assertEquals(1, h.calledCount)
        }
    }

    internal class TestHandler() : EventHandler {

        var calledCount = 0

        override suspend fun handleEvent(e: Event) {
            calledCount += 1
        }

    }
}