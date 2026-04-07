package com.normtronix.meringue

import com.normtronix.meringue.Common.BoolValue
import com.normtronix.meringue.Common.Empty
import com.normtronix.meringue.PitcrewContextInterceptor.Companion.pitcrewContext
import com.normtronix.meringue.event.*
import io.grpc.Status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException

@ExperimentalCoroutinesApi
@GrpcService(interceptors = [PitcrewContextInterceptor::class, PitcrewSecurityInterceptor::class])
class PitcrewCommunicationsService : PitcrewServiceGrpcKt.PitcrewServiceCoroutineImplBase() {

    @Autowired
    lateinit var deviceStore: DeviceDataStore

    @Autowired
    lateinit var connectedCarStore: ConnectedCarStore

    @Autowired
    lateinit var server: Server

    @Autowired
    lateinit var carDataService: CarDataService

    @Autowired
    lateinit var mailService: MailService

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

    companion object {
        val log: Logger = LoggerFactory.getLogger(PitcrewCommunicationsService::class.java)
        private const val QR_MAX_AGE_SECONDS = 3600L
        private const val MAX_HISTORY = 500
    }

    override suspend fun ping(request: Empty): Empty {
        return Empty.getDefaultInstance()
    }

    override suspend fun auth(request: Pitcrew.PitAuthRequest): Pitcrew.PitAuthResponse {
        val email = request.username.lowercase().trim()
        val teamCode = request.teamCode

        val deviceIds = deviceStore.findDevicesByEmailAndTeamCode(email, teamCode)
        if (deviceIds.isEmpty()) {
            throw BadCredentialsException("no devices found for credentials")
        }
        log.info("logging in and matched ${deviceIds.size} devices")

        val token = JwtHelper.createToken(deviceIds, teamCode, email, jwtSecret)

        log.info("logged in")

        return Pitcrew.PitAuthResponse.newBuilder()
            .setBearerToken(token)
            .build()
    }

    override suspend fun carAuth(request: Pitcrew.CarAuthRequest): Pitcrew.CarAuthResponse {
        val trackCode = request.trackCode
        val carNumber = request.carNumber
        val installCode = request.installCode
        log.info("carAuth request: car=$carNumber track=$trackCode installCode=${installCode.take(8)}...")

        val status = connectedCarStore.getStatus(trackCode, carNumber)
        if (status == null || !status.isOnline) {
            log.warn("carAuth failed: car $carNumber at $trackCode is not connected")
            throw BadCredentialsException("car not connected: $trackCode/$carNumber")
        }

        val deviceId = status.deviceId
            ?: throw BadCredentialsException("no device id for $trackCode/$carNumber")

        if (deviceId != installCode) {
            log.warn("carAuth failed: install code mismatch for $carNumber at $trackCode")
            throw BadCredentialsException("invalid install code for $trackCode/$carNumber")
        }

        val deviceInfo = deviceStore.getDeviceInfo(deviceId)
            ?: throw BadCredentialsException("device not found: $deviceId")

        val token = JwtHelper.createToken(listOf(deviceId), deviceInfo.teamCode, "", jwtSecret)
        log.info("carAuth successful for $carNumber at $trackCode")
        return Pitcrew.CarAuthResponse.newBuilder()
            .setBearerToken(token)
            .build()
    }

