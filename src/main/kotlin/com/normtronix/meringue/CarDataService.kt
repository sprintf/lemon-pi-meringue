package com.normtronix.meringue

import com.google.common.cache.CacheBuilder
import com.google.protobuf.Empty
import com.normtronix.meringue.event.*
import com.normtronix.meringue.racedata.InvalidRaceId
import com.normtronix.meringue.racedata.PositionEnum
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

@GrpcService
class CarDataService : CarDataServiceGrpcKt.CarDataServiceCoroutineImplBase(),
    InitializingBean, EventHandler {

    @Autowired
    lateinit var adminService: AdminService

    private val channelMap = mutableMapOf<String, MutableSharedFlow<CarData.CarDataResponse>>()

    private val trackPositionMap = mutableMapOf<String, MutableSharedFlow<CarData.CarPositionDataResponse>>()

    override fun afterPropertiesSet() {
        Events.register(CarTelemetryEvent::class.java, this)
        Events.register(DriverMessageEvent::class.java, this)
        Events.register(LapCompletedEvent::class.java, this)
    }

    override suspend fun ping(request: Empty): Empty {
        return Empty.getDefaultInstance()
    }

    override suspend fun getRaceField(request: CarData.RaceFieldRequest) : CarData.RaceFieldResponse {
        val view = adminService.getRaceView(request.trackCode) ?: throw InvalidRaceId()
        val result = view.getField().stream().map {
            CarData.RaceParticipant.newBuilder()
                .setCarNumber(it.carNumber)
                .setTeamName(it.teamDriverName)
                .build()
        }.collect(Collectors.toList())
        return CarData.RaceFieldResponse.newBuilder().addAllParticipants(result).build()
    }

    override suspend fun getCarData(request: CarData.CarDataRequest): CarData.CarDataResponse {
        return buildCarData(request.trackCode, request.carNumber)
    }

    override fun streamCarData(request: CarData.CarDataRequest): Flow<CarData.CarDataResponse> {
        val key = buildKey(request.trackCode, request.carNumber)
        val baseFlow = channelMap.getOrPut(key) { MutableSharedFlow() }
        // start tracking this car
        CarConnectedEvent(request.trackCode, request.carNumber).emitAsync()
        return baseFlow.asSharedFlow()
    }

    override fun streamCarPositionsAtTrack(request: CarData.CarPositionDataRequest): Flow<CarData.CarPositionDataResponse> {
        val trackFlow = trackPositionMap.getOrPut(request.trackCode) {
            val result = MutableSharedFlow<CarData.CarPositionDataResponse>()
            Events.register(GpsPositionEvent::class.java,
                            object : EventHandler {
                                override suspend fun handleEvent(e: Event) {
                                    result.emit(CarData.CarPositionDataResponse.newBuilder().apply {
                                        this.position = (e as GpsPositionEvent).position
                                        this.carNumber = e.carNumber
                                    }.build())
                                }
                            }
            ) { e -> e is GpsPositionEvent && e.trackCode == request.trackCode }
            result
        }
        return trackFlow.asSharedFlow()
    }

    internal fun buildCarData(trackCode: String, carNumber: String): CarData.CarDataResponse {
        val view = adminService.getRaceView(trackCode) ?: throw InvalidRaceId()
        // log.info(view.toString())
        view.lookupCar(carNumber)?.let {
            val carAhead = it.getCarAhead(PositionEnum.IN_CLASS) ?: it.getCarAhead(PositionEnum.OVERALL)
            val bldr = CarData.CarDataResponse.newBuilder().apply {
                this.carNumber = it.carNumber
                this.flagStatus = convertStatus(view.raceStatus)
                this.lapCount = it.lapsCompleted
                this.position = it.position
                this.positionInClass = it.positionInClass
                this.lastLapTime = it.lastLapTime.toFloat()
                this.gap = it.gap((carAhead))
                this.fastestLap = it.fastestLap
                this.fastestLapTime = it.fastestLapTime.toFloat()
                it.lastLapAbsTimestamp?.let { ts ->
                    this.timestamp = ts.toEpochMilli()
                }
            }
            carAhead?.let { ahead ->
                bldr.carAhead = ahead.carNumber
            }

            val key = buildKey(trackCode, carNumber)
            telemetryMap.getIfPresent(key)?.apply {
                bldr.coolantTemp = this.coolantTemp
                bldr.fuelRemainingPercent = this.fuelRemainingPercent
            }
            driverMessageMap.getIfPresent(key)?.apply {
                bldr.driverMessage = this.message
            }

            return bldr.build()
        }
        throw NoSuchCarException("no such car")
    }

    internal class NoSuchCarException(s: String) : Exception(s)

    internal val driverMessageMap = CacheBuilder
        .newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<String, DriverMessageEvent>()

    internal val telemetryMap = CacheBuilder
        .newBuilder()
        .expireAfterWrite(120, TimeUnit.SECONDS)
        .build<String, CarTelemetryEvent>()

    override suspend fun handleEvent(e: Event) {
        if (e is DriverMessageEvent) {
            val key = buildKey(e.trackCode, e.carNumber)
            driverMessageMap.put(buildKey(e.trackCode, e.carNumber), e)
            channelMap[key]?.emit(buildCarData(e.trackCode, e.carNumber))
        } else if (e is CarTelemetryEvent) {
            val key = buildKey(e.trackCode, e.carNumber)
            telemetryMap.put(buildKey(e.trackCode, e.carNumber), e)
            channelMap[key]?.emit(buildCarData(e.trackCode, e.carNumber))
        } else if (e is LapCompletedEvent) {
            val key = buildKey(e.trackCode, e.carNumber)
            log.info("sending lap completed for car ${e.carNumber}")
            channelMap[key]?.emit(buildCarData(e.trackCode, e.carNumber))
        }
    }

    @GrpcExceptionHandler(InvalidRaceId::class)
    internal fun handleInvalidRaceId(e: InvalidRaceId) : Status {
        return Status.INVALID_ARGUMENT.withCause(e)
    }

    @GrpcExceptionHandler(NoSuchCarException::class)
    internal fun handleNoSuchCar(e: NoSuchCarException) : Status {
        return Status.INVALID_ARGUMENT.withCause(e)
    }

    private fun buildKey(trackCode: String, carNumber: String) : String =
        "$trackCode:$carNumber"

    companion object {
        val log: Logger = LoggerFactory.getLogger(CarDataService::class.java)

        internal fun convertStatus(raceStatus: String): RaceFlagStatusOuterClass.RaceFlagStatus {
            return when(raceStatus.trim().lowercase()) {
                "green" -> {RaceFlagStatusOuterClass.RaceFlagStatus.GREEN}
                "yellow" -> {RaceFlagStatusOuterClass.RaceFlagStatus.YELLOW}
                "red" -> {RaceFlagStatusOuterClass.RaceFlagStatus.RED}
                "black" -> {RaceFlagStatusOuterClass.RaceFlagStatus.BLACK}
                else -> {RaceFlagStatusOuterClass.RaceFlagStatus.UNKNOWN}
            }
        }
    }

}