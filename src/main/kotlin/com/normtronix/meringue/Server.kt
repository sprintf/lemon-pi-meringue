package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import com.normtronix.meringue.event.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant


@ExperimentalCoroutinesApi
@GrpcService(interceptors = [ContextInterceptor::class, ServerSecurityInterceptor::class])
class Server : CommsServiceGrpcKt.CommsServiceCoroutineImplBase(), EventHandler {

    // map of trackCode -> carNumber -> ChannelAndKey<LemonPi.ToPitMessage>
    val toPitIndex: MutableMap<String, MutableMap<String, ChannelAndKey<LemonPi.ToPitMessage>>> = mutableMapOf()
    // map of trackCode -> carNumber -> ChannelAndKey<LemonPi.ToCarMessage>
    val toCarIndex: MutableMap<String, MutableMap<String, ChannelAndKey<LemonPi.ToCarMessage>>> = mutableMapOf()

    // sequence number for sends emanating from here
    var seqNo = 1

    init {
        CoroutineExceptionHandler { _, exception ->
            log.warn("CoroutineExceptionHandler got $exception")
        }
        Events.register(RaceStatusEvent::class.java, this)
        Events.register(LapCompletedEvent::class.java, this)
    }

    override suspend fun pingPong(request: Empty): Empty {
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromCar(request: LemonPi.ToPitMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val currentCar = requestDetails.carNum
        val currentKey = requestDetails.key
        // todo : make sure that the car is the one sending from itself
        log.info("car ${currentTrack}/${currentCar} sending message")
        getSendChannel(currentTrack, currentCar, currentKey, toPitIndex).send(request)
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromPits(request: LemonPi.ToCarMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val currentKey = requestDetails.key
        val targetCar = extractTargetCar(request)
        log.info("pit ${currentTrack}/${requestDetails.carNum} sending message to $targetCar")
        getSendChannel(currentTrack, targetCar, currentKey, toCarIndex).send(request)
        return Empty.getDefaultInstance()
    }

    override fun receivePitMessages(request: LemonPi.CarNumber): Flow<LemonPi.ToCarMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val currentKey = requestDetails.key
        log.info("receiving messages for car ${request.carNumber} @ $currentTrack")
        CarConnectedEvent(currentTrack, request.carNumber).emitAsync()
        return getSendChannel(currentTrack, request.carNumber, currentKey, toCarIndex).consumeAsFlow()
    }

    override fun receiveCarMessages(request: LemonPi.CarNumber): Flow<LemonPi.ToPitMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val currentKey = requestDetails.key
        log.info("receiving car messages for ${request.carNumber} @ $currentTrack")
        // todo : this needs more thought
        // CarConnectedEvent(currentTrack, request.carNumber).emitAsync()
        return getSendChannel(currentTrack, request.carNumber, currentKey, toPitIndex).consumeAsFlow()
    }

    private fun <T>getSendChannel(
        currentTrack: String,
        currentCar: String,
        currentKey: String,
        index: MutableMap<String, MutableMap<String, ChannelAndKey<T>>>
    ): Channel<T> {
        if (!index.containsKey(currentTrack)) {
            index[currentTrack] = mutableMapOf()
        }
        if (!index[currentTrack]?.containsKey(currentCar)!!) {
            log.info("new channel created for recipient $currentCar")
            val result = ChannelAndKey(Channel<T>(10), currentKey)
            index[currentTrack]?.set(currentCar, result)
            return result.channel
        } else {
            val channelAndKey = index[currentTrack]?.get(currentCar)
            if (channelAndKey != null && channelAndKey.radioKey != currentKey) {
                throw MismatchedKeyException()
            }
            // the channel is already here ... but it may be toast
            if (channelAndKey == null || channelAndKey.channel.isClosedForSend) {
                log.info("replacement channel created for recipient $currentCar")
                val result = ChannelAndKey(Channel<T>(10), currentKey)
                index[currentTrack]?.set(currentCar, result)
                return result.channel
            }
            return channelAndKey.channel
        }
    }

    private fun extractTargetCar(request: LemonPi.ToCarMessage): String {
        return if (request.hasMessage()) {
            request.message.carNumber
        } else if (request.hasSetTarget()) {
            request.setTarget.carNumber
        } else if (request.hasResetFastLap()) {
            request.resetFastLap.carNumber
        } else if (request.hasReboot()) {
            request.reboot.carNumber
        } else if (request.hasSetFuel()) {
            request.setFuel.carNumber
        } else {
            throw Exception("unable to extract car number from request ")
        }
    }

