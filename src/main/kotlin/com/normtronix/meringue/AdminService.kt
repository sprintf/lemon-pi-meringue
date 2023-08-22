package com.normtronix.meringue

import com.google.protobuf.BoolValue
import com.google.protobuf.Empty
import com.google.protobuf.StringValue
import com.normtronix.meringue.MeringueAdmin.CarStatusSlackRequest
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
@kotlinx.coroutines.ExperimentalCoroutinesApi
class AdminService : AdminServiceGrpcKt.AdminServiceCoroutineImplBase(), InitializingBean {

    private val activeMap = mutableMapOf<Handle, Job>()

    @Autowired
    lateinit var appContext: ApplicationContext

    @Autowired
    lateinit var trackMetaData: TrackMetaDataLoader

    @Autowired
    lateinit var raceLister1: DS1RaceLister

    @Autowired
    lateinit var raceLister2: DS2RaceLister

    @Autowired
    lateinit var lemonPiService: Server

    @Autowired
    lateinit var connectedCarStore: ConnectedCarStore

    @Autowired
    lateinit var authService: AuthService

    @Autowired
    lateinit var slackService: SlackIntegrationService

    @Value("\${adminUsername}")
    lateinit var adminUsername:String

    @Value("\${adminPassword}")
    lateinit var adminPassword:String

    @Value("\${force.race.ids:[]}")
    lateinit var forceRaceIds:List<String>

    @Value("\${log.racedata:String}")
    lateinit var logRaceData:String

    @Value("\${lap-completed.delay:0}")
    lateinit var delayLapCompletedEvent:String

    var raceDataSourceFactoryFn : (MeringueAdmin.RaceDataProvider, String) -> RaceDataSource =
        fun(provider: MeringueAdmin.RaceDataProvider, x:String) : RaceDataSource {
            return when (provider) {
                MeringueAdmin.RaceDataProvider.PROVIDER_RM -> DataSource1(x)
                MeringueAdmin.RaceDataProvider.PROVIDER_RH -> DataSource2(x)
                else -> throw RuntimeException("unknown race provider")
            }
        }

