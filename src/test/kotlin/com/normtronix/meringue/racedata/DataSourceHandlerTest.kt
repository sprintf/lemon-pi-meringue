package com.normtronix.meringue.racedata

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DataSourceHandlerTest {

    @Test
    fun testTimeParsing() {
        val ds = DataSourceHandler(RaceOrder(), listOf())
        assertEquals(135.0, ds.convertToSeconds("00:02:15"))
        assertEquals(3735.0, ds.convertToSeconds("01:02:15"))
        assertEquals(135.999, ds.convertToSeconds("00:02:15.999"))
        assertEquals(127.007, ds.convertToSeconds("00:02:07.007"))
    }
}