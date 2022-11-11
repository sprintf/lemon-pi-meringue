package com.normtronix.meringue.racedata

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pusher.client.Pusher
import com.pusher.client.channel.PusherEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.FileReader

internal class DataSource2HandlerTest {

    private val test1 = """
    """

    @Test
    fun parseJsonFile() {
        val fr = BufferedReader(FileReader("src/test/resources/ds2-test.json"))
        val leaderboard = RaceOrder()
        runBlocking {
            val ds = DataSource2Handler(leaderboard, "ky", emptySet())
            ds.handlePayload(Gson().fromJson(fr, Payload::class.java))
        }
        val view = leaderboard.createRaceView()
        assertEquals("green", view.raceStatus)
        assertEquals(27, view.getField().size)
        assertEquals("020b", view.getField()[0].carNumber)
        assertEquals("98j", view.getField()[26].carNumber)

        assertEquals(1, view.lookupCar("16a")?.position)
    }

    @Test
    fun testParsingEndOfFile() {
        val mockPusher = mockk<Pusher>()
        every { mockPusher.disconnect() } returns Unit

        val testListener = DataSource2.CustomEventListener(
            DataSource2Handler(RaceOrder(), "test1", emptySet()),
            mockPusher)
        val pusherEventJson: JsonObject = Gson().fromJson("{\"event\":\"payload\",\"data\":\"{\\\"payload\\\":{\\\"path\\\":\\\"/events/live-timing-demo-resets-every-20-minutes/results/1073750179\\\",\\\"live_run_id\\\":null},\\\"event\\\":\\\"results\\\"}\",\"channel\":\"private-event-568-9999999999-20180727-run\"}", JsonObject::class.java)

        testListener.onEvent(PusherEvent(pusherEventJson))
        verify { mockPusher.disconnect() }

    }

}