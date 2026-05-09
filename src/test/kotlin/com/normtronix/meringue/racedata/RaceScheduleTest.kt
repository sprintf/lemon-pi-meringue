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
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("12/04/2026")
        assertEquals("Arse-Freeze-Apalooza 2026 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun findSonomaOnSaturdayRaceDay() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("12/05/2026")
        assertEquals("Arse-Freeze-Apalooza 2026 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun findSonomaOnSundayRaceDay() {
        val s = RaceSchedule()
        val d = DateFormat.getDateInstance(DateFormat.SHORT).parse("12/06/2026")
        assertEquals("Arse-Freeze-Apalooza 2026 Sonoma Raceway", s.findRaceTitle(d, "snma"))
    }

    @Test
    fun scoreStringMatch() {
        val r1 = RaceDataIndexItem("555", "Road Atlanta", "The Kim Harmon Scrotium 500 2023")
        val r2 = RaceDataIndexItem("666", "MSR Houston", "Chilli Pepper Regional - SCCA Houston")
        val lemonsRace = "24 hours of lemons The Kim Harmon Scrotium 500 Road Atlanta"
        assertTrue(RaceSchedule.score(lemonsRace, r1) > RaceSchedule.score(lemonsRace, r2))
    }

    @Test
    fun scoreStringMatch2() {
        val r1 = RaceDataIndexItem("555", "Barber Motorsports Park", "Shine Country Classic")
        val r2 = RaceDataIndexItem("666", "MSR Houston", "Chilli Pepper Regional - SCCA Houston")
        val lemonsRace = "Shine Country Classic at Barber Motorsports Park"
        assertTrue(RaceSchedule.score(lemonsRace, r1) > RaceSchedule.score(lemonsRace, r2))
    }

    // DS1RaceLister swaps constructor args (raceId, eventName, trackName) into (raceId, trackName, eventName),
    // so trackName holds the race event name and eventName holds the venue. score() concatenates them —
    // tokenSetRatio is order-independent so matching still works.
    private fun liveRace(raceId: String, eventName: String, trackName: String, isLemons: Boolean = false) =
        RaceDataIndexItem(raceId, eventName, trackName, isLemons)

    @Test
    fun `sausage fest 2026 scores higher than other Road America races`() {
        val target = "The Sausage Fest 2026 Road America"
        val sausageFest = liveRace("164732", "The Sausage Fest 2026", "Road America", isLemons = true)
        val hyundaiN    = liveRace("165071", "2026 Hyundai N Festival Round 1", "Road America", isLemons = false)
        val club175     = liveRace("164792", "Club175", "Road America", isLemons = false)
        val eightHrsNJ  = liveRace("165127", "2026 8 Hours of Joisey", "NJMP Liberator", isLemons = false)

        val sfScore = RaceSchedule.score(target, sausageFest)
        val hnScore = RaceSchedule.score(target, hyundaiN)
        val c1Score = RaceSchedule.score(target, club175)
        val njScore = RaceSchedule.score(target, eightHrsNJ)

        println("Sausage Fest score: $sfScore")
        println("Hyundai N Festival score: $hnScore")
        println("Club175 score: $c1Score")
        println("8 Hours of Joisey score: $njScore")

        assertTrue(sfScore > 80, "Sausage Fest should score >80, got $sfScore")
        assertTrue(hnScore <= 80, "Hyundai N Festival should score ≤80, got $hnScore")
        assertTrue(c1Score <= 80, "Club175 should score ≤80, got $c1Score")
        assertTrue(njScore < hnScore, "Unrelated race should score lower than partial match")
    }

    @Test
    fun `8 hours of joisey does not match sausage fest`() {
        val target = "The Sausage Fest 2026 Road America"
        val eightHrsNJ = liveRace("165127", "2026 8 Hours of Joisey", "NJMP Liberator")
        assertTrue(RaceSchedule.score(target, eightHrsNJ) < 80)
    }

    @Test
    fun `shine country classic matches at barber with 80 threshold`() {
        val target = "Shine Country Classic 2026 Barber Motorsports Park"
        val shine = liveRace("99999", "Shine Country Classic", "Barber Motorsports Park")
        val unrelated = liveRace("99998", "Chilli Pepper Regional", "MSR Houston")
        assertTrue(RaceSchedule.score(target, shine) > 80)
        assertTrue(RaceSchedule.score(target, unrelated) < 80)
    }

    @Test
    fun `only lemons sausage fest passes isLemons and score filters combined`() {
        val target = "The Sausage Fest 2026 Road America"
        val sausageFest = liveRace("164732", "The Sausage Fest 2026", "Road America", isLemons = true)
        val club175     = liveRace("164792", "Club175", "Road America", isLemons = false)
        val hyundaiN    = liveRace("165071", "2026 Hyundai N Festival Round 1", "Road America", isLemons = false)

        val matched = listOf(sausageFest, club175, hyundaiN)
            .filter { it.isLemons }
            .filter { RaceSchedule.score(target, it) > 80 }

        assertEquals(1, matched.size)
        assertEquals("164732", matched[0].raceId)
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