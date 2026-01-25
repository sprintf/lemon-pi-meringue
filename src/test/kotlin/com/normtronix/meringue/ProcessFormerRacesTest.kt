package com.normtronix.meringue

import com.normtronix.meringue.racedata.DataSourceHandler
import com.normtronix.meringue.racedata.RaceOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

class ProcessFormerRacesTest {

    @Test
    fun runTestFile() {
        runFile("logs/race-37872.log", setOf("8"))
    }

    @Test
    fun runBigFile() {
        runFile("logs/race-116969.log", setOf("2", "1"))
    }

    internal fun runFile(filename: String, cars: Set<String>) {
        runBlocking {
            val race = RaceOrder()
            val handler = DataSourceHandler(race, "test1", 0, cars)
            val content = File(filename).readLines()
            for (line in content) {
                handler.handleWebSocketMessage(line)
            }
        }
    }
}