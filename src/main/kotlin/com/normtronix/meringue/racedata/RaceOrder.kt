package com.normtronix.meringue.racedata

import java.time.Duration
import java.time.Instant

enum class PositionEnum(val value:Int) {
    OVERALL(1),
    IN_CLASS(2)
}

class CarPosition(val carNumber:String,
                  val teamDriverName: String,
                  val classId:String? = null) {

    var position = NOT_STARTED
    var classPosition = NOT_STARTED
    var lapsCompleted = 0

    // float, seconds and parts of seconds
    var lastLapTime = 0.0
    var lastLapTimestamp: Instant? = null
    // float, seconds and int lap
    var fastestLapTime = 0.0
    var fastestLap = 0
    var carInFront: CarPosition? = null
    var carBehind: CarPosition? = null

    fun getCarInFront(positionMode: PositionEnum): CarPosition? {
        when (positionMode) {
            PositionEnum.OVERALL -> {
                return carInFront
            }
            PositionEnum.IN_CLASS -> {
                var cursor: CarPosition? = carInFront
                while( cursor?.classId != classId ) {
                    cursor = cursor?.carInFront
                }
                return cursor
            }
        }
    }

    /*
      produce a human readable format of the gap. This could be in the form:
      5 L    gap is 5 laps
      7 L(p) car is in pits
      4:05
      12s
     */
    fun gap(carAhead: CarPosition?): String {
        if (carAhead == null) {
            return "-"
        }

        var secondsDiff:Long = -1
        if (carAhead.lastLapTimestamp != null && this.lastLapTimestamp != null) {
            secondsDiff = Duration.between(this.lastLapTimestamp, carAhead.lastLapTimestamp).toSeconds()
        }

        if (carAhead.lapsCompleted >= 0 && this.lapsCompleted >= 0) {
            val lapDiff = carAhead.lapsCompleted - this.lapsCompleted
            if (lapDiff > 0) {
                // if it's been more than 5 minutes between one of these two cars
                // crossed the line, then one or both are pitted
                if (secondsDiff < 300) {
                    return "{} L".format(lapDiff)
                } else {
                    return "{} L(p)".format(lapDiff)
                }
            }
        }

        if (secondsDiff > 0) {
            if (secondsDiff >= 60) {
                val mDiff = secondsDiff / 60
                val sDiff = secondsDiff % 60
                // todo : pad seconds
                return "${mDiff}:${sDiff}"
            } else {
                return "${secondsDiff}s"
            }
        }
        return "-"
    }

    companion object {
        val NOT_STARTED = 9999
    }
}

class RaceOrder {

    private var first: CarPosition? = null
    private val numberLookup: MutableMap<String, CarPosition> = mutableMapOf()
    private val classLookup: MutableMap<String, String> = mutableMapOf()

    fun lookup(carNumber:String): CarPosition? {
        return numberLookup[carNumber]
    }

    fun getCarInPosition(position: Int): CarPosition? {
        var cursor = first
        var loop = 0
        while( cursor != null && loop < position) {
            if (cursor.position == position) {
                return cursor
            }
            cursor = cursor.carBehind
        }
        return null
    }

    fun addCar(car: CarPosition): CarPosition {
        val existing = numberLookup.get(car.carNumber)
        if (existing != null) {
            return existing
        }
        numberLookup[car.carNumber] = car
        first = appendCar(first, car)
        return car
    }

    fun addClass(classId: String, name: String) {
        classLookup[classId] = name
    }

    fun hasMultipleClasses(): Boolean {
        return classLookup.size > 1
    }

    fun size(): Int {
        return numberLookup.size
    }

    fun updatePosition(carNumber: String, position: Int, lapCount: Int?) {
        var car = numberLookup[carNumber]
        if (car == null) {
            car = addCar(CarPosition(carNumber, "unknown"))
        }
        if (lapCount != null) {
            car.lapsCompleted = lapCount
            car.position = position
        }
        adjustPosition(car)
        cleanup()
    }