    override fun afterPropertiesSet() {
        if (forceRaceIds.isNotEmpty() && forceRaceIds[0].contains(":")) {
            for (raceTuple in forceRaceIds) {
                val trackCode = raceTuple.split(":").first()
                val raceId = raceTuple.split(":").last()
                if (trackCode.isNotEmpty() && raceId.isNotEmpty()) {
                    activeMap[Handle(trackCode, raceId)] =
                        connectRaceData(trackCode, raceId, MeringueAdmin.RaceDataProvider.PROVIDER_RM)
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
        request.provider // should be RM or RH
        request.trackCode // should be valid
        request.providerId // should be int for RM // its a long string for RH
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

        val jobId = connectRaceData(request.trackCode, request.providerId, request.provider)
        activeMap[key] = jobId
        log.info("returning with result for race data stream $key")
        return MeringueAdmin.RaceDataConnectionResponse.newBuilder()
            .setTrackName(getTrackName(request.trackCode))
            .setTrackCode(request.trackCode)
            .setHandle(key.toString())
            .setRunning(activeMap[key]?.isActive ?: false)
            .build()
    }

    override suspend fun getTrackForCar(request: MeringueAdmin.CarLocationRequest): StringValue {
        log.info("looking for connected car $request")
        val result = connectedCarStore.findTrack(request.carNumber, request.ipAddress, request.key)
        log.info("found car at track $result")
        return StringValue.of(result?:"")
    }

    override suspend fun getCarStatus(request: CarStatusSlackRequest): MeringueAdmin.CarStatusResponse {
        log.info("fetching car status for slack token ${request.slackToken}")
        val builder = MeringueAdmin.CarStatusResponse.newBuilder()
        slackService.getCarsForSlackToken(request.slackToken)?.stream()?.forEach { trackAndCar ->
            connectedCarStore.getStatus(trackAndCar.trackCode, trackAndCar.carNumber)?.let { status ->
                builder.addStatusList(MeringueAdmin.CarStatus.newBuilder().apply {
                    this.trackCode = trackAndCar.trackCode
                    this.carNumber = trackAndCar.carNumber
                    this.online = status.isOnline
                    this.ipAddress = status.ipAddress
                })
            }
        }
        return builder.build()
    }

    override suspend fun associateCarWithSlack(request: MeringueAdmin.CarAddViaSlackRequest): MeringueAdmin.CarStatusResponse {
        log.info("looking for a car on wifi network ${request.ipAddress}")
        connectedCarStore.getConnectedCars(request.ipAddress).stream().forEach {
            slackService.createCarConnection(it.trackCode, it.carNumber, request.slackAppId, request.slackToken)
        }
        return getCarStatus(CarStatusSlackRequest.newBuilder().apply {
            this.slackToken = request.slackToken
        }.build())
    }

    override suspend fun shutdown(request: Empty): Empty {
        log.warn("shutdown requested !!!")
        SpringApplication.exit(appContext, ExitCodeGenerator { 0 })
        return Empty.getDefaultInstance()
    }

    private fun connectRaceData(trackCode: String, raceId: String, provider: MeringueAdmin.RaceDataProvider): Job {
        val raceDataSource = raceDataSourceFactoryFn(provider, raceId)
        raceDataSource.logRaceData = logRaceData.toBooleanStrict()
        val streamUrl = raceDataSource.connect()
        log.info("launching thread to run $raceId @ $trackCode")
        val newRace = RaceOrder()
        val dsHandler = when (provider) {
            MeringueAdmin.RaceDataProvider.PROVIDER_RH -> DataSource2Handler(newRace, trackCode, getConnectedCars(trackCode))
            MeringueAdmin.RaceDataProvider.PROVIDER_RM -> DataSourceHandler(newRace, trackCode, delayLapCompletedEvent.toLong(), getConnectedCars(trackCode))
            else -> throw RuntimeException("unknown race data source")
        }

        val jobId = GlobalScope.launch(newSingleThreadContext("thread-${trackCode}")) {
            raceMap[trackCode] = newRace
            raceDataSource.stream(
                streamUrl,
                dsHandler
            )
            // finished here
            log.info("removing race data as thread has finished")
            raceMap.remove(trackCode)
        }
        return jobId
    }

    internal val raceMap = mutableMapOf<String, RaceOrder>()

    fun getRaceView(trackCode: String) : RaceView? {
        return raceMap[trackCode]?.createRaceView()
    }

    override suspend fun findLiveRaces(request: MeringueAdmin.SearchTermsRequest): MeringueAdmin.LiveRaceListResponse {
        val liveRacesRM = raceLister1.getLiveRaces(request.termList.toList())
            .map {
                MeringueAdmin.LiveRace.newBuilder()
                    .setRaceId(it.raceId)
                    .setTrackName(it.trackName)
                    .setEventName(it.eventName)
                    .setProvider(MeringueAdmin.RaceDataProvider.PROVIDER_RM)
                    .build()
            }
            .collect(Collectors.toList())
        val liveRacesRH = raceLister2.getLiveRaces(request.termList.toList())
            .map {
                MeringueAdmin.LiveRace.newBuilder()
                    .setRaceId(it.raceId)
                    .setTrackName(it.trackName)
                    .setEventName(it.eventName)
                    .setProvider(MeringueAdmin.RaceDataProvider.PROVIDER_RH)
                    .build()
            }
            .collect(Collectors.toList())
        val allRaces = liveRacesRH + liveRacesRM
        val searchTerms: List<String> = request.termList.stream().map {
            it.lowercase()
        }.collect(Collectors.toList())

        return MeringueAdmin.LiveRaceListResponse.newBuilder()
            .addAllRaces(allRaces.sortedWith(FuzzyComparator(searchTerms)))
            .build()

    }

    class FuzzyComparator(private val terms: List<String>) : Comparator<MeringueAdmin.LiveRace> {

        override fun compare(o1: MeringueAdmin.LiveRace?, o2: MeringueAdmin.LiveRace?): Int {
            return countTerms(o2) - countTerms(o1)
        }

        private fun countTerms(o1: MeringueAdmin.LiveRace?): Int {
            if (o1 == null) {
                return 0
            }

            val delimiters = listOf(" ", "@", ":", "/").toTypedArray()

            return ((o1.eventName?.split(*delimiters)?: emptyList()) +
                    o1.trackName.split(*delimiters))
                .stream()
                .filter {
                            it.lowercase() in terms
                        }
                        .count().toInt()
        }

    }

    override suspend fun listLiveRaces(request: Empty): MeringueAdmin.LiveRaceListResponse {
        return findLiveRaces(MeringueAdmin.SearchTermsRequest.getDefaultInstance())
    }

    override suspend fun listConnectedCars(request: MeringueAdmin.ConnectedCarRequest) : MeringueAdmin.ConnectedCarResponse {
        when (trackMetaData.isValidTrackCode(request.trackCode)) {
            true -> return MeringueAdmin.ConnectedCarResponse.newBuilder()
                .addAllCarNumber(lemonPiService.getConnectedCarNumbers(request.trackCode))
                .build()
            else -> throw RuntimeException("invalid track code")
        }
    }

    override suspend fun resetFastLapTime(request: MeringueAdmin.ResetFastLapTimeRequest) : BoolValue {
        return BoolValue.of(lemonPiService.resetFastLapTime(request.trackCode, request.carNumber))
    }

    override suspend fun setTargetLapTime(request: MeringueAdmin.SetTargetLapTimeRequest) : BoolValue {
        return BoolValue.of(lemonPiService.setTargetLapTime(request.trackCode, request.carNumber, request.targetTimeSeconds))
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
            bldr.addResponseBuilder().apply {
                this.trackName = getTrackName(it.key.trackCode)
                this.trackCode = it.key.trackCode
                this.handle = it.key.toString()
                this.running = it.value.isActive
            }
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
            log.info("removing race data")
            raceMap.remove(handle.trackCode)
        } else {
            log.info("request ignored ... no such running race")
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun sendDriverMessage(request: MeringueAdmin.DriverMessageRequest): BoolValue {
        return BoolValue.of(lemonPiService.sendDriverMessage(request.trackCode, request.carNumber, request.message))
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