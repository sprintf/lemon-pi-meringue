package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.CarConnectedEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.text.DateFormat

internal class RaceScheduleTest {

    @Test
    fun checkNoMatch() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("12/01/2023")
        assertNull(s.findRaceTitle(d, "abc"))
    }

    @Test
    fun findSonomaOnPracticeDay() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("11/21/2025")
        assertEquals("24 hours of lemons Arse-Freeze-Apalooza 2025 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun findSonomaOnSatrudayRaceDay() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("11/22/2025")
        assertEquals("24 hours of lemons Arse-Freeze-Apalooza 2025 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun findSonomaOnSundayRaceDay() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("11/23/2025")
        assertEquals("24 hours of lemons Arse-Freeze-Apalooza 2025 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun scoreStringMatch() {
        val r1 = RaceDataIndexItem("555", "Road Atlanta", "The Kim Harmon Scrotium 500 2023")
        val r2 = RaceDataIndexItem("666", "MSR Houston", "Chilli Pepper Regional - SCCA Houston")
        val lemonsRace = "24 hours of lemons The Kim Harmon Scrotium 500 Road Atlanta"
        assertTrue(RaceSchedule.score(lemonsRace, r1) > RaceSchedule.score(lemonsRace, r2))
    }

    @Test
    fun testGettingConnectionEvent() {
        runBlocking {
            val rs = RaceSchedule()
            rs.raceLister1 = DS1RaceLister()
            rs.adminService = mockk()
            // rs.adminService.trackMetaData = TrackMetaDataLoader()
            // rs.adminService.logRaceData = "false"
            // rs.adminService.delayLapCompletedEvent = "0"
            // rs.adminService.lemonPiService = mockk()
            every { rs.adminService.lemonPiService.getConnectedCarNumbers("rdatl") } returns setOf("181")

            rs.afterPropertiesSet()
            // CarConnectedEvent("rdatl", "181").emit()
            rs.handleEvent(CarConnectedEvent("rdatl", "181"))
        }
    }

}