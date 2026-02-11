package com.normtronix.meringue

import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import com.normtronix.meringue.event.Events
import com.normtronix.meringue.racedata.CarPosition
import com.normtronix.meringue.racedata.RaceOrder
import com.normtronix.meringue.racedata.RaceView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.BadCredentialsException

@OptIn(ExperimentalCoroutinesApi::class)
internal class CarTelemetryToPitCrewTest {

    private lateinit var server: Server
    private lateinit var carDataService: CarDataService
    private lateinit var pitcrewService: PitcrewCommunicationsService

    @BeforeEach
    fun setup() {
        Events.registry.clear()

        server = Server()
        server.carStore = mockk()
        server.deviceStore = mockk()
        every { server.carStore.storeConnectedCarDetails(any()) } returns null
        every { server.deviceStore.storeDeviceDetails(any(), any()) } returns Unit

        carDataService = CarDataService()
        carDataService.adminService = AdminService()
        mockkObject(carDataService.adminService)
        carDataService.afterPropertiesSet()

        pitcrewService = PitcrewCommunicationsService()
        pitcrewService.carDataService = carDataService
        pitcrewService.server = server
        pitcrewService.deviceStore = mockk()
        pitcrewService.connectedCarStore = mockk()
        pitcrewService.jwtSecret = "test-secret"
    }

    @AfterEach
    fun teardown() {
        PitcrewContextInterceptor.pitcrewContext.remove()
        Events.registry.clear()
    }

    private fun setPitcrewAuth() {
        PitcrewContextInterceptor.pitcrewContext.set(
            PitcrewContext(listOf("device1"), "radiokey833", "crew@test.com")
        )
    }

    private val carContext = requestor.asContextElement(
        value = RequestDetails("test1", "833", "radiokey833", "device1", "")
    )

    private fun streamAndCollect(
        results: MutableList<Pitcrew.ToPitCrewMessage>,
        timeoutMs: Long = 500,
        carActions: suspend () -> Unit
    ) {
        runBlocking {
            coroutineScope {
                val pitcrewJob = launch {
                    val request = Pitcrew.PitCarDataRequest.newBuilder()
                        .setTrackCode("test1")
                        .setCarNumber("833")
                        .build()
                    pitcrewService.streamCarDataV2(request).collect { response ->
                        results.add(response)
                    }
                }

                val carJob = launch(carContext) {
                    delay(50)
                    carActions()
                }

                launch {
                    delay(timeoutMs)
                    pitcrewJob.cancel()
                    carJob.cancel()
                }
            }
        }
    }

