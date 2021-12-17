package com.normtronix.meringue

import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ServerChannelTest {

    @Test
    fun testGettingMessagesFromCar(): Unit {
        runBlocking {
            val s = Server()
            val collector = PitMessageCollector(s, "99")
            coroutineScope {
                launch(requestor.asContextElement(value= RequestDetails("thil", "99", "foo"))) {
                    s.sendMessageFromCar(createPittingMessage("99", 1))
                }
                val bg = launch(requestor.asContextElement(value=RequestDetails("thil", "99-pit", "foo"))) {
                    collector.getMessages()
                }
                println("haha !")
                launch(requestor.asContextElement(value=RequestDetails("thil", "99", "foo"))) {
                    s.sendMessageFromCar(createPittingMessage("99", 2))
                }
                launch {
                    delay(200)
                    s.closeChannels()
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
            val collector = CarMessageCollector(s, "99")
            coroutineScope {
                launch(requestor.asContextElement(value = RequestDetails("thil", "99-pit", "foo"))) {
                    s.sendMessageFromPits(createDriverMessage("99", 1))
                }
                val bg = launch(requestor.asContextElement(value = RequestDetails("thil", "99", "foo"))) {
                    collector.getMessages()
                }
                println("haha !")
                launch(requestor.asContextElement(value = RequestDetails("thil", "99-pit", "foo"))) {
                    s.sendMessageFromPits(createDriverMessage("99", 2))
                }
                launch {
                    delay(500)
                    s.closeChannels()
                }
            }
            assertEquals(2, collector.messages.size)
            assertEquals(1, collector.messages[0].message.seqNum)
            assertEquals(2, collector.messages[1].message.seqNum)
        }
    }

    internal fun createPittingMessage(carNumber: String, seqNum: Int) : Rpc.ToPitMessage {
        val pittingMessage = Rpc.ToPitMessage.newBuilder().pittingBuilder
            .setSender(carNumber)
            .setSeqNum(seqNum)
            .build()
        return Rpc.ToPitMessage.newBuilder().mergePitting(pittingMessage).build()
    }

    internal fun createDriverMessage(carNumber: String, seqNum: Int) : Rpc.ToCarMessage {
        val driverMessage = Rpc.ToCarMessage.newBuilder().messageBuilder
            .setCarNumber(carNumber)
            .setSeqNum(seqNum)
            .setText("Hello")
            .build()
        return Rpc.ToCarMessage.newBuilder().mergeMessage(driverMessage).build()
    }

    class PitMessageCollector(val s: Server, val carNum: String) {

        val messages: MutableList<Rpc.ToPitMessage> = mutableListOf()

        suspend fun getMessages() {
            println("sending message to get stuff from car")
            val msg = s.receiveCarMessages(createCarNumber(carNum))
            println("collecting")
            msg.collect { messages.add(it) }
            println("finished")
        }
    }

    class CarMessageCollector(val s: Server, val carNum: String) {

        val messages: MutableList<Rpc.ToCarMessage> = mutableListOf()

        suspend fun getMessages() {
            println("sending message to get stuff from pit")
            val msg = s.receivePitMessages(createCarNumber(carNum))
            msg.collect { messages.add(it) }
            println("finished")
        }
    }
}

internal fun createCarNumber(number: String): Rpc.CarNumber {
    val builder = Rpc.CarNumber.newBuilder()
    builder.setCarNumber(number)
    return builder.build()
}