    fun updateLastLap(carNumber: String, lastLapTime: Double) {
        var car = numberLookup[carNumber]
        if (car == null) {
            car = addCar(CarPosition(carNumber, "unknown"))
        }
        car.lastLapTime = lastLapTime
    }

    fun updateFastestLap(carNumber: String, fastestLapNumber: Int, fastestLapTime: Double) {
        var car = numberLookup[carNumber]
        if (car == null) {
            car = addCar(CarPosition(carNumber, "unknown"))
        }
        car.fastestLap = fastestLapNumber
        car.fastestLapTime = fastestLapTime
    }

    fun updateLapTimestamp(carNumber: String, timestamp: Instant) {
        var car = numberLookup[carNumber]
        if (car == null) {
            car = addCar(CarPosition(carNumber, "unknown"))
        }
        car.lastLapTimestamp = timestamp
    }

    private fun appendCar(first: CarPosition?, car: CarPosition): CarPosition? {
        if (first == null) {
            return car
        }
        first.carBehind = appendCar(first.carBehind, car)
        first.carBehind?.carInFront = first
        return first
    }

    // adjust the position of a car, whatever it's ultimate position is
    // there can be no cars ahead of it with a position greater or equal
    // and there can be no car behind with a position less than or equal
    private fun adjustPosition(car: CarPosition) {
        // just us
        if (car.carBehind == null && car.carInFront == null) {
            return
        }

        // scan forwards and backwards to find the insertion point
        val insertAfter = findInsertionPoint(car)

        // if we're already at the right place then we're finished
        if (insertAfter == car) {
            return
        }

        val formerInFront = car.carInFront
        val formerBehind = car.carBehind

        if (insertAfter == null) {
            // we just moved to first place
            first?.carInFront = car
            car.carBehind = first
            first = car
        } else {
            val insertPointCarBehind = insertAfter.carBehind

            car.carInFront = insertAfter
            car.carBehind = insertPointCarBehind

            if (insertPointCarBehind != null) {
                insertPointCarBehind.carInFront = car
            }
            insertAfter.carBehind = car
        }

        // where we removed ourselves from, we cross wire
        if (formerInFront != null) {
            formerInFront.carBehind = formerBehind
        }

        if (formerBehind != null) {
            formerBehind.carInFront = formerInFront
        }

        // renumber everything
        cleanup()
    }

    private fun findInsertionPoint(car: CarPosition) : CarPosition? {
        var scan = car.carInFront
        var iterations = 0
        while( scan != null && (scan.lapsCompleted < car.lapsCompleted || scan.position == CarPosition.NOT_STARTED)) {
            scan = scan.carInFront
            iterations += 1
        }
        if (iterations > 0) {
            return scan
        }

        // lets try going backwards instead of forwards
        if (iterations == 0) {
            var prev = car
            scan = car.carBehind
            while( scan != null && scan.lapsCompleted >= car.lapsCompleted && scan.position != CarPosition.NOT_STARTED) {
                prev = scan
                scan = scan.carBehind
            }
            return prev
        }
        return car
    }

    private fun cleanup() {
        var pos = 1
        var scan = first
        while( scan != null && scan.position != CarPosition.NOT_STARTED ) {
            scan.position = pos
            pos += 1
            scan = scan.carBehind
        }
        while( scan != null ) {
            if (scan.lapsCompleted == 0) {
                scan.position = CarPosition.NOT_STARTED
                scan = scan.carBehind
            }
        }
        if (hasMultipleClasses()) {
            for (raceClass in classLookup.keys) {
                scan = first
                pos = 1
                while( scan != null && scan.position != CarPosition.NOT_STARTED) {
                    if (scan.classId == raceClass) {
                        scan.classPosition = pos
                        pos += 1
                    }
                    scan = scan.carBehind
                }
            }
        }
    }
}
