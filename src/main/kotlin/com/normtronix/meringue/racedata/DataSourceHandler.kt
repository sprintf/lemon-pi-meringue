package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.LapCompletedEvent
import com.normtronix.meringue.event.RaceStatusEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// todo : need to be able to update the target cars dynamocally : can do this via events
// todo : need to know the trackId / code associated with this race
class DataSourceHandler(val leaderboard: RaceOrder, val targetCars: List<String>) {

    var raceFlag = ""

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
                                val newFlag = bits[5].trim('"')
                                if (raceFlag != newFlag) {
                                    raceFlag = newFlag
                                    RaceStatusEvent(newFlag).emit()
                                    log.info("race status is $raceFlag")
                                }
                            }
                        }
                        "\$G" -> {
                            if (bits.size == 5) {
                                val carNumber = bits[2].trim('"')
                                val lapsCompleted = bits[3].toIntOrNull()
                                leaderboard.updatePosition(carNumber, bits[1].toInt(), lapsCompleted)
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
                        "\$RMLT" -> {
                            if (bits.size == 3) {
                                val carNumber = bits[1].trim('"')
                                leaderboard.updateLapTimestamp(carNumber, Instant.ofEpochMilli(bits[2].toLong()))
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
                                leaderboard.updatePosition(carNumber, position, laps)
                                if (carNumber in targetCars) {
                                    val ahead = getCarAhead(thisCar)
                                    LapCompletedEvent(
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

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSourceHandler::class.java)
    }
}

/*

    def __init__(self, leaderboard: RaceOrder, target_cars: [str]):
        self.leaderboard = leaderboard
        self.race_flag = ""
        self.target_cars: [str] = target_cars
        self.chase_cars: dict = {}
        CarSettingsEvent.register_handler(self)

    def handle_event(self, event, car="", chase_mode=False, target_car=""):
        if event == CarSettingsEvent:
            if chase_mode:
                self.chase_cars[car] = target_car
            else:
                if car in self.chase_cars:
                    del self.chase_cars[car]

    def handle_message(self, raw_line):
        try:
            line = raw_line.strip()
            if len(line) > 0:
                bits = line.split(",")
                if len(bits) > 0:
                    if bits[0] == "$COMP":
                        self.leaderboard.add_car(CarPosition(bits[1].strip('"'),
                                                             bits[4].strip('"') + bits[5].strip('"'),
                                                             class_id=int(bits[3])))
                    if bits[0] == "$C":
                        self.leaderboard.add_class(int(bits[1]), bits[2].strip('"'))
                    if bits[0] == "$F" and len(bits) == 6:
                        # print(bits)
                        if self.race_flag != bits[5].strip('" '):
                            self.race_flag = bits[5].strip('" ')
                            RaceStatusEvent.emit(flag=self.race_flag)
                    if bits[0] == "$G" and len(bits) == 5:
                        # "$G" indicates a cars position and number of laps completed
                        # print(bits)
                        # $G,14,"128",24,"00:59:45.851"
                        car_number = bits[2].strip('"')
                        laps_completed = None
                        if bits[3].isnumeric():
                            laps_completed = int(bits[3])
                        self.leaderboard.update_position(car_number, int(bits[1]), laps_completed)
                    if bits[0] == "$H" and len(bits) == 5:
                        # print(bits)
                        car_number = bits[2].strip('"')
                        self.leaderboard.update_fastest_lap(car_number,
                                                            int(bits[3]),
                                                            self._convert_to_s(bits[4].strip('"')))
                    if bits[0] == "$J" and len(bits) == 4:
                        # print(bits)
                        car_number = bits[1].strip('"')
                        self.leaderboard.update_last_lap(car_number, self._convert_to_s(bits[2].strip('"')))
                    if bits[0] == "$RMLT" and len(bits) == 3:
                        # print(bits)
                        car_number = bits[1].strip('"')
                        self.leaderboard.update_lap_timestamp(car_number, int(bits[2]))
                    if bits[0] == "$RMHL" and len(bits) == 7:
                        car_number = bits[1].strip('"')
                        laps = int(bits[2].strip('"'))
                        position = int(bits[3].strip('"'))
                        last_lap_time = self._convert_to_s(bits[4].strip('"'))
                        flag = bits[5].strip('" ')
                        self.leaderboard.update_position(car_number, position, laps)
                        if car_number in self.target_cars:
                            target = self.leaderboard.number_lookup.get(car_number)
                            # if we are chasing a particular car then look it up instead
                            # of the car directly ahead
                            car_ahead = None
                            if car_number in self.chase_cars:
                                car_ahead = self.leaderboard.number_lookup.get(self.chase_cars[car_number])
                            if not car_ahead:
                                car_ahead = target.car_in_front
                            gap = target.gap(car_ahead)
                            self.emit_lap_completed(car_number, laps, position,
                                                    target.class_position, car_ahead,
                                                    gap, last_lap_time, flag)
                        else:
                            this_car = self.leaderboard.number_lookup.get(car_number)
                            if not this_car:
                                return
                            ahead = this_car.car_in_front
                            if ahead and ahead.car_number in self.target_cars:
                                gap = this_car.gap(ahead)
                                self.emit_lap_completed(car_number, laps, position,
                                                        this_car.class_position, ahead,
                                                        gap, last_lap_time, flag)
        except Exception as e:
            logger.exception("issue parsing '{}'".format(raw_line))

    @classmethod
    def emit_lap_completed(cls, car_number, laps, position, class_position, ahead, gap, last_lap_time, flag):
        if ahead:
            LapCompletedEvent.emit(car=car_number,
                                   laps=laps,
                                   position=position,
                                   class_position=class_position,
                                   ahead=ahead.car_number,
                                   gap=gap,
                                   last_lap_time=last_lap_time,
                                   flag=flag)
        else:
            LapCompletedEvent.emit(car=car_number,
                                   laps=laps,
                                   position=position,
                                   ahead=None,
                                   gap="-",
                                   last_lap_time=last_lap_time,
                                   flag=flag)

    @classmethod
    def _convert_to_s(cls, string_time) -> float:
        if "." in string_time:
            format_string = "%H:%M:%S.%f"
        else:
            format_string = "%H:%M:%S"
        date_time = datetime.strptime(string_time, format_string)
        td = date_time - datetime(1900, 1, 1)
        return td.total_seconds()

 */