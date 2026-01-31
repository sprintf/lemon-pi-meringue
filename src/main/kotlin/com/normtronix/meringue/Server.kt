package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import com.normtronix.meringue.event.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.devh.boot.grpc.server.security.interceptors.ExceptionTranslatingServerInterceptor
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant


@ExperimentalCoroutinesApi
@GrpcService(interceptors = [ContextInterceptor::class, ServerSecurityInterceptor::class])
class Server : CommsServiceGrpcKt.CommsServiceCoroutineImplBase(), EventHandler {

    @Autowired
    private lateinit var exceptionTranslatingServerInterceptor: ExceptionTranslatingServerInterceptor

    @Autowired
    private lateinit var connectedCarStore: ConnectedCarStore

    @Autowired
    lateinit var carStore : ConnectedCarStore

    @Autowired
    lateinit var deviceStore : DeviceDataStore

    @Autowired
    lateinit var emailService : EmailAddressService

    // map of trackCode -> carNumber -> ChannelAndKey<LemonPi.ToPitMessage>
    val toPitIndex: MutableMap<String, MutableMap<String, ChannelAndKey<LemonPi.ToPitMessage>>> = mutableMapOf()
    // map of trackCode -> carNumber -> ChannelAndKey<LemonPi.ToCarMessage>
    val toCarIndex: MutableMap<String, MutableMap<String, ChannelAndKey<LemonPi.ToCarMessage>>> = mutableMapOf()

    // sequence number for sends emanating from here
    var seqNo = 1

    init {
        Events.register(RaceStatusEvent::class.java, this)
        Events.register(LapCompletedEvent::class.java, this)
        Events.register(SendEmailAddressesToCarEvent::class.java, this)
    }

    override suspend fun pingPong(request: Empty): Empty {
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromCar(request: LemonPi.ToPitMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val currentCar = requestDetails.carNum
        // todo : make sure that the car is the one sending from itself
        if (!request.hasPing()) {
            log.info("car ${currentTrack}/${currentCar} sending message")
        }
        getSendChannel(requestDetails, toPitIndex).emit(request)
        introspectToPitMessage(requestDetails, request)
        return Empty.getDefaultInstance()
    }

    override suspend fun sendMessageFromPits(request: LemonPi.ToCarMessage): Empty {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        val targetCar = extractTargetCar(request)
        log.info("pit ${currentTrack}/${requestDetails.carNum} sending message to $targetCar")
        getSendChannel(requestDetails, toCarIndex).emit(request)
        introspectToCarMessage(currentTrack, targetCar, request)
        return Empty.getDefaultInstance()
    }

    override fun receivePitMessages(request: LemonPi.CarNumber): Flow<LemonPi.ToCarMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        log.info("receiving messages for car ${request.carNumber} @ $currentTrack")
        val previousDeviceId = carStore.storeConnectedCarDetails(requestDetails)
        deviceStore.storeDeviceDetails(requestDetails, previousDeviceId)
        CarConnectedEvent(currentTrack, request.carNumber).emitAsync()
        return getSendChannel(requestDetails, toCarIndex).asSharedFlow()
    }

    override fun receiveCarMessages(request: LemonPi.CarNumber): Flow<LemonPi.ToPitMessage> {
        val requestDetails = requestor.get()
        val currentTrack = requestDetails.trackCode
        log.info("receiving car messages for ${request.carNumber} @ $currentTrack")
        // todo : this needs more thought
        // CarConnectedEvent(currentTrack, request.carNumber).emitAsync()
        return getSendChannel(requestDetails, toPitIndex).asSharedFlow()
    }

    internal suspend fun sendDriverMessage(trackCode: String, carNumber: String, message: String): Boolean {
        val internalMessage = LemonPi.ToCarMessage.newBuilder().messageBuilder
            .setCarNumber(carNumber)
            .setSender("meringue")
            .setText(message)
            .setSeqNum(seqNo++)
            .setTimestamp(Instant.now().epochSecond.toInt())
            .build()
        val wrapper = LemonPi.ToCarMessage.newBuilder()
            .setMessage(internalMessage)
            .build()

        log.info("request to send driver message to car $carNumber at $trackCode")

        toCarIndex[trackCode]?.get(carNumber)?.radioKey?.apply {
            val context = RequestDetails(trackCode, carNumber, this)
            getSendChannel(context, toCarIndex).emit(wrapper)
            log.info("sent driver message to car $carNumber at $trackCode")
            return true
        }
        return false
    }

    internal suspend fun resetFastLapTime(trackCode: String, carNumber: String): Boolean {
        val resetMessage = LemonPi.ToCarMessage.newBuilder().resetFastLapBuilder
            .setCarNumber(carNumber)
            .setSender("meringue")
            .setSeqNum(seqNo++)
            .setTimestamp(Instant.now().epochSecond.toInt())
            .build()
        val wrapper = LemonPi.ToCarMessage.newBuilder()
            .setResetFastLap(resetMessage)
            .build()

        log.info("request to reset fast lap on $carNumber at $trackCode")
        toCarIndex[trackCode]?.get(carNumber)?.radioKey?.apply {
            val context = RequestDetails(trackCode, carNumber, this)
            getSendChannel(context, toCarIndex).emit(wrapper)
            log.info("sent reset fast lap to car $carNumber at $trackCode")
            return true
        }
        return false
    }

