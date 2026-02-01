package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.racedata.DS1RaceLister
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit4.SpringRunner
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
@RunWith(SpringRunner::class)
@SpringBootTest(classes = [AdminService::class, Server::class, CarDataService::class])
@Import(TestFireStoreConfiguration::class)
@TestPropertySource(locations=["classpath:test.properties"])
internal class AdminIntegrationTest {

    @MockBean
    lateinit var trackLoader: TrackMetaDataLoader

    @MockBean
    lateinit var raceLister1: DS1RaceLister

    @MockBean
    lateinit var connectedCars: ConnectedCarStore

    @MockBean
    lateinit var deviceStore: DeviceDataStore

    @MockBean
    lateinit var authService: AuthService

    @MockBean
    lateinit var slackService: SlackIntegrationService

    @MockBean
    lateinit var emailService: EmailAddressService

    @MockBean
    lateinit var mailService: MailService

    @Autowired
    lateinit var adminService: AdminService

    @Autowired
    lateinit var lemonPiService: Server

    @Autowired
    lateinit var carDataService: CarDataService

    val grpcCleanup = GrpcCleanupRule()
    var asyncLemonPiStub: CommsServiceGrpc.CommsServiceStub? = null

    fun setupBlockingAdminStub() :  AdminServiceGrpc.AdminServiceBlockingStub {
        val serverName = InProcessServerBuilder.generateName()
        `when`(trackLoader.isValidTrackCode("thil")).thenReturn(true)
        `when`(adminService.authService.isTokenValid(anyString())).thenReturn(true)

        grpcCleanup.register(
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(adminService)
                .build()).start()

        return AdminServiceGrpc.newBlockingStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                    directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS)
    }

    fun setupBlockingLemonPiStub() : Pair<CommsServiceGrpc.CommsServiceBlockingStub, String> {
        val serverName = InProcessServerBuilder.generateName()
        `when`(trackLoader.isValidTrackCode("thil")).thenReturn(true)

        grpcCleanup.register(
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(ContextInterceptor(trackLoader))
                .addService(lemonPiService)
                .build()).start()

        asyncLemonPiStub = CommsServiceGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS)

        return Pair(CommsServiceGrpc.newBlockingStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS), serverName)
    }

    @Test
    fun testPing() {
        val stub = setupBlockingAdminStub()
        val reply = stub.ping(Empty.getDefaultInstance())
        assertEquals(Empty.getDefaultInstance(), reply)
    }

    @Test
    fun testDriverMessageNotHeldWhenNotConnected() {
        val admin = setupBlockingAdminStub()

        val msg = MeringueAdmin.DriverMessageRequest.newBuilder()
            .setTrackCode("thil")
            .setCarNumber("181")
            .setMessage("wanker")
            .build()

        admin.sendDriverMessage(msg)
        val messageEvent = carDataService.driverMessageMap.getIfPresent("thil:181")
        assertNull(messageEvent)

    }

}