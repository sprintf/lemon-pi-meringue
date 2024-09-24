package com.normtronix.meringue.racedata

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

class DebugRaceDataStream {

    // manual test
    // @Test
    fun readLiveRaceData() {
        runBlocking {
            val raceId = "24-hours-of-lemons-pacific-raceway"
            val race = RaceOrder()
            val handler = DataSource2Handler(race, "test1", setOf("236"))
            val ds2 = DataSource2(raceId)
            ds2.logRaceData = true
            val url = ds2.connect()
            println(url)
           ds2.stream(url, handler)
        }
    }
}