package com.normtronix.meringue.racedata

import com.normtronix.meringue.racedata.CarPosition.Companion.NOT_STARTED
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
        if (this.position == NOT_STARTED) {
            return null
        }
        return when (positionMode) {
            PositionEnum.OVERALL -> {
                carInFront
            }
            PositionEnum.IN_CLASS -> {
                var cursor: CarPosition? = carInFront
                while( cursor != null && cursor.classId != classId ) {
                    cursor = cursor.carInFront
                }
                cursor
            }
        }
    }

    /*
      produce a human-readable format of the gap. This could be in the form:
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
                return if (secondsDiff < 300) {
                    "{} L".format(lapDiff)
                } else {
                    "{} L(p)".format(lapDiff)
                }
            }
        }

        if (secondsDiff > 0) {
            return if (secondsDiff >= 60) {
                val mDiff = secondsDiff / 60
                val sDiff = secondsDiff % 60
                // todo : pad seconds
                "${mDiff}:${sDiff}"
            } else {
                "${secondsDiff}s"
            }
        }
        return "-"
    }

    companion object {
        const val NOT_STARTED = 9999
        val log: Logger = LoggerFactory.getLogger(CarPosition::class.java)
    }
}

class RaceOrder {

    private var first: CarPosition? = null
    private val numberLookup: MutableMap<String, CarPosition> = mutableMapOf()
    private val classLookup: MutableMap<String, String> = mutableMapOf()

    fun lookup(carNumber:String): CarPosition? {
        return numberLookup[carNumber]
    }

    fun getLeadCar(): CarPosition? {
        return first
    }

    fun addCar(car: CarPosition): CarPosition {
        val existing = numberLookup[car.carNumber]
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

    private fun hasMultipleClasses(): Boolean {
        return classLookup.size > 1
    }

    fun size(): Int {
        return numberLookup.size
    }

    fun updatePosition(carNumber: String, position: Int, lapCount: Int?) {
        val car = numberLookup[carNumber] ?: addCar(CarPosition(carNumber, "unknown"))
        car.let {
            car.position = position
            lapCount?.let {
                car.lapsCompleted = lapCount
            }
        }
        adjustPosition(car)
        cleanup()
        checkIntegrity()
    }

    fun updateLastLap(carNumber: String, lastLapTime: Double) {
        numberLookup[carNumber]?.let {
            it.lastLapTime = lastLapTime
        }
    }

    fun updateFastestLap(carNumber: String, fastestLapNumber: Int, fastestLapTime: Double) {
        numberLookup[carNumber]?.let {
            it.fastestLap = fastestLapNumber
            it.fastestLapTime = fastestLapTime
        }
    }

    fun updateLapTimestamp(carNumber: String, timestamp: Instant) {
        numberLookup[carNumber]?.let {
            it.lastLapTimestamp = timestamp
        }
    }

    private fun appendCar(first: CarPosition?, car: CarPosition): CarPosition {
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
            val tmpFirst = first
            if (tmpFirst != null) {
                tmpFirst.carInFront = car
                car.carBehind = tmpFirst
            }
            car.carInFront = null
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
        // and enforce stuff
        checkIntegrity()
    }

    private fun findInsertionPoint(car: CarPosition) : CarPosition? {
        var scan = car.carInFront
        var iterations = 0
        while( scan != null && (scan.lapsCompleted < car.lapsCompleted || scan.position == NOT_STARTED)) {
            scan = scan.carInFront
            iterations += 1
        }
        if (iterations > 0) {
            return scan
        }

        // let's try going backwards instead of forwards
        if (iterations == 0) {
            var prev = car
            scan = car.carBehind
            while( scan != null && scan.lapsCompleted >= car.lapsCompleted && scan.position != NOT_STARTED) {
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
        while( scan != null && scan.position != NOT_STARTED ) {
            scan.position = pos
            pos += 1
            scan = scan.carBehind
        }
        while( scan != null ) {
            if (scan.lapsCompleted == 0) {
                scan.position = NOT_STARTED
                scan = scan.carBehind
            }
        }
        if (hasMultipleClasses()) {
            for (raceClass in classLookup.keys) {
                scan = first
                pos = 1
                while( scan != null && scan.position != NOT_STARTED) {
                    if (scan.classId == raceClass) {
                        scan.classPosition = pos
                        pos += 1
                    }
                    scan = scan.carBehind
                }
            }
        }
    }

    internal fun checkIntegrity() {
        val expectedSize = this.numberLookup.size
        // scan from front to back
        var scan = this.first
        var last: CarPosition? = null
        var count = 0
        while ( scan != null && count < expectedSize) {
            if (scan.carBehind == null) {
                last = scan
            }
            scan = scan.carBehind
            count += 1
        }

        myAssert(scan == null && count == expectedSize, "backward scan broken : $scan : $count : $expectedSize")

        // scan from back to front
        count = 0
        scan = last
        while( scan != null && count < expectedSize) {
            scan = scan.carInFront
            count += 1
        }

        myAssert(scan == null && count == expectedSize, "forward scan broken : $scan : $count : $expectedSize")

        // position should be correct : any not started should be at the back
        val firstPtr = this.first
        if (firstPtr != null && firstPtr.position != NOT_STARTED) {
            myAssert(firstPtr.position == 1, "first has started but isn't first")

            var lastPosition = 0
            scan = firstPtr
            var foundNotStarted = false

            while( scan != null ) {
                if (scan.position == NOT_STARTED) {
                    foundNotStarted = true
                }
                if (foundNotStarted) {
                    myAssert( scan.position == NOT_STARTED, "found race behind NOT_STARTED ")
                } else {
                    myAssert(scan.position == lastPosition + 1, "positions not ordered")
                    lastPosition = scan.position
                    val carAhead = scan.carInFront
                    if (carAhead != null) {
                        myAssert(carAhead.lapsCompleted >= scan.lapsCompleted, "distance not ordered")
                    }
                }

                scan = scan.carBehind
            }
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(RaceOrder::class.java)

        private fun myAssert(exp: Boolean, msg: String?) {
            if (!exp) {
                throw RuntimeException(msg)
            }
        }
    }
}
