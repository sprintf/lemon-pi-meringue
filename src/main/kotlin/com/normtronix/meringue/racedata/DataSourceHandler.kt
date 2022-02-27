package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.*
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class DataSourceHandler(val leaderboard: RaceOrder, val trackCode: String, targetCarParam: Set<String>) : EventHandler {

    private val targetCars = targetCarParam.toMutableSet()
    private var raceFlag = ""

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
                log.info("rcv >> $line")
                val bits = line.split(",")
                if (bits.size > 0) {
                    when (bits[0]) {
                        "\$COMP" -> {
                            val teamName = when (bits.size) {
                                4 -> { "" }
                                5 -> { bits[4].trim('"') }
                                in 6..20 -> { bits[4].trim('"') + bits[5].trim('"') }
                                else -> { "unknown "}
                            }
                            leaderboard.addCar(CarPosition(bits[1].trim('"'),
                                teamName,
                                classId = bits[3]))
                        }
                        "\$C" -> {
                            leaderboard.addClass(bits[1], bits[2].trim('"'))
                        }
                        "\$F" -> {
                            if (bits.size == 6) {
                                val newFlag = bits[5].trim('"',' ')
                                if (raceFlag != newFlag) {
                                    raceFlag = newFlag
                                    RaceStatusEvent(trackCode, newFlag).emit()
                                    log.info("race status is $raceFlag")
                                }
                            }
                        }
                        // note : these appear to arrive in the order J, G, H
                        "\$G" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                // if we get updates saying they completed null laps then ignore it
                                bits[3].toIntOrNull()?.let {
                                    leaderboard.updatePosition(carNumber, bits[1].toInt(), it, convertToSeconds(bits[4]))
                                    val thisCar = leaderboard.lookup(carNumber)
                                    if (thisCar != null) {
                                        constructLapCompleteEvent(thisCar)
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
                        // we choose to ignore RMHL messages as we do not receive them for some
                        // reason when we run in GCP
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

    private suspend fun constructLapCompleteEvent(
        thisCar: CarPosition,
    ) {
        val carNumber = thisCar.carNumber

        if (thisCar.position == 1) {
            log.info("lead car ${carNumber} is starting lap ${thisCar.lapsCompleted + 1}")
        }

        if (targetCars.contains(carNumber)) {
            val ahead = getCarAhead(thisCar)
            emitLapCompleted(thisCar, ahead)
            log.info("car of interest completed lap")
        } else {
            // it may be that this car is directly behind (in class or overall)
            val overallAhead = thisCar.getCarInFront(PositionEnum.OVERALL)
            val aheadInClass = thisCar.getCarInFront(PositionEnum.IN_CLASS)
            if (overallAhead != null && targetCars.contains(overallAhead.carNumber)) {
                emitLapCompleted(thisCar, overallAhead)
                log.info("car following car of interest completed lap")
            } else if (aheadInClass != null && targetCars.contains(aheadInClass.carNumber)) {
                emitLapCompleted(thisCar, aheadInClass)
                log.info("car following car of interest completed lap")
            }
        }
    }

    private fun emitLapCompleted(
        thisCar: CarPosition,
        ahead: CarPosition?
    ) {
        LapCompletedEvent(
            trackCode,
            thisCar.carNumber,
            thisCar.lapsCompleted,
            thisCar.position,
            positionInClass = thisCar.classPosition,
            ahead = ahead?.carNumber,
            gap = thisCar.gap(ahead),
            thisCar.lastLapTime,
            raceFlag,
        ).emitAsync()
    }

    internal fun getCarAhead(thisCar: CarPosition?) : CarPosition? {
        val directlyAhead = thisCar?.getCarInFront(PositionEnum.OVERALL)
        val aheadInClass = thisCar?.getCarInFront(PositionEnum.IN_CLASS)
        //
        if (aheadInClass != null && thisCar.classPosition <= 5) {
            return aheadInClass
        }
        return directlyAhead
    }

    internal fun convertToSeconds(rawTime: String): Double {
        val time = rawTime.trim('"')
        val formatString = if (time.contains(".")) {
            "HH:mm:ss.SSS"
        } else {
            "HH:mm:ss"
        }
        val f = DateTimeFormatter.ofPattern(formatString)
        val timeAmount = LocalTime.parse(time, f)
        return timeAmount.hour * 3600.0 +
                timeAmount.minute * 60.0 +
                timeAmount.second +
                (timeAmount.nano / 1000000000.0)
    }

    override suspend fun handleEvent(e: Event) {
        if (e is CarConnectedEvent) {
            targetCars.add(e.carNumber)
            log.info("registering car ${e.carNumber} filtering for cars $targetCars")
            val thisCar = leaderboard.lookup(e.carNumber)
            thisCar?.let {
                // wait a moment so the connection is there
                delay(1000)
                LapCompletedEvent(
                    trackCode,
                    thisCar.carNumber,
                    thisCar.lapsCompleted,
                    thisCar.position,
                    positionInClass = thisCar.classPosition,
                    ahead = thisCar.carInFront?.carNumber,
                    gap = "-",
                    thisCar.lastLapTime,
                    raceFlag,
                ).emit()
            }
        } else if (e is RaceDisconnectEvent) {
            Events.unregister(this)
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSourceHandler::class.java)
    }

}
