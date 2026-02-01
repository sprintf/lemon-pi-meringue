package com.normtronix.meringue.racedata

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.normtronix.meringue.AdminService
import com.normtronix.meringue.MeringueAdmin
import com.normtronix.meringue.event.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.stream.Collectors


@Component
class RaceSchedule : InitializingBean, EventHandler {

    @Autowired
    lateinit var raceLister1: DS1RaceLister

    @OptIn(ExperimentalCoroutinesApi::class)
    @Autowired
    lateinit var adminService : AdminService

    data class RaceSeries(
        val series: List<ScheduledRace>
    )

    data class ScheduledRace(
        @SerializedName("start-date")
        val startDate: Date,
        @SerializedName("end-date")
        val endDate: Date,
        val name: String,
        val track: String,
        val location: String,
        val trackCode: String
    )

    val scheduledRaces = loadSchedule()

    override fun afterPropertiesSet() {
        Events.register(CarConnectedEvent::class.java, this)
    }

    fun findRaceTitle(targetDate: Date, trackCode: String): String? {
        val race = scheduledRaces.series
            .filter { it.trackCode == trackCode &&
                    targetDate.compareTo(endOfDay(it.endDate)) <= 0 &&
                    targetDate.compareTo(practiceDay(it.startDate)) >= 0
                    }
            .firstOrNull()
        return race?.let {
            return "24 hours of lemons " + it.name + " " + it.track
        }
    }

    private fun practiceDay(firstDay: Date) : Date =
        Date.from(firstDay.toInstant().minus(1, ChronoUnit.DAYS))

    private fun endOfDay(raceDay: Date) : Date =
        Date.from(raceDay.toInstant().plus(24, ChronoUnit.HOURS))

    private fun loadSchedule(): RaceSeries {
        log.info("loading race schedule from race-schedule.json")
        val resourceReader = object {}.javaClass.getResourceAsStream("/race-schedule.json")?.bufferedReader()
        val result = GsonBuilder().apply {
            this.setDateFormat("MM/dd/yyyy")
        }.create().fromJson(resourceReader, RaceSeries::class.java)
        log.info("loaded ${result.series.size} races in series")
        return result
    }

    override suspend fun handleEvent(e: Event) {
        if (e is CarConnectedEvent) {
            log.debug("got Car Connected event")
            // see if there is a race at this location now
            findRaceTitle(Date(), e.trackCode)?.apply {
                // if it is not connected then try to connect it up
                val selectedRace =
                    raceLister1.getLiveRaces(emptyList())
                        .filter {
                            score(this, it) > 50
                        }
                        .collect(Collectors.toList())
                if (selectedRace.size == 1) {
                    log.info("selected race at ${e.trackCode} from RaceMonitor")
                    connectToRace(e.trackCode, selectedRace[0], MeringueAdmin.RaceDataProvider.PROVIDER_RM)
                }
            } ?: log.info("no live races found at ${e.trackCode}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun connectToRace(
        trackCode: String,
        selectedRace: RaceDataIndexItem,
        raceDataProvider: MeringueAdmin.RaceDataProvider
    ) {
        if (!adminService.isRaceConnected(trackCode, selectedRace.raceId)) {
            log.info("Race is not connected ... sending race connect request")
            val connectRequest = MeringueAdmin.ConnectToRaceDataRequest.newBuilder().apply {
                this.trackCode = trackCode
                this.provider = raceDataProvider
                this.providerId = selectedRace.raceId
            }.build()
            adminService.connectToRaceData(connectRequest)
        }
    }

    companion object {

        fun score(target: String, o1: RaceDataIndexItem): Int {
            return FuzzySearch.tokenSetRatio(target, o1.eventName + "@" + o1.trackName)
        }

        val log: Logger = LoggerFactory.getLogger(RaceSchedule::class.java)

    }

}