    /*
     * get all the connected cars at a track ... useful for sending out yellow flags
     */
    private fun getConnectedCarChannels(trackCode: String) :List<Channel<LemonPi.ToCarMessage>> {
        val cars = toCarIndex[trackCode] ?: return emptyList()
        return cars.values.filter {
            !it.channel.isClosedForSend
        }.map{
            it.channel
        }.toList()
    }

    /*
     * get all the connected pits at a track ... useful for sending out yellow flags
     */
    private fun getConnectedPitChannels(trackCode: String) :List<Channel<LemonPi.ToPitMessage>> {
        val pits = toPitIndex[trackCode] ?: return emptyList()
        return pits.values.filter {
            !it.channel.isClosedForSend
        }.map{
            it.channel
        }.toList()
    }

    internal fun getConnectedCarNumbers(trackCode: String) :Set<String> {
        val cars = toCarIndex[trackCode] ?: return emptySet()
        return cars.entries.filter {
            !it.value.channel.isClosedForSend
        }.map {
            it.key
        }.toSet()
    }

    // test only
    fun closeChannels() {
        toPitIndex.values.forEach { it.values.forEach { c -> c.channel.close() }}
        toCarIndex.values.forEach { it.values.forEach { c -> c.channel.close() }}
    }

    data class ChannelAndKey<T>(val channel: Channel<T>, val radioKey:String)

    companion object {
        val log: Logger = LoggerFactory.getLogger(Server::class.java)
    }

    override suspend fun handleEvent(e: Event) {
        log.info("handling event $e")
        when (e) {
            is RaceStatusEvent -> {
                if (e.flagStatus.isEmpty()) {
                    return
                }
                val msg = LemonPi.ToCarMessage.newBuilder().raceStatusBuilder
                    .setSender("meringue")
                    .setTimestamp(Instant.now().epochSecond.toInt())
                    .setSeqNum(seqNo++)
                    .setFlagStatus(convertFlagStatus(e.flagStatus))
                    .build()
                getConnectedCarChannels(e.trackCode).forEach {
                    it.send(LemonPi.ToCarMessage.newBuilder().mergeRaceStatus(msg).build())
                    log.info("sent flag message to a car")
                }
                getConnectedPitChannels(e.trackCode).forEach {
                    it.send(LemonPi.ToPitMessage.newBuilder().mergeRaceStatus(msg).build())
                    log.info("sent flag message to pits")
                }
            }
            is LapCompletedEvent -> {
                val ahead = LemonPi.Opponent.newBuilder()
                    .setCarNumber(e.ahead ?: "")
                    .setGapText(e.gap)
                    .build()
                val msg = LemonPi.ToCarMessage.newBuilder().racePositionBuilder
                    .setSender("meringue")
                    .setTimestamp(Instant.now().epochSecond.toInt())
                    .setSeqNum(seqNo++)
                    .setCarNumber(e.carNumber)
                    .setLapCount(e.lapCount)
                    .setPosition(e.position)
                    .setPositionInClass(e.positionInClass)
                    .setLastLapTime(e.lastLapTime.toFloat())
                    .setCarAhead(ahead)
                    .setFlagStatus(convertFlagStatus(e.flagStatus))
                    .build()
                getConnectedCarNumbers(e.trackCode).forEach {
                    if (it == msg.carNumber || it == msg.carAhead.carNumber) {
                        toCarIndex[e.trackCode]?.get(it)?.channel?.send(
                            LemonPi.ToCarMessage.newBuilder().mergeRacePosition(msg).build())
                        log.info("sent race position message to $it")
                        val pitChannel = toPitIndex[e.trackCode]?.get(it)?.channel
                        when (pitChannel?.isClosedForSend) {
                            true -> {}
                            else -> {
                                pitChannel?.send(
                                    LemonPi.ToPitMessage.newBuilder().mergeRacePosition(msg).build()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun convertFlagStatus(statusString: String) =
        when (statusString) {
            "" -> { LemonPi.RaceFlagStatus.UNKNOWN }
            else -> LemonPi.RaceFlagStatus.valueOf(statusString.trim().uppercase())
        }

}

class MismatchedKeyException: Exception()