    internal suspend fun setDriverName(trackCode: String, carNumber: String, driverName: String): Boolean {
        val driverNameMsg = LemonPi.ToCarMessage.newBuilder().driverNameBuilder
            .setCarNumber(carNumber)
            .setSender("meringue")
            .setDriverName(driverName)
            .setSeqNum(seqNo++)
            .setTimestamp(Instant.now().epochSecond.toInt())
            .build()
        val wrapper = LemonPi.ToCarMessage.newBuilder()
            .setDriverName(driverNameMsg)
            .build()

        log.info("request to set driver name on $carNumber at $trackCode")
        toCarIndex[trackCode]?.get(carNumber)?.radioKey?.apply {
            val context = RequestDetails(trackCode, carNumber, this)
            getSendChannel(context, toCarIndex).emit(wrapper)
            log.info("sent driver name to car $carNumber at $trackCode")
            return true
        }
        return false
    }

    internal suspend fun sendEmailAddresses(trackCode: String, carNumber: String, emailAddresses: List<String>): Boolean {
        val response = LemonPi.ToCarMessage.newBuilder().emailAddressesBuilder
            .setSender("meringue")
            .setSeqNum(seqNo++)
            .setTimestamp(Instant.now().epochSecond.toInt())
            .addAllEmailAddress(emailAddresses)
            .build()

        log.info("sent team email addresses to car {} ({} addresses)", carNumber, emailAddresses.size)
        val wrapper = LemonPi.ToCarMessage.newBuilder()
            .setEmailAddresses(response)
            .build()
        toCarIndex[trackCode]?.get(carNumber)?.radioKey?.apply {
            val context = RequestDetails(trackCode, carNumber, this)
            getSendChannel(context, toCarIndex).emit(wrapper)
            log.info("sent driver name to car $carNumber at $trackCode")
            return true
        }
        return false
    }

    internal suspend fun setTargetLapTime(trackCode: String, carNumber: String, targetTimeSeconds: Int): Boolean {
        val targetTime = LemonPi.ToCarMessage.newBuilder().setTargetBuilder
            .setCarNumber(carNumber)
            .setTargetLapTime(targetTimeSeconds.toFloat())
            .setSender("meringue")
            .setSeqNum(seqNo++)
            .setTimestamp(Instant.now().epochSecond.toInt())
            .build()
        val wrapper = LemonPi.ToCarMessage.newBuilder()
            .setSetTarget(targetTime)
            .build()

        log.info("request to set target lap time on $carNumber at $trackCode")
        toCarIndex[trackCode]?.get(carNumber)?.radioKey?.apply {
            val context = RequestDetails(trackCode, carNumber, this)
            getSendChannel(context, toCarIndex).emit(wrapper)
            log.info("sent target lap time to car $carNumber at $trackCode")
            return true
        }
        return false
    }

    private suspend fun introspectToPitMessage(requestDetails: RequestDetails,
                                               request: LemonPi.ToPitMessage) {
        val trackCode = requestDetails.trackCode
        val carNumber = requestDetails.carNum
        if (request.hasTelemetry()) {
            CarTelemetryEvent(
                trackCode,
                carNumber,
                request.telemetry.lapCount,
                request.telemetry.lastLapTime,
                request.telemetry.coolantTemp,
                request.telemetry.fuelRemainingPercent,
                request.telemetry.extraSensorsMap
            ).emit()
        } else if (request.hasPing()) {
            GpsPositionEvent(
                trackCode,
                carNumber,
                request.ping.gps
            ).emit()
        } else if (request.hasPitting()) {
            CarPittingEvent(
                trackCode,
                carNumber
            ).emit()
        } else if (request.hasEntering()) {
            CarLeavingPitEvent(
                trackCode,
                carNumber
            ).emit()
        } else if (request.hasEmailAddress()) {
            handleEditTeamEmailAddress(requestDetails, request.emailAddress)
        }
    }

    internal suspend fun handleEditTeamEmailAddress(requestDetails: RequestDetails,
                                                   editRequest: LemonPi.EditTeamEmailAddress) {
        val email = editRequest.emailAddress.lowercase().trim()
        val deviceId = requestDetails.deviceId

        if (deviceId.isBlank()) {
            log.warn("cannot manage email addresses without a device ID")
            return
        }

        if (editRequest.add) {
            if (emailService.isDeliverable(email)) {
                deviceStore.addEmailAddress(deviceId, email)
            } else {
                log.info("email {} not deliverable, not adding to device {}", email, deviceId)
            }
        } else {
            deviceStore.removeEmailAddress(deviceId, email)
        }

        val allAddresses = deviceStore.getEmailAddresses(deviceId)
        SendEmailAddressesToCarEvent(requestDetails.trackCode, requestDetails.carNum, allAddresses).emit()
    }

