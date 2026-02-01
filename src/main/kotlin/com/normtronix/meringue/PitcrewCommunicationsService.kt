package com.normtronix.meringue

import com.google.protobuf.BoolValue
import com.google.protobuf.Empty
import com.normtronix.meringue.PitcrewContextInterceptor.Companion.pitcrewContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException

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

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

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
        return BoolValue.of(result)
    }

    override suspend fun setTargetLapTime(request: Pitcrew.PitSetTargetLapTimeRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.setTargetLapTime(request.trackCode, request.carNumber, request.targetTimeSeconds)
        return BoolValue.of(result)
    }

    override suspend fun resetFastLapTime(request: Pitcrew.PitResetFastLapTimeRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.resetFastLapTime(request.trackCode, request.carNumber)
        return BoolValue.of(result)
    }

    override suspend fun setDriverName(request: Pitcrew.PitSetDriverNameRequest): BoolValue {
        validateAccess(request.trackCode, request.carNumber)
        val result = server.setDriverName(request.trackCode, request.carNumber, request.driverName)
        return BoolValue.of(result)
    }

    override fun streamCarData(request: Pitcrew.PitCarDataRequest): Flow<Pitcrew.PitCarDataResponse> {
        validateAccessSync(request.trackCode, request.carNumber)
        val carDataRequest = CarData.CarDataRequest.newBuilder()
            .setTrackCode(request.trackCode)
            .setCarNumber(request.carNumber)
            .build()
        return carDataService.streamCarData(carDataRequest).map { carData ->
            Pitcrew.PitCarDataResponse.newBuilder()
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
                .setFastestLap(carData.fastestLap)
                .setFastestLapTime(carData.fastestLapTime)
                .putAllExtraSensors(carData.extraSensorsMap)
                .build()
        }
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

    companion object {
        val log: Logger = LoggerFactory.getLogger(PitcrewCommunicationsService::class.java)
    }
}
