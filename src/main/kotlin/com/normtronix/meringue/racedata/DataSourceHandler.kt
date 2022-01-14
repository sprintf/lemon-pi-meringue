package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class DataSourceHandler(val leaderboard: RaceOrder, val trackCode: String, targetCarParam: Set<String>) : EventHandler {

    private val targetCars = targetCarParam.toMutableSet()
    private var raceFlag = ""

    init {
        Events.register(CarConnectedEvent::class.java, this,
            filter={it is CarConnectedEvent && it.trackCode == this.trackCode} )
        log.info("filtering for cars $targetCars")
    }

    suspend fun handleWebSocketMessage(rawLine: String) {
        try {
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                val bits = line.split(",")
                if (bits.size > 0) {
                    when (bits[0]) {
                        "\$COMP" -> {
                            leaderboard.addCar(CarPosition(bits[1].trim('"'),
                            bits[4].trim('"') + bits[5].trim('"'),
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
                        "\$G" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                // if we get updates saying they completed null laps then ignore it
                                bits[3].toIntOrNull()?.let {
                                    leaderboard.updatePosition(carNumber, bits[1].toInt(), it, convertToSeconds(bits[4]))
                                }
                            }
                        }
                        "\$H" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                leaderboard.updateFastestLap(carNumber, bits[3].toInt(), convertToSeconds(bits[4]))
                            }
                        }
                        "\$J" -> {
                            if (bits.size == 4) {
                                val carNumber = bits[1].trim('"')
                                leaderboard.updateLastLap(carNumber, convertToSeconds(bits[2]))
                            }
                        }
                        "\$RMHL" -> {
                            if (bits.size == 7) {
                                val carNumber = bits[1].trim('"')
                                val thisCar = leaderboard.lookup(carNumber)
                                val laps = bits[2].trim('"').toInt()
                                val position = bits[3].trim('"').toInt()
                                val lastLapTime = convertToSeconds(bits[4])
                                val flag = bits[5].trim('"', ' ')
                                leaderboard.updatePosition(carNumber, position, laps, convertToSeconds(bits[6]))
                                if (carNumber in targetCars) {
                                    val ahead = getCarAhead(thisCar)
                                    LapCompletedEvent(
                                        trackCode,
                                        carNumber,
                                        laps,
                                        position,
                                        positionInClass = thisCar?.classPosition ?: position,
                                        ahead = ahead?.carNumber,
                                        gap = thisCar?.gap(ahead) ?: "-",
                                        lastLapTime,
                                        flag,
                                    ).emit()
                                    log.info("car of interest completed lap")
                                } else {
                                    // it may be that this car is directly behind (in class or overall)
                                    val overallAhead = thisCar?.getCarInFront(PositionEnum.OVERALL)
                                    val aheadInClass = thisCar?.getCarInFront(PositionEnum.IN_CLASS)
                                    if (overallAhead != null && overallAhead.carNumber in targetCars) {
                                        val gap = thisCar.gap(overallAhead)
                                        LapCompletedEvent(
                                            trackCode,
                                            carNumber,
                                            laps,
                                            position,
                                            positionInClass = thisCar.classPosition,
                                            ahead = overallAhead.carNumber,
                                            gap = gap,
                                            lastLapTime,
                                            flag,
                                        ).emit()
                                        log.info("car following car of interest completed lap")
                                    }
                                    else if (aheadInClass != null && aheadInClass.carNumber in targetCars) {
                                        val gap = thisCar.gap(aheadInClass)
                                        LapCompletedEvent(
                                            trackCode,
                                            carNumber,
                                            laps,
                                            position,
                                            positionInClass = thisCar.classPosition,
                                            ahead = aheadInClass.carNumber,
                                            gap = gap,
                                            lastLapTime,
                                            flag,
                                        ).emit()
                                        log.info("car following car of interest completed lap")
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            log.error("exception ", e)
        }
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
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSourceHandler::class.java)
    }

}
