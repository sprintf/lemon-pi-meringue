package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.CarConnectedEvent
import com.normtronix.meringue.event.Events
import com.normtronix.meringue.event.RaceDisconnectEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class DataSource2Handler(private val leaderboard: RaceOrder,
                         trackCode: String,
                         targetCarParam: Set<String>) : BaseDataSourceHandler(leaderboard, trackCode, targetCarParam) {

    init {
        Events.register(
            CarConnectedEvent::class.java, this,
            filter={it is CarConnectedEvent && it.trackCode == this.trackCode} )
        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == this.trackCode})
        DataSourceHandler.log.info("filtering for cars $targetCars")
    }

    suspend fun handlePayload(payload: Payload) {
        val sessionMap: MutableMap<Int, RacerSessionRH> = mutableMapOf()
        payload.sessions.forEach {
            sessionMap[it.racerSessionId] = it
        }
        handlePassings(payload.passings, sessionMap.toMap())
        leaderboard.setFlagStatus(trackCode, convertFlagStatus(payload.latestFlag))
    }

    private fun convertFlagStatus(flag: FlagRH?): String {
        return when (flag?.color?.lowercase()) {
            "green" -> "green"
            "green flag" -> "green"
            "red" -> "red"
            "red flag" -> "red"
            "yellow" -> "yellow"
            "yellow flag" -> "yellow"
            "black" -> "black"
            "black flag" -> "black"
            else -> "unknown"
        }
    }

    suspend fun handlePassings(passings: List<PassingRH>, sessionMap: Map<Int, RacerSessionRH>) {
        passings.forEach {
            log.debug("processing $it")
            handlePassing(it, sessionMap)
        }
    }

    suspend fun handlePassing(passing: PassingRH, sessionMap: Map<Int, RacerSessionRH>) {
        if (sessionMap.containsKey(passing.racerSessionId)) {
            sessionMap[passing.racerSessionId]?.let {
                if (!leaderboard.numberLookup.containsKey(it.number)) {
                    leaderboard.addCar(it.number, it.name, it.racerClass)
                }
                leaderboard.updatePosition(it.number, passing.positionOverall, passing.latestLapNumber, passing.timestamp)
                leaderboard.updateFastestLap(it.number, passing.bestLapNumber, passing.bestLapTimeSeconds)
                leaderboard.updateLastLap(it.number, passing.lastLapTimeSeconds)
                val view = leaderboard.createRaceView()
                view.lookupCar(it.number)?.let {
                    constructLapCompleteEvent(view, it)
                }
            }
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource2Handler::class.java)
    }

}