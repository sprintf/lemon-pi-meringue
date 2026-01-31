package com.normtronix.meringue

import com.google.protobuf.Empty
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.*
import net.devh.boot.grpc.client.security.CallCredentialsHelper
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

    val grpcCleanup = GrpcCleanupRule()
    var asyncStub: CommsServiceGrpc.CommsServiceStub? = null

    fun setupBlockingStub() :  CommsServiceGrpc.CommsServiceBlockingStub {
        val serverName = InProcessServerBuilder.generateName()
        val server = Server()
        val trackMetaData: TrackMetaDataLoader = mock(TrackMetaDataLoader::class.java)
        `when`(trackMetaData.isValidTrackCode("thil")).thenReturn(true)

        grpcCleanup.register(
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(ContextInterceptor(trackMetaData))
                .addService(server)
                .build()).start()

        asyncStub = CommsServiceGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS)

        return CommsServiceGrpc.newBlockingStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                    directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS)
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

//    @Test
//    fun testASync() {
//        val stub = setupAsyncStub()
//        val ping = LemonPi.ToPitMessage.newBuilder().pingBuilder
//            .setSeqNum(1)
//            .setSender("12")
//            .build()
//        val ro = stub.
//        stub.sendMessageFromCar(LemonPi.ToPitMessage.newBuilder().mergePing(ping).build(), ro)
//
//        val carNumbers = LemonPi.CarNumbers.newBuilder()
//            .addCarNumber("12").build()
//        val receiveCarMessages = stub.receiveCarMessages(carNumbers)
//        stub.sendMessageFromCar(LemonPi.ToPitMessage.newBuilder().mergePing(ping).build())
//        receiveCarMessages.forEach {
//            println("got a car message !!!")
//        }
//    }

    // test cases
    //  car sends a whole bunch of messages and nothing listening ... doesn't matter
    //  car sends and eventually pits connect ... gets the last second of messages
    //  car sends message with pits connected ... message comes through
    //  car message only goes to intended pit

    //  pits send bunch of messages ... car not there ... nothing happens
    //  pits sends one message .. car connects within 10s and it handles it
    //  pits sends message to a car it shouldn't be allowed to : doesn't go

    // multiple cars all sending
    //   only correct pits get it

    // with radio, you can't talk from far away
    // with radio cars do not know their race id
    // cars do know the track they are at
    // the server can also load the tracks ... and can work out the track a car is at from its gps
    //
    //   so we can use car-id + track-id as a unique way to determine vehicle
    //   the key is known to the car + the laptop
    //
    //   need association between race and track
    //
    //   could assign a nonce over lora : 2FA !!
    //
    //   laptop has to select track : it can only be at one
    //     laptop selects track + car numbers ... and needs key
    //
    //    car sends it's track num + its car num : encrypted with its key as its header
    //    any request to server not including these things is rejected
    //
    //    server doesn't have the key
    //    client is going to have to decrypt auth headers and ensure that:
    //      its sent by someone with our key ... expect car + track to be signed


}