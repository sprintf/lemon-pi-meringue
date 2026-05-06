package com.normtronix.meringue

import com.normtronix.meringue.CarIdleMonitor.TimedPosition
import com.normtronix.meringue.racedata.RaceOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

internal class CarIdleMonitorTest {

    private lateinit var monitor: CarIdleMonitor
    private lateinit var server: Server
    private lateinit var adminService: AdminService

    private val BASE_LAT = 39.537f
    private val BASE_LON = -122.304f
    private val NEARBY_LAT = 39.5374f   // ~45m north — within 50m threshold
    private val FAR_LAT = 39.539f       // ~200m north — outside threshold

    @BeforeEach
    fun setup() {
        monitor = CarIdleMonitor(idleMinutes = 15L, idleRadiusMeters = 50.0)
        server = mockk()
        adminService = mockk()
        monitor.server = server
        monitor.adminService = adminService

        every { adminService.raceMap } returns mutableMapOf()
        coEvery { server.sendSleepApp(any(), any()) } returns true
    }

    private fun wireCarToServer(trackCode: String, carNumber: String) {
        val channelAndKey = mockk<Server.ChannelAndKey<LemonPi.ToCarMessage>>()
        every { server.toCarIndex } returns mutableMapOf(trackCode to mutableMapOf(carNumber to channelAndKey))
    }

    private fun injectPositions(
        trackCode: String, carNumber: String,
        positions: List<Pair<Float, Float>>,
        oldestAgeMinutes: Long = 16L
    ) {
        val key = "$trackCode:$carNumber"
        val base = Instant.now().minusSeconds(oldestAgeMinutes * 60)
        val deque = monitor.positionHistory.getOrPut(key) { ArrayDeque() }
        positions.forEachIndexed { i, (lat, lon) ->
            val spacing = (oldestAgeMinutes * 60) / positions.size
            deque.addLast(TimedPosition(lat, lon, base.plusSeconds(i * spacing)))
        }
    }

    @Test
    fun `idle car within radius receives sleep message`() {
        wireCarToServer("thil", "99")
        injectPositions("thil", "99",
            List(20) { if (it % 2 == 0) BASE_LAT to BASE_LON else NEARBY_LAT to BASE_LON })

        monitor.checkForIdleCars()

        coVerify(exactly = 1) { server.sendSleepApp("thil", "99") }
    }

    @Test
    fun `car that has moved beyond radius does not receive sleep`() {
        wireCarToServer("thil", "99")
        injectPositions("thil", "99",
            List(20) { if (it % 2 == 0) BASE_LAT to BASE_LON else FAR_LAT to BASE_LON })

        monitor.checkForIdleCars()

        coVerify(exactly = 0) { server.sendSleepApp(any(), any()) }
    }

    @Test
    fun `active race suppresses sleep`() {
        wireCarToServer("thil", "99")
        every { adminService.raceMap } returns mutableMapOf("thil" to RaceOrder())
        injectPositions("thil", "99", List(20) { BASE_LAT to BASE_LON })

        monitor.checkForIdleCars()

        coVerify(exactly = 0) { server.sendSleepApp(any(), any()) }
    }

    @Test
    fun `car with less than a full idle window is not slept`() {
        wireCarToServer("thil", "99")
        injectPositions("thil", "99", List(10) { BASE_LAT to BASE_LON }, oldestAgeMinutes = 5L)

        monitor.checkForIdleCars()

        coVerify(exactly = 0) { server.sendSleepApp(any(), any()) }
    }

    @Test
    fun `history is cleared after sleep is sent`() {
        wireCarToServer("thil", "99")
        injectPositions("thil", "99", List(20) { BASE_LAT to BASE_LON })

        monitor.checkForIdleCars()

        assert(monitor.positionHistory["thil:99"] == null)
    }

    @Test
    fun `handleEvent accumulates gps positions`() {
        runBlocking {
            val pos = LemonPi.GpsPosition.newBuilder().setLat(BASE_LAT).setLong(BASE_LON).build()
            monitor.handleEvent(com.normtronix.meringue.event.GpsPositionEvent("thil", "99", pos))
            monitor.handleEvent(com.normtronix.meringue.event.GpsPositionEvent("thil", "99", pos))
        }
        assertEquals(2, monitor.positionHistory["thil:99"]!!.size)
    }

    @Test
    fun `handleEvent ignores zero positions`() {
        runBlocking {
            val pos = LemonPi.GpsPosition.newBuilder().setLat(0f).setLong(0f).build()
            monitor.handleEvent(com.normtronix.meringue.event.GpsPositionEvent("thil", "99", pos))
        }
        assert(monitor.positionHistory["thil:99"] == null)
    }
}
