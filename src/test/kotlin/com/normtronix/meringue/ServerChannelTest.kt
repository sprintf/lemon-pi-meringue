package com.normtronix.meringue

import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ServerChannelTest {

    @Test
    fun testGettingMessagesFromCar(): Unit {
        runBlocking {
            val s = Server()
            s.carStore = mockk()
            every { s.carStore.storeConnectedCarDetails(any()) } returns Unit

            val collector = PitMessageCollector(s, "99")
            coroutineScope {
                val j1 = launch(requestor.asContextElement(
                    value= RequestDetails(
                        "thil",
                        "99",
                        "foo",
                        ""))) {
                    delay(50)
                    s.sendMessageFromCar(createPittingMessage("99", 1))
                }
                val j2 = launch(requestor.asContextElement(
                    value=RequestDetails(
                        "thil",
                        "99-pit",
                        "foo",
                        ""))) {
                    collector.getMessages()
                }
                val j3 = launch(requestor.asContextElement(
                    value=RequestDetails(
                        "thil",
                        "99",
                        "foo",
                        ""))) {
                    delay(100)
                    s.sendMessageFromCar(createPittingMessage("99", 2))
                }
                launch {
                    delay(200)
                    j1.cancel()
                    j2.cancel()
                    j3.cancel()
                }
            }
            assertEquals(2, collector.messages.size)
            assertEquals(1, collector.messages[0].pitting.seqNum)
            assertEquals(2, collector.messages[1].pitting.seqNum)
        }
    }

    @Test
    fun testGettingMessagesFromPit() {
        runBlocking {
            val s = Server()
            s.carStore = mockk()
            every { s.carStore.storeConnectedCarDetails(any()) } returns Unit

            val collector = CarMessageCollector(s, "99")
            coroutineScope {
                val j1 = launch(requestor.asContextElement(
                    value = RequestDetails(
                        "thil",
                        "99-pit",
                        "foo",
                        ""))) {
                    delay(50)
                    s.sendMessageFromPits(createDriverMessage("99", 1))
                }
                val j2 = launch(requestor.asContextElement(
                    value = RequestDetails(
                        "thil",
                        "99",
                        "foo",
                        ""))) {
                    collector.getMessages()
                }
                val j3 = launch(requestor.asContextElement(
                    value = RequestDetails(
                        "thil",
                        "99-pit",
                        "foo",
                        ""))) {
                    delay(100)
                    s.sendMessageFromPits(createDriverMessage("99", 2))
                }
                launch {
                    delay(500)
                    j1.cancel()
                    j2.cancel()
                    j3.cancel()
                }
            }
            assertEquals(2, collector.messages.size)
            assertEquals(1, collector.messages[0].message.seqNum)
            assertEquals(2, collector.messages[1].message.seqNum)
        }
    }

    internal fun createPittingMessage(carNumber: String, seqNum: Int) : LemonPi.ToPitMessage {
        val pittingMessage = LemonPi.ToPitMessage.newBuilder().pittingBuilder
            .setSender(carNumber)
            .setSeqNum(seqNum)
            .build()
        return LemonPi.ToPitMessage.newBuilder().mergePitting(pittingMessage).build()
    }

    internal fun createDriverMessage(carNumber: String, seqNum: Int) : LemonPi.ToCarMessage {
        val driverMessage = LemonPi.ToCarMessage.newBuilder().messageBuilder
            .setCarNumber(carNumber)
            .setSeqNum(seqNum)
            .setText("Hello")
            .build()
        return LemonPi.ToCarMessage.newBuilder().mergeMessage(driverMessage).build()
    }

    class PitMessageCollector(val s: Server, val carNum: String) {

        val messages: MutableList<LemonPi.ToPitMessage> = mutableListOf()

        suspend fun getMessages() {
            println("sending message to get stuff from car")
            val msg = s.receiveCarMessages(createCarNumber(carNum))
            println("collecting")
            msg.collect { messages.add(it) }
            println("finished")
        }
    }

    class CarMessageCollector(val s: Server, val carNum: String) {

        val messages: MutableList<LemonPi.ToCarMessage> = mutableListOf()

        suspend fun getMessages() {
            println("sending message to get stuff from pit")
            val msg = s.receivePitMessages(createCarNumber(carNum))
            msg.collect { messages.add(it) }
            println("finished")
        }
    }
}

internal fun createCarNumber(number: String): LemonPi.CarNumber {
    val builder = LemonPi.CarNumber.newBuilder()
    builder.setCarNumber(number)
    return builder.build()
}