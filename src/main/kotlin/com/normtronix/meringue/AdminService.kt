package com.normtronix.meringue

import com.google.protobuf.Empty
import io.grpc.Status
import com.normtronix.meringue.racedata.DataSource1
import com.normtronix.meringue.racedata.DataSourceHandler
import com.normtronix.meringue.racedata.InvalidRaceId
import com.normtronix.meringue.racedata.RaceOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.BadCredentialsException
import java.io.IOException

@GrpcService(interceptors = [AdminSecurityInterceptor::class])
@Configuration
class AdminService : AdminServiceGrpcKt.AdminServiceCoroutineImplBase() {

    private val activeMap = mutableMapOf<Handle, Job>()

    @Autowired
    lateinit var trackMetaData: TrackMetaDataLoader

    @Autowired
    lateinit var lemonPiService: Server

    @Autowired
    lateinit var authService: AuthService

    @Value("\${adminUsername}")
    lateinit var adminUsername:String

    @Value("\${adminPassword}")
    lateinit var adminPassword:String

    var raceDataSourceFactoryFn : (String) -> DataSource1 = fun(x:String) : DataSource1 { return DataSource1(x) }

    override suspend fun ping(request: Empty): Empty {
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
                .setName(getTrackName(request.trackCode))
                .setHandle(key.toString())
                .setRunning(true)
                .build()
        }

        val raceDataSource = raceDataSourceFactoryFn(request.providerId)
        val streamUrl = raceDataSource.connect()
        coroutineScope {
            val xyv = launch {
                try {
                    raceDataSource.stream(streamUrl, DataSourceHandler(RaceOrder(), request.trackCode, getConnectedCars(request.trackCode)))
                } finally {
                    log.warn("race data stream finished for $key / $streamUrl")
                }
            }
            activeMap[key] = xyv
        }
        return MeringueAdmin.RaceDataConnectionResponse.newBuilder()
            .setName(getTrackName(request.trackCode))
            .setHandle(key.toString())
            .setRunning(activeMap[key]?.isActive ?: false)
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
                .setName(getTrackName(it.key.trackCode))
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