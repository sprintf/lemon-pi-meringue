package com.normtronix.meringue

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server as GrpcServer
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Integration test for PitcrewCommunicationsService that exercises the full
 * authentication flow through the gRPC interceptors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PitcrewIntegrationTest {

    private val jwtSecret = "test-secret-key"

    // Mocks
    private lateinit var deviceStore: DeviceDataStore
    private lateinit var connectedCarStore: ConnectedCarStore
    private lateinit var server: Server
    private lateinit var carDataService: CarDataService

    // Service under test
    private lateinit var pitcrewService: PitcrewCommunicationsService
    private lateinit var pitcrewContextInterceptor: PitcrewContextInterceptor

    // gRPC resources to clean up
    private var grpcServer: GrpcServer? = null
    private var grpcChannel: ManagedChannel? = null

    @BeforeEach
    fun setup() {
        // Create mocks
        deviceStore = mockk()
        connectedCarStore = mockk()
        server = mockk()
        carDataService = mockk()

        // Create and wire up the service
        pitcrewService = PitcrewCommunicationsService().apply {
            this.deviceStore = this@PitcrewIntegrationTest.deviceStore
            this.connectedCarStore = this@PitcrewIntegrationTest.connectedCarStore
            this.server = this@PitcrewIntegrationTest.server
            this.carDataService = this@PitcrewIntegrationTest.carDataService
            this.jwtSecret = this@PitcrewIntegrationTest.jwtSecret
        }

        // Create the context interceptor with the same JWT secret
        pitcrewContextInterceptor = PitcrewContextInterceptor().apply {
            this.jwtSecret = this@PitcrewIntegrationTest.jwtSecret
        }

        // Clear thread local context
        PitcrewContextInterceptor.pitcrewContext.remove()
    }

    @AfterEach
    fun teardown() {
        PitcrewContextInterceptor.pitcrewContext.remove()
        grpcChannel?.shutdownNow()
        grpcChannel?.awaitTermination(5, TimeUnit.SECONDS)
        grpcServer?.shutdownNow()
        grpcServer?.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun setupPitcrewStub(): PitcrewServiceGrpc.PitcrewServiceBlockingStub {
        val serverName = InProcessServerBuilder.generateName()

        grpcServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .intercept(PitcrewSecurityInterceptor())
            .intercept(pitcrewContextInterceptor)
            .addService(pitcrewService)
            .build()
            .start()

        grpcChannel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        return PitcrewServiceGrpc.newBlockingStub(grpcChannel)
            .withDeadlineAfter(5000, TimeUnit.MILLISECONDS)
    }

    private fun addAuthHeader(
        stub: PitcrewServiceGrpc.PitcrewServiceBlockingStub,
        token: String
    ): PitcrewServiceGrpc.PitcrewServiceBlockingStub {
        val metadata = Metadata()
        metadata.put(
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
            "Bearer $token"
        )
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    @Test
    fun testPing() {
        val stub = setupPitcrewStub()
        val reply = stub.ping(Empty.getDefaultInstance())
        assertEquals(Empty.getDefaultInstance(), reply)
    }

    @Test
    fun testAuthAndGetCarStatus() {
        val stub = setupPitcrewStub()

        // Setup mocks for successful auth
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "team123") } returns
                listOf("device1")

        // Setup mocks for getCarStatus
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "team123", listOf("user@test.com"))
        coEvery { connectedCarStore.getStatus("thil", "181") } returns
                ConnectedCarStore.CarConnectedStatus(true, "192.168.1.100", "device1")

        // Step 1: Authenticate and get token
        val authRequest = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("user@test.com")
            .setTeamCode("team123")
            .build()

        val authResponse = stub.auth(authRequest)
        assertTrue(authResponse.bearerToken.isNotEmpty())

        // Step 2: Use token to call getCarStatus
        val authedStub = addAuthHeader(stub, authResponse.bearerToken)
        val statusResponse = authedStub.getCarStatus(Empty.getDefaultInstance())

        // Verify the response
        assertEquals(1, statusResponse.statusListCount)
        val status = statusResponse.getStatusList(0)
        assertEquals("181", status.carNumber)
        assertEquals("thil", status.trackCode)
        assertTrue(status.online)
        assertEquals("192.168.1.100", status.ipAddress)
    }

    @Test
    fun testAuthAndSendDriverMessage() {
        val stub = setupPitcrewStub()

        // Setup mocks for successful auth
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("user@test.com", "team123") } returns
                listOf("device1")

        // Setup mocks for sendDriverMessage validation
        coEvery { deviceStore.getDeviceInfo("device1") } returns
                DeviceDataStore.DeviceInfo("thil", "181", "team123", listOf("user@test.com"))

        // Mock the server.sendDriverMessage call
        coEvery { server.sendDriverMessage("thil", "181", "Box this lap") } returns true

        // Authenticate
        val authRequest = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("user@test.com")
            .setTeamCode("team123")
            .build()
        val authResponse = stub.auth(authRequest)

        // Use token to send driver message
        val authedStub = addAuthHeader(stub, authResponse.bearerToken)
        val messageRequest = Pitcrew.PitDriverMessageRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setMessage("Box this lap")
            .build()

        val result = authedStub.sendDriverMessage(messageRequest)
        assertTrue(result.value)
    }

    @Test
    fun testGetCarStatusWithoutAuthFails() {
        val stub = setupPitcrewStub()

        val exception = assertThrows(io.grpc.StatusRuntimeException::class.java) {
            stub.getCarStatus(Empty.getDefaultInstance())
        }
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, exception.status.code)
    }

    @Test
    fun testGetCarStatusWithInvalidTokenFails() {
        val stub = setupPitcrewStub()
        val authedStub = addAuthHeader(stub, "invalid-token")

        val exception = assertThrows(io.grpc.StatusRuntimeException::class.java) {
            authedStub.getCarStatus(Empty.getDefaultInstance())
        }
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, exception.status.code)
    }

    @Test
    fun testAuthWithInvalidCredentialsFails() {
        val stub = setupPitcrewStub()

        // Setup mock to return empty list (no devices found)
        coEvery { deviceStore.findDevicesByEmailAndTeamCode("bad@test.com", "wrongcode") } returns
                emptyList()

        val authRequest = Pitcrew.PitAuthRequest.newBuilder()
            .setUsername("bad@test.com")
            .setTeamCode("wrongcode")
            .build()

        assertThrows(io.grpc.StatusRuntimeException::class.java) {
            stub.auth(authRequest)
        }
    }
}
