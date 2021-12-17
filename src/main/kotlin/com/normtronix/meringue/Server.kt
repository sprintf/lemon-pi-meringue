package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory


@GrpcService(interceptors = [ContextInterceptor::class, ServerSecurityInterceptor::class])
class Server() : LemonPiCommsServiceGrpcKt.LemonPiCommsServiceCoroutineImplBase() {

    // map of trackId -> pitId -> ChannelAndKey<Rpc.ToPitMessage>
    val toPitIndex: MutableMap<String, MutableMap<String, ChannelAndKey<Rpc.ToPitMessage>>> = mutableMapOf()
    // map of trackId -> pitId -> ChannelAndKey<Rpc.ToCarMessage>
    val toCarIndex: MutableMap<String, MutableMap<String, ChannelAndKey<Rpc.ToCarMessage>>> = mutableMapOf()

    override suspend fun pingPong(request: Empty): Empty {
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromCar(request: Rpc.ToPitMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackId
        val currentCar = requestDetails.carNum
        val currentKey = requestDetails.key
        // todo : make sure that the car is the one sending from itself
        log.info("car ${currentTrack}/${currentCar} sending message")
        getSendChannel(currentTrack, currentCar, currentKey, toPitIndex).send(request)
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromPits(request: Rpc.ToCarMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackId
        val currentKey = requestDetails.key
        val targetCar = extractTargetCar(request)
        log.info("pit ${currentTrack}/${requestDetails.carNum} sending message to $targetCar")
        getSendChannel(currentTrack, targetCar, currentKey, toCarIndex).send(request)
        return Empty.getDefaultInstance()
    }

    override fun receivePitMessages(request: Rpc.CarNumber): Flow<Rpc.ToCarMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackId
        val currentKey = requestDetails.key
        log.info("receiving pit messages for ${currentTrack}/${request.carNumber}")
        return getSendChannel(currentTrack, request.carNumber, currentKey, toCarIndex).consumeAsFlow()
    }

    override fun receiveCarMessages(request: Rpc.CarNumber): Flow<Rpc.ToPitMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackId
        val currentKey = requestDetails.key
        log.info("receiving car messages for ${currentTrack}/${request.carNumber}")
        return getSendChannel(currentTrack, request.carNumber, currentKey, toPitIndex).consumeAsFlow()
    }

    private fun <T>getSendChannel(
        currentTrack: String,
        currentCar: String,
        currentKey: String,
        index: MutableMap<String, MutableMap<String, ChannelAndKey<T>>>
    ): Channel<T> {
        if (!index.containsKey(currentTrack)) {
            index[currentTrack] = mutableMapOf<String, ChannelAndKey<T>>()
        }
        if (!index[currentTrack]?.containsKey(currentCar)!!) {
            val result = ChannelAndKey(Channel<T>(10), currentKey)
            index[currentTrack]?.set(currentCar, result)
            return result.channel
        } else {
            val channelAndKey = index[currentTrack]?.get(currentCar)
            if (channelAndKey != null && channelAndKey.key != currentKey) {
                throw MismatchedKeyException()
            }
            // the channel is already here ... but it may be toast
            if (channelAndKey == null || channelAndKey.channel.isClosedForSend) {
                val result = ChannelAndKey(Channel<T>(10), currentKey)
                index[currentTrack]?.set(currentCar, result)
                return result.channel
            }
            return channelAndKey.channel
        }
    }

    private fun extractTargetCar(request: Rpc.ToCarMessage): String {
        if (request.hasMessage()) {
            return request.message.carNumber
        } else if (request.hasSetTarget()) {
            return request.setTarget.carNumber
        } else if (request.hasResetFastLap()) {
            return request.resetFastLap.carNumber
        } else if (request.hasReboot()) {
            return request.reboot.carNumber
        } else if (request.hasSetFuel()) {
            return request.setFuel.carNumber
        } else {
            throw Exception("unable to extract car number from request ")
        }
    }

    /*
     * get all the connected cars at a track .. useful for sending out yellow flags
     */
    private fun getConnectedCarChannels(trackId: String) :List<Channel<Rpc.ToCarMessage>> {
        val cars = toCarIndex.get(trackId) ?: return emptyList()
        return cars.values.filter {
            !it.channel.isClosedForSend
        }.map{
            it.channel
        }.toList()
    }

    // test only
    fun closeChannels() {
        toPitIndex.values.forEach { it.values.forEach { it.channel.close() }}
        toCarIndex.values.forEach { it.values.forEach { it.channel.close() }}
    }

    data class ChannelAndKey<T>(val channel: Channel<T>, val key:String)

    companion object {
        val log: Logger = LoggerFactory.getLogger(Server::class.java)
    }
}

class MismatchedKeyException(): Exception()

