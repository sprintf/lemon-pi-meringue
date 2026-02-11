package com.normtronix.meringue.event

import com.normtronix.meringue.LemonPi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class EventsTest {

    @BeforeEach
    fun setup() {
        Events.registry.clear()
    }

    @AfterEach
    fun teardown() {
        Events.registry.clear()
    }

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
    fun testDebounceWorks() {
        val h = TestHandler()
        Events.register(LapCompletedEvent::class.java, h)
        runBlocking {
            LapCompletedEvent("thil","red", 2, 3, 1,
                null, "-", 300.0, -5.5, 120.0, "green" ).emit()
            delay(100)
            LapCompletedEvent("thil","red", 2, 3, 1,
                null, "-", 300.0, +2.3, 120.0, "green" ).emit()
        }
        assertEquals(1, h.calledCount)
    }

    @Test
    fun testNoDebounceOnGpS() {
        val h = TestHandler()
        Events.register(GpsPositionEvent::class.java, h)
        runBlocking {
            val position = LemonPi.GpsPosition.newBuilder()
                .setLat(0.0F)
                .setLong(0.0F)
                .build()
            val gps = GpsPositionEvent("thil","red",  position)
            gps.emit()
            delay(100)
            gps.emit()
        }
        assertEquals(2, h.calledCount)
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

    @Test
    fun testHandlerExceptionDoesNotAffectOtherHandlers() {
        val goodHandler1 = TestHandler()
        val goodHandler2 = TestHandler()
        val throwingHandler = ThrowingHandler()
        Events.register(RaceStatusEvent::class.java, goodHandler1)
        Events.register(RaceStatusEvent::class.java, throwingHandler)
        Events.register(RaceStatusEvent::class.java, goodHandler2)
        runBlocking {
            RaceStatusEvent("thil", "red").emit()
        }
        assertEquals(1, goodHandler1.calledCount)
        assertEquals(1, goodHandler2.calledCount)
        assertTrue(throwingHandler.called)
    }

    @Test
    fun testSectorCompleteEvent() {
        val h = TestHandler()
        Events.register(SectorCompleteEvent::class.java, h)
        runBlocking {
            SectorCompleteEvent("thil", "181", 32.5f, "S1", 1,
                95.0f, 0.5f, -0.3f, 5, 31.8f).emit()
        }
        assertEquals(1, h.calledCount)
    }

    @Test
    fun testSectorCompleteEventFiltering() {
        val h = TestHandler()
        Events.register(SectorCompleteEvent::class.java, h,
            filter = { it is SectorCompleteEvent && it.carNumber == "181" })
        runBlocking {
            SectorCompleteEvent("thil", "999", 32.5f, "S1", 1,
                95.0f, 0.5f, -0.3f, 5, 31.8f).emit()
            assertEquals(0, h.calledCount)
            SectorCompleteEvent("thil", "181", 32.5f, "S1", 1,
                95.0f, 0.5f, -0.3f, 5, 31.8f).emit()
            assertEquals(1, h.calledCount)
        }
    }

    @Test
    fun testUnregisterRemovesHandler() {
        val h1 = TestHandler()
        val h2 = TestHandler()
        Events.register(RaceStatusEvent::class.java, h1)
        Events.register(RaceStatusEvent::class.java, h2)
        runBlocking {
            RaceStatusEvent("thil", "red").emit()
        }
        assertEquals(1, h1.calledCount)
        assertEquals(1, h2.calledCount)

        Events.unregister(h1)
        runBlocking {
            RaceStatusEvent("thil", "green").emit()
        }
        assertEquals(1, h1.calledCount)
        assertEquals(2, h2.calledCount)
    }

    @Test
    fun testUnregisterRemovesFromMultipleEventTypes() {
        val h = TestHandler()
        Events.register(RaceStatusEvent::class.java, h)
        Events.register(LapCompletedEvent::class.java, h)
        Events.register(SectorCompleteEvent::class.java, h)

        Events.unregister(h)

        runBlocking {
            RaceStatusEvent("thil", "red").emit()
            LapCompletedEvent("thil", "red", 2, 3, 1,
                null, "-", 300.0, -5.5, 120.0, "green").emit()
            SectorCompleteEvent("thil", "181", 32.5f, "S1", 1,
                95.0f, 0.5f, -0.3f, 5, 31.8f).emit()
        }
        assertEquals(0, h.calledCount)
    }

    internal class ThrowingHandler : EventHandler {
        var called = false

        override suspend fun handleEvent(e: Event) {
            called = true
            throw RuntimeException("handler failure")
        }
    }

    internal class TestHandler() : EventHandler {

        var calledCount = 0

        override suspend fun handleEvent(e: Event) {
            calledCount += 1
        }

    }
}