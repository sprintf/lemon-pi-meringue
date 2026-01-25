package com.normtronix.meringue

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@ExperimentalCoroutinesApi
internal class HandleEditTeamEmailTest {

    private lateinit var server: Server
    private lateinit var emailService: EmailAddressService
    private lateinit var deviceStore: DeviceDataStore
    private lateinit var carChannel: MutableSharedFlow<LemonPi.ToCarMessage>

    @BeforeEach
    fun setup() {
        server = Server()
        emailService = mockk()
        deviceStore = mockk()
        server.emailService = emailService
        server.deviceStore = deviceStore
        server.carStore = mockk()

        // Set up a channel for the car to receive responses
        carChannel = MutableSharedFlow(1, 5, BufferOverflow.DROP_OLDEST)
        server.toCarIndex["track1"] = mutableMapOf(
            "42" to Server.ChannelAndKey(carChannel, "key1")
        )
    }

    @Test
    fun testAddDeliverableEmail() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("good@test.com", add = true)

        coEvery { emailService.isDeliverable("good@test.com") } returns true
        coEvery { deviceStore.addEmailAddress("device1", "good@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns listOf("good@test.com")

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify { deviceStore.addEmailAddress("device1", "good@test.com") }

        val response = withTimeout(1000) { carChannel.first() }
        assertTrue(response.hasEmailAddresses())
        assertEquals(listOf("good@test.com"), response.emailAddresses.emailAddressList)
    }

    @Test
    fun testAddUndeliverableEmailNotStored() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("bad@test.com", add = true)

        coEvery { emailService.isDeliverable("bad@test.com") } returns false
        coEvery { deviceStore.getEmailAddresses("device1") } returns emptyList()

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify(exactly = 0) { deviceStore.addEmailAddress(any(), any()) }

        val response = withTimeout(1000) { carChannel.first() }
        assertTrue(response.emailAddresses.emailAddressList.isEmpty())
    }

    @Test
    fun testRemoveEmail() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("remove@test.com", add = false)

        coEvery { deviceStore.removeEmailAddress("device1", "remove@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns listOf("remaining@test.com")

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify { deviceStore.removeEmailAddress("device1", "remove@test.com") }
        coVerify(exactly = 0) { emailService.isDeliverable(any()) }

        val response = withTimeout(1000) { carChannel.first() }
        assertEquals(listOf("remaining@test.com"), response.emailAddresses.emailAddressList)
    }

    @Test
    fun testRemoveLastEmailReturnsEmptyList() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("last@test.com", add = false)

        coEvery { deviceStore.removeEmailAddress("device1", "last@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns emptyList()

        server.handleEditTeamEmailAddress(request, editMsg)

        val response = withTimeout(1000) { carChannel.first() }
        assertTrue(response.emailAddresses.emailAddressList.isEmpty())
    }

    @Test
    fun testBlankDeviceIdRejected() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "", "1.2.3.4")
        val editMsg = buildEditMessage("test@test.com", add = true)

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify(exactly = 0) { emailService.isDeliverable(any()) }
        coVerify(exactly = 0) { deviceStore.addEmailAddress(any(), any()) }
    }

    @Test
    fun testEmailIsNormalizedToLowercase() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("  MiXeD@Test.COM  ", add = true)

        coEvery { emailService.isDeliverable("mixed@test.com") } returns true
        coEvery { deviceStore.addEmailAddress("device1", "mixed@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns listOf("mixed@test.com")

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify { emailService.isDeliverable("mixed@test.com") }
        coVerify { deviceStore.addEmailAddress("device1", "mixed@test.com") }
    }

    @Test
    fun testResponseSentEvenWhenNoChannel() = runBlocking {
        // Car at a different track with no channel set up
        val request = RequestDetails("other-track", "99", "key2", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("test@test.com", add = false)

        coEvery { deviceStore.removeEmailAddress("device1", "test@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns emptyList()

        // Should not throw even though there's no channel
        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify { deviceStore.removeEmailAddress("device1", "test@test.com") }
    }

    @Test
    fun testRemoveDoesNotCallEmailVerification() = runBlocking {
        val request = RequestDetails("track1", "42", "key1", "device1", "1.2.3.4")
        val editMsg = buildEditMessage("remove@test.com", add = false)

        coEvery { deviceStore.removeEmailAddress("device1", "remove@test.com") } just runs
        coEvery { deviceStore.getEmailAddresses("device1") } returns emptyList()

        server.handleEditTeamEmailAddress(request, editMsg)

        coVerify(exactly = 0) { emailService.isDeliverable(any()) }
    }

    private fun buildEditMessage(email: String, add: Boolean): LemonPi.EditTeamEmailAddress {
        return LemonPi.EditTeamEmailAddress.newBuilder()
            .setEmailAddress(email)
            .setAdd(add)
            .setSeqNum(1)
            .setTimestamp(1000)
            .setSender("test")
            .build()
    }
}
