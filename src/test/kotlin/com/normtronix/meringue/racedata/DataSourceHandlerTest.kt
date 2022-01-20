package com.normtronix.meringue.racedata

import com.normtronix.meringue.LemonPi
import com.normtronix.meringue.event.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.FileReader

internal class DataSourceHandlerTest {

    @Test
    fun testTimeParsing() {
        val ds = DataSourceHandler(RaceOrder(), "thil", setOf())
        assertEquals(135.0, ds.convertToSeconds("00:02:15"))
        assertEquals(3735.0, ds.convertToSeconds("01:02:15"))
        assertEquals(135.999, ds.convertToSeconds("00:02:15.999"))
        assertEquals(127.007, ds.convertToSeconds("00:02:07.007"))
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

        val ds = DataSourceHandler(leaderboard, "thil", setOf())
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
                    leaderboard.checkIntegrity()
                }
            }

        val car35 = leaderboard.lookup("35")
        println(car35?.gap(car35.getCarInFront(PositionEnum.OVERALL)))
        println(car35?.gap(car35.getCarInFront(PositionEnum.IN_CLASS)))

        val car964 = leaderboard.lookup("964")
        println(car964?.gap(car964.getCarInFront(PositionEnum.OVERALL)))

        // there's some dupes in here now that we dont use RMHL codes
        assertEquals(35, th.callbackCount["964"])
    }

    @Test
    fun emptyRaceStatusEvent() {
        runBlocking {
            val e = RaceStatusEvent("thill","yellow")
            val flagStatus = LemonPi.RaceFlagStatus.valueOf(e.flagStatus.trim().uppercase())
            assertEquals(LemonPi.RaceFlagStatus.YELLOW, flagStatus)
            val emptyStatus = RaceStatusEvent("thill","      ")
            assertThrows(IllegalArgumentException::class.java) {
                LemonPi.RaceFlagStatus.valueOf(emptyStatus.flagStatus.trim().uppercase())
            }
        }
    }

    @Test
    fun parseLine() {
        val rawLine1 = "\$RMHL \"23\" \"11\" \"1\" \"00:01:26.000\" \"Green \" \"00:16:19.395\""
        val rawLine2 = "\$RMHL \"22\" \"11\" \"2\" \"00:01:28.883\" \"Green \" \"00:16:25.395\""
        val rawLine3 = "\$RMHL \"62\" \"11\" \"3\" \"00:01:28.671\" \"Green \" \"00:16:34.018\""

        val leaderboard = RaceOrder()
        val ds = DataSourceHandler(leaderboard, "thil", setOf())

        leaderboard.addCar(CarPosition("23", "", "A"))
        leaderboard.addCar(CarPosition("22", "", "A"))
        leaderboard.addCar(CarPosition("62", "", "A"))

        runBlocking {
            ds.handleWebSocketMessage(rawLine1)
            ds.handleWebSocketMessage(rawLine2)
            ds.handleWebSocketMessage(rawLine3)
        }

        val leader = leaderboard.getLeadCar()
        val car62 = leaderboard.lookup("62")

        print(car62?.gap(car62.getCarInFront(PositionEnum.OVERALL)))

    }
}