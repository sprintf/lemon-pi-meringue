package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.format.DateTimeParseException

class DataSourceHandler(private val leaderboard: RaceOrder,
                        trackCode: String,
                        val delayLapCompletedEvent: Long,
                        targetCarParam: Set<String>) : BaseDataSourceHandler(leaderboard, trackCode, targetCarParam) {


    init {
        Events.register(CarConnectedEvent::class.java, this,
            filter={it is CarConnectedEvent && it.trackCode == this.trackCode} )
        Events.register(RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == this.trackCode})
        log.info("filtering for cars $targetCars")
    }

    suspend fun handleWebSocketMessage(rawLine: String) {
        try {
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                log.debug("rcv >> $line")
                val bits = line.split(",")
                if (bits.isNotEmpty()) {
                    when (bits[0]) {
                        "\$COMP" -> {
                            val teamName = when (bits.size) {
                                4 -> { "" }
                                5 -> { bits[4].trim('"') }
                                in 6..20 -> { bits[4].trim('"') + " " +  bits[5].trim('"') }
                                else -> { "unknown "}
                            }
                            leaderboard.addCar(bits[1].trim('"'),
                                teamName,
                                classId = bits[3])
                        }
                        "\$C" -> {
                            leaderboard.addClass(bits[1], bits[2].trim('"'))
                        }
                        "\$F" -> {
                            if (bits.size == 6) {
                                val newFlag = bits[5].trim('"',' ')
                                leaderboard.setFlagStatus(trackCode, newFlag)
                            }
                        }
                        // note : these appear to arrive in the order J, G, H
                        "\$G" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                // if we get updates saying they completed null laps then ignore it
                                bits[3].toIntOrNull()?.let {
                                    leaderboard.updatePosition(carNumber, bits[1].toInt(), it, convertToSeconds(bits[4]))
                                    coroutineScope {
                                        launch {
                                            delay(delayLapCompletedEvent)
                                            val view = leaderboard.createRaceView()
                                            view.lookupCar(carNumber)?.let {
                                                constructLapCompleteEvent(view, it)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "\$H" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                leaderboard.updateFastestLap(carNumber, bits[3].trim('"').toInt(), convertToSeconds(bits[4]))
                            }
                        }
                        "\$J" -> {
                            if (bits.size == 4) {
                                val carNumber = bits[1].trim('"')
                                leaderboard.updateLastLap(carNumber, convertToSeconds(bits[2]))
                            }
                        }
                        // we choose to ignore RMHL/RMLT messages as we do not receive them for some
                        // reason when we run in GCP ... still very confused as to why.
                        // we do get RMLT at start of race but not during it
                        else -> {}
                    }
                }
            }
        } catch (e1: DateTimeParseException) {
            log.info("got unexpected date/time : $e1")
        } catch (e: Exception) {
            log.error("exception ", e)
        }
    }



    internal fun convertToSeconds(rawTime: String): Double {
        try {
            val timeFields = rawTime.trim('"').split(':')
            val hours = timeFields[0].toInt()
            val minutes = timeFields[1].toInt()
            val seconds = when ("." in timeFields[2]) {
                true -> {
                    val fields = timeFields[2].split(".")
                    fields[0].toInt() + fields[1].toInt() / 1000.0
                }
                false -> timeFields[2].toDouble()
            }
            return hours * 3600.0 +
                    minutes * 60.0 +
                    seconds
        } catch (e: IndexOutOfBoundsException) {
            throw DateTimeParseException(e.message, rawTime, 0)
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSourceHandler::class.java)
    }

}