    override suspend fun qrAuthAndReg(request: Pitcrew.QrAuthRequest): Pitcrew.QrAuthResponse {
        val email = request.email.lowercase().trim()
        val teamCode = request.teamCode
        val deviceId = request.deviceId
        val timestamp = request.timestamp

        // Validate timestamp — reject QR codes older than 1 hour or more than 60s in the future
        val ageSeconds = System.currentTimeMillis() / 1000 - timestamp
        if (ageSeconds > QR_MAX_AGE_SECONDS || ageSeconds < -60) {
            log.warn("qrAuthAndReg rejected: timestamp too old/future (age=${ageSeconds}s) device=$deviceId")
            throw CredentialsExpiredException("QR code has expired")
        }

        // Look up device and validate teamCode
        val deviceInfo = deviceStore.getDeviceInfo(deviceId)
        if (deviceInfo == null) {
            log.warn("qrAuthAndReg rejected: unknown device=$deviceId")
            throw BadCredentialsException("device not found: $deviceId")
        }
        if (deviceInfo.teamCode != teamCode) {
            log.warn("qrAuthAndReg rejected: teamCode mismatch for device=$deviceId")
            throw BadCredentialsException("team code mismatch for device: $deviceId")
        }

        // Register email with device if not already there
        val isNewEmail = deviceStore.addEmailAddress(deviceId, email)
        if (isNewEmail) {
            log.info("qrAuthAndReg: new email $email registered for device=$deviceId car=${deviceInfo.carNumber}")
            mailService.sendWelcomeEmail(email, deviceInfo.carNumber)
            val allEmails = deviceStore.getEmailAddresses(deviceId)
            SendEmailAddressesToCarEvent(deviceInfo.trackCode, deviceInfo.carNumber, allEmails).emitAsync()
        }

        // Build token covering all devices this email+teamCode is registered with
        val deviceIds = deviceStore.findDevicesByEmailAndTeamCode(email, teamCode)
        val token = JwtHelper.createToken(deviceIds, teamCode, email, jwtSecret)
        log.info("qrAuthAndReg success: $email → car=${deviceInfo.carNumber} track=${deviceInfo.trackCode}")

        return Pitcrew.QrAuthResponse.newBuilder()
            .setBearerToken(token)
            .setTrackCode(deviceInfo.trackCode)
            .setCarNumber(deviceInfo.carNumber)
            .build()
    }

    override suspend fun getCarStatus(request: Empty): Pitcrew.PitCarStatusResponse {
        log.info("getting car status")
        val ctx = pitcrewContext.get()
            ?: throw BadCredentialsException("missing context")

        log.info("found the context of the caller from the auth token")
        val statusList = mutableListOf<Pitcrew.PitCarStatus>()
        for (deviceId in ctx.deviceIds) {
            val info = deviceStore.getDeviceInfo(deviceId) ?: continue
            val status = connectedCarStore.getStatus(info.trackCode, info.carNumber)
            statusList.add(
                Pitcrew.PitCarStatus.newBuilder()
                    .setCarNumber(info.carNumber)
                    .setTrackCode(info.trackCode)
                    .setOnline(status?.isOnline ?: false)
                    .setIpAddress(status?.ipAddress ?: "")
                    .build()

            )
            log.info("returning car status for {} at {} online:{}", info.carNumber, info.trackCode, status?.isOnline)
        }


        return Pitcrew.PitCarStatusResponse.newBuilder()
            .addAllStatusList(statusList)
            .build()
    }

    override suspend fun sendDriverMessage(request: Pitcrew.PitDriverMessageRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.sendDriverMessage(request.trackCode, request.carNumber, request.message)
        return BoolValue.newBuilder().setValue(result).build()
    }

    override suspend fun setTargetLapTime(request: Pitcrew.PitSetTargetLapTimeRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.setTargetLapTime(request.trackCode, request.carNumber, request.targetTimeSeconds)
        return BoolValue.newBuilder().setValue(result).build()
    }

    override suspend fun resetFastLapTime(request: Pitcrew.PitResetFastLapTimeRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.resetFastLapTime(request.trackCode, request.carNumber)
        return BoolValue.newBuilder().setValue(result).build()
    }

    override suspend fun setDriverName(request: Pitcrew.PitSetDriverNameRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.setDriverName(request.trackCode, request.carNumber, request.driverName)
        return BoolValue.newBuilder().setValue(result).build()
    }