    private suspend fun introspectToCarMessage(trackCode: String,
                                               carNumber: String,
                                               request: LemonPi.ToCarMessage) {
        if (request.hasMessage()) {
            DriverMessageEvent(trackCode, carNumber, request.message.text).emit()
        }
    }

    private fun <T>getSendChannel(
        context: RequestDetails,
        index: MutableMap<String, MutableMap<String, ChannelAndKey<T>>>
    ): MutableSharedFlow<T> {
        val trackMap = index.getOrPut(context.trackCode) { mutableMapOf() }
        val existingChannelAndKey = trackMap[context.carNum]

        if (existingChannelAndKey == null) {
            log.info("new channel created for recipient ${context.carNum}")
            val result = ChannelAndKey(MutableSharedFlow<T>(0, 5, BufferOverflow.DROP_OLDEST), context.teamCode)
            trackMap[context.carNum] = result
            return result.channel
        }

        if (existingChannelAndKey.radioKey != context.teamCode) {
            // we have either two cars at the same track with different codes, or
            // someone changed their code at the track
            // see if there's a connected car w/ this deviceId
            val existingConnection = runBlocking { connectedCarStore.getStatus(context.trackCode, context.carNum) }
            val newChannelAndKey = existingConnection?.let {
                if (it.isOnline) {
                    if (context.deviceId == it.deviceId) {
                        // doesn't make much sense, but we will replace it anyway
                        ChannelAndKey(MutableSharedFlow<T>(0, 5, BufferOverflow.DROP_OLDEST), context.teamCode)
                    } else {
                        // in this case there's an online car on this channel, and its connected,
                        // throw an exception
                        throw MismatchedKeyException()
                    }
                } else {
                    // we're replacing a car that is no longer online
                    log.info("replacing send channel for car ${context.carNum} at ${context.trackCode}")
                    ChannelAndKey(MutableSharedFlow<T>(0, 5, BufferOverflow.DROP_OLDEST), context.teamCode)
                }
            } ?: run {
                log.info("replacing send channel for car ${context.carNum} at ${context.trackCode}")
                ChannelAndKey(MutableSharedFlow<T>(0, 5, BufferOverflow.DROP_OLDEST), context.teamCode)
            }
            trackMap[context.carNum] = newChannelAndKey
            return newChannelAndKey.channel
        } else {
            return existingChannelAndKey.channel
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
            throw InvalidCarMessageException("unable to extract car number from request")
        }
    }

    /*
     * get all the connected cars at a track ... useful for sending out yellow flags
     */
    private fun getConnectedCarChannels(trackCode: String) :List<MutableSharedFlow<LemonPi.ToCarMessage>> {
        val cars = toCarIndex[trackCode] ?: return emptyList()
        return cars.values.map{
            it.channel
        }.toList()
    }

    /*
     * get all the connected pits at a track ... useful for sending out yellow flags
     */
    private fun getConnectedPitChannels(trackCode: String) :List<MutableSharedFlow<LemonPi.ToPitMessage>> {
        val pits = toPitIndex[trackCode] ?: return emptyList()
        return pits.values.map{
            it.channel
        }.toList()
    }

    internal fun getConnectedCarNumbers(trackCode: String) :Set<String> {
        val cars = toCarIndex[trackCode] ?: return emptySet()
        return cars.entries.map {
            it.key
        }.toSet()
    }

    data class ChannelAndKey<T>(val channel: MutableSharedFlow<T>, val radioKey:String)

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
                    it.emit(LemonPi.ToCarMessage.newBuilder().mergeRaceStatus(msg).build())
                    log.info("sent flag message to a car")
                }
                getConnectedPitChannels(e.trackCode).forEach {
                    it.emit(LemonPi.ToPitMessage.newBuilder().mergeRaceStatus(msg).build())
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
                    .setGapToFront(e.gapToFront.toFloat())
                    .setGapToFrontDelta(e.gapToFrontDelta.toFloat())
                    .build()
                getConnectedCarNumbers(e.trackCode).forEach {
                    if (it == msg.carNumber || it == msg.carAhead.carNumber) {
                        toCarIndex[e.trackCode]?.get(it)?.channel?.emit(
                            LemonPi.ToCarMessage.newBuilder().mergeRacePosition(msg).build())
                        log.info("sent race position message to $it")
                        val pitChannel = toPitIndex[e.trackCode]?.get(it)?.channel
                        pitChannel?.emit(
                            LemonPi.ToPitMessage.newBuilder().mergeRacePosition(msg).build()
                        )
                    }
                }
            }
            is SendEmailAddressesToCarEvent -> {
                sendEmailAddresses(e.trackCode, e.carNumber, e.emailAddresses)
            }
        }
    }

    private fun convertFlagStatus(statusString: String) =
        when (statusString) {
            "" -> { RaceFlagStatusOuterClass.RaceFlagStatus.UNKNOWN }
            else -> RaceFlagStatusOuterClass.RaceFlagStatus.valueOf(statusString.trim().uppercase())
        }


}

class MismatchedKeyException: Exception()

class InvalidCarMessageException(message: String): Exception(message)

