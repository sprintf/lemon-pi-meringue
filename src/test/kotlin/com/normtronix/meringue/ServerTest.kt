package com.normtronix.meringue

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.Server as GrpcServer
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import net.devh.boot.grpc.client.security.CallCredentialsHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit4.SpringRunner
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
@RunWith(SpringRunner::class)
@SpringBootTest(classes = [SlackIntegrationService::class])
@Import(TestFireStoreConfiguration::class)
@TestPropertySource(locations=["classpath:test.properties"])
internal class ServerTest {

    private var grpcServer: GrpcServer? = null
    private val channels = mutableListOf<ManagedChannel>()
    var asyncStub: CommsServiceGrpc.CommsServiceStub? = null

    @AfterEach
    fun teardown() {
        channels.forEach {
            it.shutdownNow()
            it.awaitTermination(5, TimeUnit.SECONDS)
        }
        channels.clear()
        grpcServer?.shutdownNow()
        grpcServer?.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun createChannel(serverName: String): ManagedChannel {
        val channel = InProcessChannelBuilder.forName(serverName)
            .build()
        channels.add(channel)
        return channel
    }

    fun setupBlockingStub() :  CommsServiceGrpc.CommsServiceBlockingStub {
        val serverName = InProcessServerBuilder.generateName()
        val server = Server()
        val trackMetaData: TrackMetaDataLoader = mock(TrackMetaDataLoader::class.java)
        `when`(trackMetaData.isValidTrackCode("thil")).thenReturn(true)

        grpcServer = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .intercept(ContextInterceptor(trackMetaData))
            .addService(server)
            .build()
        grpcServer!!.start()

        asyncStub = CommsServiceGrpc.newStub(createChannel(serverName))
            .withDeadlineAfter(1000, TimeUnit.MILLISECONDS)

        return CommsServiceGrpc.newBlockingStub(createChannel(serverName))
            .withDeadlineAfter(1000, TimeUnit.MILLISECONDS)
    }

    @Test
    fun testPingPong() {
        val stub = setupBlockingStub()
        val reply = stub.pingPong(Empty.getDefaultInstance())
        assertEquals(Empty.getDefaultInstance(), reply)
    }

    @Test
    fun testSendingMessage() {
        runBlocking {
            val stub = setupBlockingStub()
            val ping = createPingMessage("12", 1)
            val carNumber = LemonPi.CarNumber.newBuilder().setCarNumber("12").build()

            val carCreds = CallCredentialsHelper.basicAuth("thil/12", "foo")
            val pitCreds = CallCredentialsHelper.basicAuth("thil/12", "foo")
            val observer = SO<LemonPi.ToPitMessage>()

            coroutineScope {

                launch {
                    asyncStub?.withCallCredentials(pitCreds)?.receiveCarMessages(carNumber, observer)
                }

                launch {
                    delay(100)
                    stub.withCallCredentials(carCreds).sendMessageFromCar(ping)
                }

                launch {
                    delay(110)
                    stub.withCallCredentials(carCreds).sendMessageFromCar(ping)
                }

                delay(500)
            }
            assertEquals(2, observer.capture.size)
        }
    }

    class SO<T> :StreamObserver<T> {
        val capture : MutableList<T> = mutableListOf()

        override fun onNext(value: T?) {
            if (value != null) {
                capture.add(value)
            }
        }

        override fun onError(t: Throwable?) {
            // we expect a deadline exceeded error
            // println("got an error $t")
        }

        override fun onCompleted() {
            println("its complete")
        }

    }

    internal fun createPingMessage(carNumber: String, seqNum: Int) : LemonPi.ToPitMessage {
        val pingMessage = LemonPi.ToPitMessage.newBuilder().pingBuilder
            .setSender(carNumber)
            .setSeqNum(seqNum)
            .build()
        return LemonPi.ToPitMessage.newBuilder().mergePing(pingMessage).build()
    }

}