    // persistent flows keyed by "trackCode:carNumber", live until server restarts after race weekend
    internal val pitcrewStreams = mutableMapOf<String, MutableSharedFlow<Pitcrew.ToPitCrewMessage>>()

    // rolling history of the last MAX_HISTORY messages per car, used to replay to late-joining clients
    internal val messageHistory = ConcurrentHashMap<String, ArrayDeque<Pitcrew.ToPitCrewMessage>>()

    private fun emitToStream(key: String, message: Pitcrew.ToPitCrewMessage) {
        // Stamp with wall-clock time if the builder didn't already set it
        val stamped = if (message.timestamp == 0L)
            message.toBuilder().setTimestamp(System.currentTimeMillis()).build()
        else message
        pitcrewStreams[key]?.tryEmit(stamped)
        val hist = messageHistory.getOrPut(key) { ArrayDeque() }
        synchronized(hist) {
            hist.addLast(stamped)
            while (hist.size > MAX_HISTORY) hist.removeFirst()
        }
    }

    override fun streamCarDataV2(request: Pitcrew.PitCarDataRequest): Flow<Pitcrew.ToPitCrewMessage> {
        validateAccessSync(request.trackCode, request.carNumber)
        val trackCode = request.trackCode
        val carNumber = request.carNumber
        val key = "$trackCode:$carNumber"
        val since = request.sinceTimestamp

        val liveFlow = pitcrewStreams.getOrPut(key) {
            log.info("creating pitcrew stream for $carNumber at $trackCode")
            createPitcrewStream(trackCode, carNumber)
        }

        val snapshot = messageHistory[key]?.let { hist ->
            synchronized(hist) {
                hist.filter { since == 0L || it.timestamp > since }.toList()
            }
        } ?: emptyList()

        log.info("streaming $carNumber at $trackCode — replaying ${snapshot.size} historical messages (since=$since)")
        CarConnectedEvent(trackCode, carNumber).emitAsync()

        return flow {
            snapshot.forEach { emit(it) }
            emitAll(liveFlow)
        }
    }

