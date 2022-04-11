package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.event.RaceDisconnectEvent
import com.normtronix.meringue.racedata.*
import io.grpc.Status
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import net.devh.boot.grpc.server.advice.GrpcAdvice
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.BadCredentialsException
import java.io.IOException
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.stream.Collectors


@GrpcService(interceptors = [AdminSecurityInterceptor::class])
@GrpcAdvice
@Configuration
class AdminService : AdminServiceGrpcKt.AdminServiceCoroutineImplBase(), InitializingBean {

    private val activeMap = mutableMapOf<Handle, Job>()

    @Autowired
    lateinit var appContext: ApplicationContext

    @Autowired
    lateinit var trackMetaData: TrackMetaDataLoader

    @Autowired
    lateinit var raceLister: DS1RaceLister

    @Autowired
    lateinit var lemonPiService: Server

    @Autowired
    lateinit var authService: AuthService

    @Value("\${adminUsername}")
    lateinit var adminUsername:String

    @Value("\${adminPassword}")
    lateinit var adminPassword:String

    @Value("\${force.race.ids:[]}")
    lateinit var forceRaceIds:List<String>

    @Value("\${log.racedata:String}")
    lateinit var logRaceData:String

    var raceDataSourceFactoryFn : (String) -> DataSource1 = fun(x:String) : DataSource1 { return DataSource1(x) }

    override fun afterPropertiesSet() {
        if (forceRaceIds.isNotEmpty() && forceRaceIds[0].contains(":")) {
            for (raceTuple in forceRaceIds) {
                val trackCode = raceTuple.split(":").first()
                val raceId = raceTuple.split(":").last()
                if (trackCode.isNotEmpty() && raceId.isNotEmpty()) {
                    _connectRaceData(trackCode, raceId)
                }
            }
        }
    }

    override suspend fun ping(request: Empty): Empty {
        log.info("received ping() ... logging threaddump..")
        val threadDump = StringBuffer(System.lineSeparator())
        val threadMXBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
        for (threadInfo in threadMXBean.dumpAllThreads(false, false)) {
            threadDump.append(threadInfo.toString())
        }
        log.info(threadDump.toString())
        return Empty.getDefaultInstance()
    }

    override suspend fun auth(request: MeringueAdmin.AuthRequest): MeringueAdmin.AuthResponse {
        if (request.username.equals(this.adminUsername) &&
                request.password.equals(this.adminPassword)) {
            val token = authService.createTokenForUser(request.username)

            return MeringueAdmin.AuthResponse.newBuilder()
                .setBearerToken(token)
                .build()
        }
        throw BadCredentialsException("invalid credentials")
    }

    override suspend fun listTracks(request: Empty): MeringueAdmin.TrackMetadataResponse {
        val bldr = MeringueAdmin.TrackMetadataResponse.newBuilder()
        trackMetaData.listTracks().forEach {
            bldr.addTrackBuilder()
                .setCode(it.code)
                .setName(it.name)
                .build()
        }
        return bldr.build()
    }

    override suspend fun connectToRaceData(request: MeringueAdmin.ConnectToRaceDataRequest): MeringueAdmin.RaceDataConnectionResponse {
        request.provider // should be RM
        request.trackCode // should be valid
        request.providerId // should be int for RM
        trackMetaData.validateTrackCode(request.trackCode)

        // next see if theres a connection already
        val key = Handle(request.trackCode, request.providerId)
        if (activeMap.containsKey(key) && activeMap[key]!!.isActive) {
            return MeringueAdmin.RaceDataConnectionResponse.newBuilder()
                .setTrackName(getTrackName(request.trackCode))
                .setTrackCode(request.trackCode)
                .setHandle(key.toString())
                .setRunning(true)
                .build()
        }

        val jobId = _connectRaceData(request.trackCode, request.providerId)
        activeMap[key] = jobId
        log.info("returning with result for race data stream $key")
        return MeringueAdmin.RaceDataConnectionResponse.newBuilder()
            .setTrackName(getTrackName(request.trackCode))
            .setTrackCode(request.trackCode)
            .setHandle(key.toString())
            .setRunning(activeMap[key]?.isActive ?: false)
            .build()
    }