    @Test
    fun testCarTelemetryStreamsToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createPingMessage(1))
            server.sendMessageFromCar(createPingMessage(2))
            server.sendMessageFromCar(createTelemetryMessage(3))
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasCarData())
        val carData = results[0].carData
        assertEquals("833", carData.carNumber)
        assertEquals(200, carData.coolantTemp)
        assertEquals(75, carData.fuelRemainingPercent)
        assertEquals(10, carData.position)
        assertEquals(1, carData.positionInClass)
    }

    @Test
    fun testCarTelemetryStreamsToPitCrewWithNoRace() {
        every { carDataService.adminService.getRaceView("test1") } returns null
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createPingMessage(1))
            server.sendMessageFromCar(createPingMessage(2))
            server.sendMessageFromCar(createTelemetryMessage(3))
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasCarData())
        val carData = results[0].carData
        assertEquals("833", carData.carNumber)
        assertEquals(200, carData.coolantTemp)
        assertEquals(75, carData.fuelRemainingPercent)
        assertEquals(5, carData.lapCount)
        assertEquals(93.5f, carData.lastLapTime)
        assertEquals(0, carData.position)
        assertEquals(0, carData.positionInClass)
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.UNKNOWN, carData.flagStatus)
    }

    @Test
    fun testMultipleTelemetryUpdatesStreamToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createTelemetryMessage(1, lapCount = 3, coolantTemp = 190))
            delay(50)
            server.sendMessageFromCar(createTelemetryMessage(2, lapCount = 4, coolantTemp = 195))
            delay(50)
            server.sendMessageFromCar(createTelemetryMessage(3, lapCount = 5, coolantTemp = 200))
        }

        assertEquals(3, results.size)
        assertEquals(190, results[0].carData.coolantTemp)
        assertEquals(195, results[1].carData.coolantTemp)
        assertEquals(200, results[2].carData.coolantTemp)
    }

    @Test
    fun testDriverMessageAppearsInStream() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()

        runBlocking {
            coroutineScope {
                val pitcrewJob = launch {
                    val request = Pitcrew.PitCarDataRequest.newBuilder()
                        .setTrackCode("test1")
                        .setCarNumber("833")
                        .build()
                    pitcrewService.streamCarDataV2(request).collect { response ->
                        results.add(response)
                    }
                }

                // Send a driver message from the car side via the event system
                launch(carContext) {
                    delay(50)
                    // First send telemetry so the car has a channel
                    server.sendMessageFromCar(createTelemetryMessage(1))
                    delay(50)
                    // Send a driver message from pit to car — this triggers DriverMessageEvent
                    server.sendMessageFromPits(createDriverMessage("833", "Box this lap"))
                }

                launch {
                    delay(500)
                    pitcrewJob.cancel()
                }
            }
        }

        // Should have 2 responses: one from telemetry, one from driver message
        assertEquals(2, results.size)
        assertTrue(results[0].hasCarData())
        assertTrue(results[1].hasCarData())
        assertEquals("Box this lap", results[1].carData.driverMessage)
    }

    @Test
    fun testStreamCarDataWithoutAuthFails() {
        // Don't set pitcrew context — simulates unauthenticated call
        assertThrows(BadCredentialsException::class.java) {
            pitcrewService.streamCarDataV2(
                Pitcrew.PitCarDataRequest.newBuilder()
                    .setTrackCode("test1")
                    .setCarNumber("833")
                    .build()
            )
        }
    }

    @Test
    fun testPingsDoNotStreamToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createPingMessage(1))
            server.sendMessageFromCar(createPingMessage(2))
            server.sendMessageFromCar(createPingMessage(3))
        }

        assertEquals(0, results.size)
    }

    @Test
    fun testTelemetryExtraSensorsStreamToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            val telemetry = LemonPi.ToPitMessage.newBuilder()
                .setTelemetry(LemonPi.CarTelemetry.newBuilder()
                    .setSender("833")
                    .setSeqNum(1)
                    .setLapCount(5)
                    .setLastLapTime(93.5f)
                    .setCoolantTemp(200)
                    .setFuelRemainingPercent(75)
                    .putExtraSensors("oil_pressure", 45)
                    .putExtraSensors("oil_temp", 230))
                .build()
            server.sendMessageFromCar(telemetry)
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasCarData())
        val carData = results[0].carData
        assertEquals(45, carData.extraSensorsMap["oil_pressure"])
        assertEquals(230, carData.extraSensorsMap["oil_temp"])
    }

    @Test
    fun testSectorCompleteStreamsToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createSectorMessage(1))
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasSectorDetails())
        val sector = results[0].sectorDetails
        assertEquals(32.5f, sector.sectorTime)
        assertEquals("S1", sector.sectorName)
        assertEquals(1, sector.sectorNum)
        assertEquals(95.0f, sector.predictedLapTime)
        assertEquals(0.5f, sector.predictedDeltaToTarget)
        assertEquals(-0.3f, sector.predictedDeltaToBest)
        assertEquals(5, sector.lapCount)
        assertEquals(31.8f, sector.bestSectorTime)
    }

    @Test
    fun testPittingStreamsToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createPittingMessage(1))
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasPitting())
    }

    @Test
    fun testLeavingPitStreamsToPitCrew() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createLeavingPitMessage(1))
        }

        assertEquals(1, results.size)
        assertTrue(results[0].hasEntering())
    }

    @Test
    fun testMixedMessagesStreamCorrectOneofTypes() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val results = mutableListOf<Pitcrew.ToPitCrewMessage>()
        streamAndCollect(results) {
            server.sendMessageFromCar(createTelemetryMessage(1))
            delay(50)
            server.sendMessageFromCar(createSectorMessage(2))
            delay(50)
            server.sendMessageFromCar(createPittingMessage(3))
        }

        assertEquals(3, results.size)
        assertTrue(results[0].hasCarData())
        assertTrue(results[1].hasSectorDetails())
        assertTrue(results[2].hasPitting())
    }

    @Test
    fun testStreamReusesFlowOnReconnect() {
        every { carDataService.adminService.getRaceView("test1") } returns buildRaceView()
        setPitcrewAuth()

        val request = Pitcrew.PitCarDataRequest.newBuilder()
            .setTrackCode("test1")
            .setCarNumber("833")
            .build()

        // First and second connection should reuse the same underlying stream
        pitcrewService.streamCarDataV2(request)
        assertEquals(1, pitcrewService.pitcrewStreams.size)

        pitcrewService.streamCarDataV2(request)
        assertEquals(1, pitcrewService.pitcrewStreams.size)
    }

    @Test
    fun testHasAudience() {
        setPitcrewAuth()

        assertFalse(pitcrewService.hasAudience("test1", "833"))

        val request = Pitcrew.PitCarDataRequest.newBuilder()
            .setTrackCode("test1")
            .setCarNumber("833")
            .build()
        pitcrewService.streamCarDataV2(request)

        assertTrue(pitcrewService.hasAudience("test1", "833"))
        assertFalse(pitcrewService.hasAudience("test1", "999"))
    }

    private fun createPingMessage(seqNum: Int): LemonPi.ToPitMessage {
        return LemonPi.ToPitMessage.newBuilder()
            .setPing(LemonPi.Ping.newBuilder()
                .setSender("833")
                .setSeqNum(seqNum))
            .build()
    }

    private fun createTelemetryMessage(
        seqNum: Int,
        lapCount: Int = 5,
        lastLapTime: Float = 93.5f,
        coolantTemp: Int = 200,
        fuelRemainingPercent: Int = 75
    ): LemonPi.ToPitMessage {
        return LemonPi.ToPitMessage.newBuilder()
            .setTelemetry(LemonPi.CarTelemetry.newBuilder()
                .setSender("833")
                .setSeqNum(seqNum)
                .setLapCount(lapCount)
                .setLastLapTime(lastLapTime)
                .setCoolantTemp(coolantTemp)
                .setFuelRemainingPercent(fuelRemainingPercent))
            .build()
    }

    private fun createSectorMessage(seqNum: Int): LemonPi.ToPitMessage {
        return LemonPi.ToPitMessage.newBuilder()
            .setSectorDetails(LemonPi.SectorComplete.newBuilder()
                .setSender("833")
                .setSeqNum(seqNum)
                .setSectorTime(32.5f)
                .setSectorName("S1")
                .setSectorNum(1)
                .setPredictedLapTime(95.0f)
                .setPredictedDeltaToTarget(0.5f)
                .setPredictedDeltaToBest(-0.3f)
                .setLapCount(5)
                .setBestSectorTime(31.8f))
            .build()
    }

    private fun createPittingMessage(seqNum: Int): LemonPi.ToPitMessage {
        return LemonPi.ToPitMessage.newBuilder()
            .setPitting(LemonPi.EnteringPits.newBuilder()
                .setSender("833")
                .setSeqNum(seqNum))
            .build()
    }

    private fun createLeavingPitMessage(seqNum: Int): LemonPi.ToPitMessage {
        return LemonPi.ToPitMessage.newBuilder()
            .setEntering(LemonPi.LeavingPits.newBuilder()
                .setSender("833")
                .setSeqNum(seqNum))
            .build()
    }

    private fun createDriverMessage(carNumber: String, text: String): LemonPi.ToCarMessage {
        return LemonPi.ToCarMessage.newBuilder()
            .setMessage(LemonPi.DriverMessage.newBuilder()
                .setCarNumber(carNumber)
                .setSender("meringue")
                .setText(text)
                .setSeqNum(1))
            .build()
    }

    private fun buildRaceView(): RaceView {
        return RaceView("green",
            mapOf("833" to CarPosition("833", "", RaceOrder.Car("833", "", "")).apply {
                position = 10
                positionInClass = 1
            })
        )
    }
}
