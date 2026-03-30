package com.normtronix.meringue

import com.normtronix.meringue.Common.BoolValue
import com.google.protobuf.ByteString
import com.normtronix.meringue.Common.Empty
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException

internal class PitcrewCommunicationsServiceTest {

    private lateinit var service: PitcrewCommunicationsService
    private lateinit var deviceStore: DeviceDataStore
    private lateinit var connectedCarStore: ConnectedCarStore
    private lateinit var server: Server
    private lateinit var mailService: MailService

    private val pitcrewContext get() = PitcrewContextInterceptor.pitcrewContext

    @BeforeEach
    fun setup() {
        deviceStore = mockk()
        connectedCarStore = mockk()
        server = mockk()
        mailService = mockk(relaxed = true)

        service = PitcrewCommunicationsService()
        service.deviceStore = deviceStore
        service.connectedCarStore = connectedCarStore
        service.server = server
        service.carDataService = mockk()
        service.mailService = mailService
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
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))

        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { connectedCarStore.getStatus("thil", "181") } returns
                ConnectedCarStore.CarConnectedStatus(true, "1.2.3.4", "device1")

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
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.sendDriverMessage("thil", "181", "hello driver") } returns true

        val request = Pitcrew.PitDriverMessageRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setMessage("hello driver")
            .build()

        val result = service.sendDriverMessage(request)
        assertEquals(BoolValue.newBuilder().setValue(true).build(), result)
    }

    @Test
    fun testSetDriverName() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.setDriverName("thil", "181", "Bob") } returns true

        val request = Pitcrew.PitSetDriverNameRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setDriverName("Bob")
            .build()

        val result = service.setDriverName(request)
        assertEquals(BoolValue.newBuilder().setValue(true).build(), result)
    }

    @Test
    fun testSetTargetLapTime() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.setTargetLapTime("thil", "181", 95) } returns true

        val request = Pitcrew.PitSetTargetLapTimeRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setTargetTimeSeconds(95)
            .build()

        val result = service.setTargetLapTime(request)
        assertEquals(BoolValue.newBuilder().setValue(true).build(), result)
    }

    @Test
    fun testResetFastLapTime() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.resetFastLapTime("thil", "181") } returns true

        val request = Pitcrew.PitResetFastLapTimeRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .build()

        val result = service.resetFastLapTime(request)
        assertEquals(BoolValue.newBuilder().setValue(true).build(), result)
    }

    @Test
    fun testAccessDeniedForUnownedCar() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))

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
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))

        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
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

    @Test
    fun testTalkToCarSuccess() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.sendAudioToCar("thil", "181", any()) } returns true

        val packets = flowOf(
            createVoicePacket("thil", "181", 0, false),
            createVoicePacket("thil", "181", 1, false),
            createVoicePacket("thil", "181", 2, true)
        )

        val result = service.talkToCar(packets)

        assertEquals(BoolValue.newBuilder().setValue(true).build(), result)
        coVerify(exactly = 3) { server.sendAudioToCar("thil", "181", any()) }
    }

    @Test
    fun testTalkToCarRejectsConcurrentSession() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.sendAudioToCar("thil", "181", any()) } returns true

        // Simulate an active session already in progress
        service.activeVoiceSessions["thil:181"] = System.currentTimeMillis()

        val packets = flowOf(createVoicePacket("thil", "181", 0, false))
        val result = service.talkToCar(packets)

        assertEquals(BoolValue.newBuilder().setValue(false).build(), result)
        coVerify(exactly = 0) { server.sendAudioToCar(any(), any(), any()) }
        // session should not have been added (the pre-existing one remains)
        assertTrue(service.activeVoiceSessions.containsKey("thil:181"))
    }

    @Test
    fun testTalkToCarReleasesSessionOnLastPacket() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.sendAudioToCar("thil", "181", any()) } returns true

        val packets = flowOf(
            createVoicePacket("thil", "181", 0, false),
            createVoicePacket("thil", "181", 1, true)
        )
        service.talkToCar(packets)

        assertFalse(service.activeVoiceSessions.containsKey("thil:181"))
    }

    @Test
    fun testTalkToCarReleasesSessionOnAbnormalTermination() = runBlocking {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))
        coEvery { server.sendAudioToCar("thil", "181", any()) } throws RuntimeException("connection dropped")

        val packets = flowOf(createVoicePacket("thil", "181", 0, false))
        assertThrows<RuntimeException> { runBlocking { service.talkToCar(packets) } }

        assertFalse(service.activeVoiceSessions.containsKey("thil:181"))
    }

    @Test
    fun testTalkToCarAccessDenied() {
        pitcrewContext.set(PitcrewContext(listOf("device1"), "123", "test@example.com"))
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "123", listOf("test@example.com"))

        val packets = flowOf(createVoicePacket("thil", "999", 0, false))

        assertThrows<AccessDeniedException> {
            runBlocking { service.talkToCar(packets) }
        }
    }

    // -------------------------------------------------------------------------
    // qrAuthAndReg
    // -------------------------------------------------------------------------

    private val nowTs get() = System.currentTimeMillis() / 1000
    private val validDeviceInfo = DeviceDataStore.DeviceInfo("thil", "181", "12345", emptyList())

    private fun qrRequest(
        deviceId: String = "device-uuid",
        teamCode: String = "12345",
        email: String = "user@test.com",
        ts: Long = nowTs
    ) = Pitcrew.QrAuthRequest.newBuilder()
        .setDeviceId(deviceId)
        .setTeamCode(teamCode)
        .setEmail(email)
        .setTimestamp(ts)
        .build()

    @Test
    fun `qrAuthAndReg happy path returns token and car info`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns true
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns listOf("device-uuid")

        val response = service.qrAuthAndReg(qrRequest())

        assertTrue(response.bearerToken.isNotEmpty())
        assertEquals("thil", response.trackCode)
        assertEquals("181", response.carNumber)
    }

    @Test
    fun `qrAuthAndReg sends welcome email for new email registration`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns true
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns listOf("device-uuid")

        service.qrAuthAndReg(qrRequest())

        coVerify(exactly = 1) { mailService.sendWelcomeEmail("user@test.com", "181") }
    }

    @Test
    fun `qrAuthAndReg does not send welcome email for already-registered email`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns false
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns listOf("device-uuid")

        service.qrAuthAndReg(qrRequest())

        coVerify(exactly = 0) { mailService.sendWelcomeEmail(any(), any()) }
    }

    @Test
    fun `qrAuthAndReg normalizes email to lowercase`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns true
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns listOf("device-uuid")

        service.qrAuthAndReg(qrRequest(email = "User@Test.COM"))

        coVerify { deviceStore.addEmailAddress("device-uuid", "user@test.com") }
    }

    @Test
    fun `qrAuthAndReg token contains correct device IDs`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns false
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns
            listOf("device-uuid", "device-uuid-2")

        val response = service.qrAuthAndReg(qrRequest())

        val claims = JwtHelper.decodeToken(response.bearerToken, "test-secret")
        assertEquals(listOf("device-uuid", "device-uuid-2"), claims.deviceIds)
        assertEquals("user@test.com", claims.email)
        assertEquals("12345", claims.teamCode)
    }

    @Test
    fun `qrAuthAndReg rejects expired timestamp`() {
        assertThrows<CredentialsExpiredException> {
            runBlocking { service.qrAuthAndReg(qrRequest(ts = nowTs - 3700L)) }
        }
        coVerify(exactly = 0) { deviceStore.getDeviceInfo(any()) }
    }

    @Test
    fun `qrAuthAndReg rejects timestamp in future beyond tolerance`() {
        assertThrows<CredentialsExpiredException> {
            runBlocking { service.qrAuthAndReg(qrRequest(ts = nowTs + 120L)) }
        }
    }

    @Test
    fun `qrAuthAndReg accepts timestamp within future tolerance`() = runBlocking {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo
        coEvery { deviceStore.addEmailAddress("device-uuid", "user@test.com") } returns false
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "12345") } returns listOf("device-uuid")

        // 30s in the future — within the 60s tolerance
        val response = service.qrAuthAndReg(qrRequest(ts = nowTs + 30L))

        assertTrue(response.bearerToken.isNotEmpty())
    }

    @Test
    fun `qrAuthAndReg rejects unknown device`() {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns null

        assertThrows<BadCredentialsException> {
            runBlocking { service.qrAuthAndReg(qrRequest()) }
        }
    }

    @Test
    fun `qrAuthAndReg rejects mismatched team code`() {
        coEvery { deviceStore.getDeviceInfo("device-uuid") } returns validDeviceInfo

        assertThrows<BadCredentialsException> {
            runBlocking { service.qrAuthAndReg(qrRequest(teamCode = "99999")) }
        }
    }

    private fun createVoicePacket(trackCode: String, carNumber: String, seqNum: Int, lastPacket: Boolean): Pitcrew.PitVoicePacket {
        return Pitcrew.PitVoicePacket.newBuilder()
            .setTrackCode(trackCode)
            .setCarNumber(carNumber)
            .setMessageStartTime(1000)
            .setAudioData(ByteString.copyFrom(byteArrayOf(0x1a, 0x45)))
            .setAudioSeqNum(seqNum)
            .setLastPacket(lastPacket)
            .build()
    }
}
