package com.normtronix.meringue

import com.google.protobuf.BoolValue
import com.google.protobuf.Empty
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException

internal class PitcrewCommunicationsServiceTest {

    private lateinit var service: PitcrewCommunicationsService
    private lateinit var deviceStore: DeviceDataStore
    private lateinit var connectedCarStore: ConnectedCarStore
    private lateinit var server: Server

    private val pitcrewContext get() = PitcrewContextInterceptor.pitcrewContext

    @BeforeEach
    fun setup() {
        deviceStore = mockk()
        connectedCarStore = mockk()
        server = mockk()

        service = PitcrewCommunicationsService()
        service.deviceStore = deviceStore
        service.connectedCarStore = connectedCarStore
        service.server = server
        service.carDataService = mockk()
        service.jwtSecret = "test-secret"

        pitcrewContext.remove()
    }

    @AfterEach
    fun teardown() {
        pitcrewContext.remove()
    }

    @Test
    fun testPing() = runBlocking {
        val result = service.ping(Empty.getDefaultInstance())
        assertEquals(Empty.getDefaultInstance(), result)
    }

    @Test
    fun testAuthSuccess() = runBlocking {
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "123") } returns
                listOf("device1", "device2")

        val request = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("User@Test.com")
            .setTeamCode("123")
            .build()

        val response = service.auth(request)
        assertTrue(response.bearerToken.isNotEmpty())
    }

    @Test
    fun testAuthFailsWithUnknownEmail() = runBlocking {
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("unknown@test.com", "123") } returns
                emptyList()

        val request = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("unknown@test.com")
            .setTeamCode("123")
            .build()

        assertThrows<BadCredentialsException> {
            runBlocking { service.auth(request) }
        }

    }

    @Test
    fun testAuthFailsWithWrongTeamCode() = runBlocking {
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "999") } returns
                emptyList()

        val request = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("user@test.com")
            .setTeamCode("999")
            .build()

        assertThrows<BadCredentialsException> {
            runBlocking { service.auth(request) }
        }
    }

    @Test
    fun testGetCarStatus() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))

        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { connectedCarStore.getStatus("thil", "181") } returns
                ConnectedCarStore.CarConnectedStatus(true, "1.2.3.4")

        val response = service.getCarStatus(Empty.getDefaultInstance())

        assertEquals(1, response.statusListCount)
        val status = response.getStatusList(0)
        assertEquals("181", status.carNumber)
        assertEquals("thil", status.trackCode)
        assertTrue(status.online)
        assertEquals("1.2.3.4", status.ipAddress)
    }

    @Test
    fun testSendDriverMessage() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { server.sendDriverMessage("thil", "181", "hello driver") } returns true

        val request = Pitcrew.PitDriverMessageRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setMessage("hello driver")
            .build()

        val result = service.sendDriverMessage(request)
        assertEquals(BoolValue.of(true), result)
    }

    @Test
    fun testSetDriverName() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { server.setDriverName("thil", "181", "Bob") } returns true

        val request = Pitcrew.PitSetDriverNameRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setDriverName("Bob")
            .build()

        val result = service.setDriverName(request)
        assertEquals(BoolValue.of(true), result)
    }

    @Test
    fun testSetTargetLapTime() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { server.setTargetLapTime("thil", "181", 95) } returns true

        val request = Pitcrew.PitSetTargetLapTimeRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setTargetTimeSeconds(95)
            .build()

        val result = service.setTargetLapTime(request)
        assertEquals(BoolValue.of(true), result)
    }

    @Test
    fun testResetFastLapTime() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { server.resetFastLapTime("thil", "181") } returns true

        val request = Pitcrew.PitResetFastLapTimeRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .build()

        val result = service.resetFastLapTime(request)
        assertEquals(BoolValue.of(true), result)
    }

    @Test
    fun testAccessDeniedForUnownedCar() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")

        val request = Pitcrew.PitDriverMessageRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("999")
            .setMessage("should fail")
            .build()

        assertThrows<AccessDeniedException> {
            runBlocking { service.sendDriverMessage(request) }
        }
    }

    @Test
    fun testGetCarStatusWithOfflineCar() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123"))

        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123")
        coEvery { connectedCarStore.getStatus("thil", "181") } returns null

        val response = service.getCarStatus(Empty.getDefaultInstance())

        assertEquals(1, response.statusListCount)
        val status = response.getStatusList(0)
        assertFalse(status.online)
        assertEquals("", status.ipAddress)
    }

    @Test
    fun testGetCarStatusMissingContext() {
        assertThrows<BadCredentialsException> {
            runBlocking { service.getCarStatus(Empty.getDefaultInstance()) }
        }
    }
}
