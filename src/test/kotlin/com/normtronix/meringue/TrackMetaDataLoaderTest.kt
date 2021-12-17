package com.normtronix.meringue

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class TrackMetaDataLoaderTest {

    @Test
    fun testLoading() {
        val tmd = TrackMetaDataLoader()
        assertTrue(tmd.isValidTrackCode("thil"))
    }


}

