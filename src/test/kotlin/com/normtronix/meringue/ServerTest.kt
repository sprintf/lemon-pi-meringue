package com.normtronix.meringue

import com.google.protobuf.Empty
import io.grpc.CallCredentials
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.*
import net.devh.boot.grpc.client.security.CallCredentialsHelper
import net.devh.boot.grpc.server.security.authentication.BasicGrpcAuthenticationReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import java.util.concurrent.TimeUnit

@SpringBootTest
internal class ServerTest {

    val grpcCleanup = GrpcCleanupRule()
    var asyncStub: LemonPiCommsServiceGrpc.LemonPiCommsServiceStub? = null

    fun setupBlockingStub() :  LemonPiCommsServiceGrpc.LemonPiCommsServiceBlockingStub {
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

        asyncStub = LemonPiCommsServiceGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).
                directExecutor().build()
            )
        ).withDeadlineAfter(1000, TimeUnit.MILLISECONDS)

        return LemonPiCommsServiceGrpc.newBlockingStub(
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
            val carNumber = Rpc.CarNumber.newBuilder().setCarNumber("12").build()

            val carCreds = CallCredentialsHelper.basicAuth("thil/12", "foo")
            val pitCreds = CallCredentialsHelper.basicAuth("thil/12-pit", "foo")
            val observer = SO<Rpc.ToPitMessage>()

            coroutineScope {
                launch {
                    stub.withCallCredentials(carCreds).sendMessageFromCar(ping)
                }

                launch {
                    delay(10)
                    stub.withCallCredentials(carCreds).sendMessageFromCar(ping)
                }

                launch {
                    asyncStub?.withCallCredentials(pitCreds)?.receiveCarMessages(carNumber, observer)
                }

                suspend {
                    delay(500)
                }

            }
            assertEquals(2, observer.capture.size)
        }
    }

    class SO<T>() :StreamObserver<T> {
        val capture : MutableList<T> = mutableListOf()

        override fun onNext(value: T?) {
            if (value != null) {
                capture.add(value)
            }
        }

        override fun onError(t: Throwable?) {
            println("got an error")
        }

        override fun onCompleted() {
            println("its complete")
        }

    }

    internal fun createPingMessage(carNumber: String, seqNum: Int) : Rpc.ToPitMessage {
        val pingMessage = Rpc.ToPitMessage.newBuilder().pingBuilder
            .setSender(carNumber)
            .setSeqNum(seqNum)
            .build()
        return Rpc.ToPitMessage.newBuilder().mergePing(pingMessage).build()
    }

//    @Test
//    fun testASync() {
//        val stub = setupAsyncStub()
//        val ping = Rpc.ToPitMessage.newBuilder().pingBuilder
//            .setSeqNum(1)
//            .setSender("12")
//            .build()
//        val ro = stub.
//        stub.sendMessageFromCar(Rpc.ToPitMessage.newBuilder().mergePing(ping).build(), ro)
//
//        val carNumbers = Rpc.CarNumbers.newBuilder()
//            .addCarNumber("12").build()
//        val receiveCarMessages = stub.receiveCarMessages(carNumbers)
//        stub.sendMessageFromCar(Rpc.ToPitMessage.newBuilder().mergePing(ping).build())
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

    // with radio you can't talk from far away
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