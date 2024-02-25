package com.normtronix.meringue.racedata

import com.normtronix.meringue.RaceFlagStatusOuterClass
import com.normtronix.meringue.event.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.FileReader
import java.time.format.DateTimeParseException

internal class DataSourceHandlerTest {

    @Test
    fun testTimeParsing() {
        val ds = DataSourceHandler(RaceOrder(), "thil", 0, setOf())
        assertEquals(135.0, ds.convertToSeconds("00:02:15"))
        assertEquals(3735.0, ds.convertToSeconds("01:02:15"))
        assertEquals(135.999, ds.convertToSeconds("00:02:15.999"))
        assertEquals(127.007, ds.convertToSeconds("00:02:07.007"))
    }

    @Test
    fun testMoreThan24Hours() {
        val ds = DataSourceHandler(RaceOrder(), "thil", 0, setOf())
        assertEquals(97335.123, ds.convertToSeconds("27:02:15.123"))
    }

    @Test
    fun testTimeTruncated() {
        val ds = DataSourceHandler(RaceOrder(), "thil", 0, setOf())
        assertThrows(DateTimeParseException::class.java) {
            ds.convertToSeconds("27:0")
        }
    }

    class TestHandler: EventHandler {

        val callbackCount: MutableMap<String, Int> = mutableMapOf()

        override suspend fun handleEvent(e: Event) {
            if (e is LapCompletedEvent) {
                callbackCount[e.carNumber] = callbackCount[e.carNumber]?.plus(1) ?: 1
            }
        }
    }

    @Test
    fun parseFile() {
        val leaderboard = RaceOrder()

        val ds = DataSourceHandler(leaderboard, "thil", 0, setOf())
        val fr = BufferedReader(FileReader("src/test/resources/test-file.dat"))

        val th = TestHandler()

        Events.register(LapCompletedEvent::class.java, th)

        runBlocking {
            ds.handleEvent(CarConnectedEvent("thil", "964"))
            // we don't know about this car, so no callback
            assertEquals(null, th.callbackCount["964"])
        }

        fr.lines()
            .filter { it.isNotEmpty() }
            .forEach {
                runBlocking {
                    ds.handleWebSocketMessage(it)
                }
            }

        val view = leaderboard.createRaceView()
        val car35 = view.lookupCar("35")
        println(car35?.gap(car35.getCarAhead(PositionEnum.OVERALL)))
        println(car35?.gap(car35.getCarAhead(PositionEnum.IN_CLASS)))

        val car964 = view.lookupCar("964")
        println(car964?.gap(car964.getCarAhead(PositionEnum.OVERALL)))

        // there's some dupes in here now that we dont use RMHL codes
        assertEquals(33, th.callbackCount["964"])
    }

    @Test
    fun emptyRaceStatusEvent() {
        runBlocking {
            val e = RaceStatusEvent("thill","yellow")
            val flagStatus = RaceFlagStatusOuterClass.RaceFlagStatus.valueOf(e.flagStatus.trim().uppercase())
            assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.YELLOW, flagStatus)
            val emptyStatus = RaceStatusEvent("thill","      ")
            assertThrows(IllegalArgumentException::class.java) {
                RaceFlagStatusOuterClass.RaceFlagStatus.valueOf(emptyStatus.flagStatus.trim().uppercase())
            }
        }
    }

    @Test
    fun parseLine() {
        val rawLine1 = "\$RMHL \"23\" \"11\" \"1\" \"00:01:26.000\" \"Green \" \"00:16:19.395\""
        val rawLine2 = "\$RMHL \"22\" \"11\" \"2\" \"00:01:28.883\" \"Green \" \"00:16:25.395\""
        val rawLine3 = "\$RMHL \"62\" \"11\" \"3\" \"00:01:28.671\" \"Green \" \"00:16:34.018\""

        val leaderboard = RaceOrder()
        val ds = DataSourceHandler(leaderboard, "thil", 0, setOf())

        leaderboard.addCar("23", "", "A")
        leaderboard.addCar("22", "", "A")
        leaderboard.addCar("62", "", "A")

        runBlocking {
            ds.handleWebSocketMessage(rawLine1)
            ds.handleWebSocketMessage(rawLine2)
            ds.handleWebSocketMessage(rawLine3)
        }

        val view = leaderboard.createRaceView()
        val car62 = view.lookupCar("62")

        print(car62?.gap(car62.getCarAhead(PositionEnum.OVERALL)))

    }
}