    override suspend fun shutdown(request: Empty): Empty {
        log.warn("shutdown requested !!!")
        SpringApplication.exit(appContext, ExitCodeGenerator { 0 })
        return Empty.getDefaultInstance()
    }

    private fun _connectRaceData(trackCode: String, raceId: String): Job {
        val raceDataSource = raceDataSourceFactoryFn(raceId)
        raceDataSource.logRaceData = logRaceData.toBooleanStrict()
        val streamUrl = raceDataSource.connect()
        log.info("launching thread to run $raceId @ $trackCode")
        val jobId = GlobalScope.launch(newSingleThreadContext("thread-${trackCode}")) {
            raceDataSource.stream(
                streamUrl,
                DataSourceHandler(RaceOrder(), trackCode, getConnectedCars(trackCode))
            )
        }
        return jobId
    }

    override suspend fun listLiveRaces(request: Empty): MeringueAdmin.LiveRaceListResponse {
        val liveRaces = raceLister.getLiveRaces()
            .map {
                    MeringueAdmin.LiveRace.newBuilder()
                        .setRaceId(it.raceId)
                        .setTrackName(it.trackName)
                        .setEventName(it.eventName)
                        .build()
                }
            .collect(Collectors.toList())
        return MeringueAdmin.LiveRaceListResponse.newBuilder()
            .addAllRaces(liveRaces)
            .build()
    }

    private fun getConnectedCars(trackCode: String?): Set<String> {
        if (trackCode == null) {
            return setOf()
        }
        return lemonPiService.getConnectedCarNumbers(trackCode)
    }

    private fun getTrackName(trackCode: String?): String {
        return trackMetaData.codeToName(trackCode?:"unknown")
    }

    override suspend fun listRaceDataConnections(request: Empty): MeringueAdmin.RaceDataConnectionsResponse {
        // go through the list of connections, return status on each one
        val bldr = MeringueAdmin.RaceDataConnectionsResponse.newBuilder()
        activeMap.forEach {
            bldr.addResponseBuilder()
                .setTrackName(getTrackName(it.key.trackCode))
                .setTrackCode(it.key.trackCode)
                .setHandle(it.key.toString())
                .setRunning(it.value.isActive)
        }
        return bldr.build()
    }

    override suspend fun disconnectRaceData(request: MeringueAdmin.RaceDataConnectionRequest): Empty {
        // look up the do-hicky and cancel it
        log.info("request to disconnect ${request.handle}")
        val handle = Handle.fromString(request.handle)
        if (activeMap.containsKey(handle)) {
            log.info("race is active, performing cancel")
            RaceDisconnectEvent(handle.trackCode).emit()
            activeMap[handle]?.cancel()
        } else {
            log.info("request ignored ... no such running race")
        }
        return Empty.getDefaultInstance()
    }

    @GrpcExceptionHandler(InvalidRaceId::class)
    fun handleInvalidRaceId(e: InvalidRaceId) : Status {
        return Status.INVALID_ARGUMENT.withCause(e)
    }

    @GrpcExceptionHandler(InvalidTrackCode::class)
    fun handleInvalidTrackCode(e: InvalidTrackCode) : Status {
        return Status.INVALID_ARGUMENT.withCause(e)
    }

    @GrpcExceptionHandler(IOException::class)
    fun handleUnknownHostException(e: IOException) : Status {
        return Status.INTERNAL.withCause(e)
    }

    @GrpcExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentialsException(e: BadCredentialsException) : Status {
        return Status.UNAUTHENTICATED.withCause(e)
    }

    private data class Handle(val trackCode: String, val providerId: String) {

        override fun toString(): String {
            return "$trackCode:$providerId"
        }

        companion object {
            fun fromString(handle: String): Handle {
                val segments = handle.split(":")
                return Handle(segments[0], segments[1])
            }
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(AdminService::class.java)
    }

}