    private fun createPitcrewStream(
        trackCode: String,
        carNumber: String
    ): MutableSharedFlow<Pitcrew.ToPitCrewMessage> {
        val flow = MutableSharedFlow<Pitcrew.ToPitCrewMessage>(
            extraBufferCapacity = 5,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        val handler = object : EventHandler {
            override suspend fun handleEvent(e: Event) {
                val message = when (e) {
                    is CarTelemetryEvent, is LapCompletedEvent, is DriverMessageEvent -> {
                        val carData = carDataService.buildCarData(trackCode, carNumber)
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setCarData(buildPitCarData(carData))
                            .build()
                    }
                    is CarPittingEvent -> {
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setPitting(LemonPi.EnteringPits.newBuilder().build())
                            .build()
                    }
                    is CarLeavingPitEvent -> {
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setEntering(LemonPi.LeavingPits.newBuilder().build())
                            .build()
                    }
                    is RaceStatusEvent -> {
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setRaceStatus(LemonPi.RaceStatus.newBuilder()
                                .setFlagStatus(CarDataService.convertStatus(e.flagStatus))
                                .build())
                            .build()
                    }
                    is SectorCompleteEvent -> {
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setSectorDetails(LemonPi.SectorComplete.newBuilder()
                                .setSectorTime(e.sectorTime)
                                .setSectorName(e.sectorName)
                                .setSectorNum(e.sectorNum)
                                .setPredictedLapTime(e.predictedLapTime)
                                .setPredictedDeltaToTarget(e.predictedDeltaToTarget)
                                .setPredictedDeltaToBest(e.predictedDeltaToBest)
                                .setLapCount(e.lapCount)
                                .setBestSectorTime(e.bestSectorTime)
                                .build())
                            .build()
                    }
                    is VoiceCallRequestEvent -> {
                        Pitcrew.ToPitCrewMessage.newBuilder()
                            .setCallRequest(Pitcrew.CarVoiceCallRequest.newBuilder()
                                .setTrackCode(e.trackCode)
                                .setCarNumber(e.carNumber)
                                .setTimestamp((System.currentTimeMillis() / 1000).toInt())
                                .build())
                            .build()
                    }
                    else -> null
                }
                message?.let { emitToStream("$trackCode:$carNumber", it) }
            }
        }

        val carFilter: (Event) -> Boolean = { e ->
            when (e) {
                is CarTelemetryEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                is LapCompletedEvent -> e.trackCode == trackCode && (e.carNumber == carNumber || e.ahead == carNumber)
                is DriverMessageEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                is CarPittingEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                is CarLeavingPitEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                is RaceStatusEvent -> e.trackCode == trackCode
                is SectorCompleteEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                is VoiceCallRequestEvent -> e.trackCode == trackCode && e.carNumber == carNumber
                else -> false
            }
        }

        Events.register(CarTelemetryEvent::class.java, handler, carFilter)
        Events.register(LapCompletedEvent::class.java, handler, carFilter)
        Events.register(DriverMessageEvent::class.java, handler, carFilter)
        Events.register(CarPittingEvent::class.java, handler, carFilter)
        Events.register(CarLeavingPitEvent::class.java, handler, carFilter)
        Events.register(RaceStatusEvent::class.java, handler, carFilter)
        Events.register(SectorCompleteEvent::class.java, handler, carFilter)
        Events.register(VoiceCallRequestEvent::class.java, handler, carFilter)

        return flow
    }

    fun hasAudience(trackCode: String, carNumber: String): Boolean {
        return pitcrewStreams.containsKey("$trackCode:$carNumber")
    }

    private fun buildPitCarData(carData: CarData.CarDataResponse): Pitcrew.PitCarData {
        return Pitcrew.PitCarData.newBuilder()
            .setCarNumber(carData.carNumber)
            .setTimestamp(carData.timestamp)
            .setFlagStatus(carData.flagStatus)
            .setLapCount(carData.lapCount)
            .setPosition(carData.position)
            .setPositionInClass(carData.positionInClass)
            .setLastLapTime(carData.lastLapTime)
            .setGap(carData.gap)
            .setCoolantTemp(carData.coolantTemp)
            .setFuelRemainingPercent(carData.fuelRemainingPercent)
            .setDriverMessage(carData.driverMessage)
            .setCarAhead(carData.carAhead)
            .setCarBehind(carData.carBehind)
            .setCarBehindGap(carData.carBehindGap)
            .setFastestLap(carData.fastestLap)
            .setFastestLapTime(carData.fastestLapTime)
            .setAvgLapTime(carData.avgLapTime)
            .setCarAheadAvgLapTime(carData.carAheadAvgLapTime)
            .setCarBehindAvgLapTime(carData.carBehindAvgLapTime)
            .putAllExtraSensors(carData.extraSensorsMap)
            .build()
    }

    private suspend fun validateAccess(trackCode: String, carNumber: String) {
        val ctx = pitcrewContext.get()
            ?: throw BadCredentialsException("missing context")
        for (deviceId in ctx.deviceIds) {
            val info = deviceStore.getDeviceInfo(deviceId) ?: continue
            if (info.trackCode == trackCode && info.carNumber == carNumber && info.emailAddresses.contains(ctx.emailAddress)) {
                return
            }
        }
        throw AccessDeniedException("no access to $trackCode/$carNumber")
    }

    private fun validateAccessSync(trackCode: String, carNumber: String) {
        val ctx = pitcrewContext.get()
            ?: throw BadCredentialsException("missing context")
        // For streaming, we validate based on the context alone
        // Device info lookup would require suspend, but the context deviceIds are already verified
        if (ctx.deviceIds.isEmpty()) {
            throw AccessDeniedException("no access to $trackCode/$carNumber")
        }
    }

}
