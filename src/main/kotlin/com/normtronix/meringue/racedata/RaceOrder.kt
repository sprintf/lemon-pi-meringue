package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.RaceStatusEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.stream.Collectors

/**
 * An ordering of the current field in the race
 */
class RaceOrder {

    internal val numberLookup: MutableMap<String, Car> = mutableMapOf()
    internal val classLookup: MutableMap<String, String> = mutableMapOf()
    internal var raceStatus: String = ""
    internal val lock = Any()

    class Car(val carNumber:String,
              val teamDriverName: String,
              val classId:String = "") : Comparable<Car> {
        var position = 0
        var lapsCompleted = 0

        // float, seconds and parts of seconds
        var lastLapTime = 0.0
        // this is relative to the start of the race, and accurate to the millisecond
        var lastLapTimestamp = 0L
        // float, seconds and int lap
        var fastestLapTime = 0.0
        var fastestLap = 0
        // this is an epoch time and accurate to the nearest second
        var lastLapAbsTimestamp: Instant? = null
        // the number of seconds between this car and the leader when they crossed the same lap
        var gapToFront: Double = 0.0
        // the delta between this car and the leader, on this lap versus the previous one
        var gapToFrontDelta: Double = 0.0

        override fun compareTo(other: Car): Int {
            val lapDiff = other.lapsCompleted - this.lapsCompleted
            if (lapDiff != 0) {
                return lapDiff
            } else {
                if (this.lapsCompleted == 0 && other.lapsCompleted == 0) {
                    return this.carNumber.compareTo(other.carNumber)
                }
                return (this.lastLapTimestamp - other.lastLapTimestamp).toInt()
            }
        }
    }

    /**
     * Add a car to the race.
     */
    fun addCar(carNumber:String,
               teamDriverName: String,
               classId:String = "") {
        val newCar = Car(carNumber, teamDriverName, classId)
        synchronized(lock) {
            numberLookup[carNumber] = newCar
        }
    }

    /**
     * Add a class to the race
     */
    fun addClass(classId: String, name: String) {
        synchronized(lock) {
            classLookup[classId] = name
        }
    }

    /**
     * Update the position of a car in the race
     */
    fun updatePosition(carNumber: String, position: Int, lapCount: Int, timestamp: Double) {
        if (timestamp == 0.0) {
            return
        }
        numberLookup[carNumber]?.let {
            it.position = position
            it.lapsCompleted = lapCount
            // this is millis relative to start of race
            it.lastLapTimestamp = (timestamp * 1000).toLong()
            // this is now
            it.lastLapAbsTimestamp = Instant.now()

            // if this is the leader crossing the line, then update some first place lap times
            if (position == 1) {
                RaceOrderVisitor().visit(this) { car: CarPosition, pos: Int, posInClass: Int?, carClass, _: CarPosition? ->
                    if (pos == 1 || posInClass == 1) {
                        car.lastLapAbsTimestamp?.let { ts ->
                            classLeaderLapTimestamps.getOrPut(carClass, { mutableMapOf() }).apply {
                                this[car.lapsCompleted] = ts
                            }
                        }
                    }
                }
            }

            // store away this cars gap to the front
            classLeaderLapTimestamps[it.classId]?.apply {
                val originalGapToFront = it.gapToFront
                it.gapToFront = this[it.lapsCompleted]?.let { leaderLapCrossTime ->
                    leaderLapCrossTime.until(it.lastLapAbsTimestamp, ChronoUnit.MILLIS).toDouble() / 1000
                } ?: 0.0
                it.gapToFrontDelta = it.gapToFront - originalGapToFront
            }
        }
    }

    fun updateLastLap(carNumber: String, lapTimeSeconds: Double) {
        if (lapTimeSeconds == 0.0) {
            return
        }
        numberLookup[carNumber]?.let {
            it.lastLapTime = lapTimeSeconds
        }
    }

    fun updateFastestLap(carNumber: String, fastestLap: Int, lapTimeSeconds: Double) {
        if (lapTimeSeconds == 0.0) {
            return
        }
        numberLookup[carNumber]?.let {
            it.fastestLap = fastestLap
            it.fastestLapTime = lapTimeSeconds
        }
    }

    /**
     * Create a view of the race at this instant in time.
     * The view can be used for presentation purposes. It is immutable and safe to use
     * between and across coroutines
     */
    fun createRaceView() : RaceView {
        return RaceView.build(this)
    }

    suspend fun setFlagStatus(trackCode: String, flag: String) {
        if (raceStatus != flag) {
            raceStatus = flag
            RaceStatusEvent(trackCode, flag).emit()
            log.info("race status is $flag")
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(RaceOrder::class.java)
    }
}

// a map from class -> { lap number, timestamp }
internal val classLeaderLapTimestamps : MutableMap<String, MutableMap<Int, Instant>> = mutableMapOf()

data class RaceEntry(val carNumber: String, val teamDriverName: String) : Comparable<RaceEntry> {

    private fun isInt(s: String?) : Boolean {
        return when (s?.toIntOrNull()) {
            null -> false
            else -> true
        }
    }

    override fun compareTo(other: RaceEntry): Int {
        if (isInt(carNumber) && isInt(other.carNumber)) {
            return carNumber.toInt() - other.carNumber.toInt()
        }
        return carNumber.compareTo(other.carNumber)
    }

}


class RaceOrderVisitor {

    fun visit(race: RaceOrder, visitorCallback: (CarPosition, Int, Int?, String, CarPosition?) -> Unit): List<CarPosition> {
        val raceOrder = synchronized(race.lock) {
            race.numberLookup.values.stream().sorted().map {
                CarPosition(it.carNumber, it.classId, it)
            }.collect(Collectors.toList())
        }
        val classCounts = mutableMapOf<String, Int>()
        synchronized(race.lock) {
            race.classLookup.keys.map { classCounts[it] = 1 }
        }

        var prev: CarPosition? = null
        raceOrder.withIndex().forEach {
            val carClass = it.value.classId

            visitorCallback(it.value, it.index + 1, classCounts[carClass], carClass, prev)
            prev = it.value

            if (!classCounts.isEmpty()) {
                classCounts[carClass]?.let { posInClass ->
                    // it.value.positionInClass = posInClass
                    classCounts[carClass] = posInClass + 1
                }
            }
        }
        return raceOrder
    }

}

/**
 * An immutable view of the race at any point in time.
 *
 * Allows the caller to locate a car, find it's position in class and in the race,
 * and find the cars ahead and behind
 */
class RaceView internal constructor(val raceStatus: String, private val raceOrder: Map<String, CarPosition>) {

    fun lookupCar(carNumber: String) : CarPosition? {
        return raceOrder[carNumber]
    }

    fun getField() : List<RaceEntry> {
        return raceOrder.values.stream()
            .map { RaceEntry(it.carNumber, it.origin.teamDriverName) }
            .sorted()
            .collect(Collectors.toList())
    }

    override fun toString(): String {
        return "status: $raceStatus\n" +
                "field: ${raceOrder.size}\n" +
                "$raceOrder"
    }

    companion object {
        fun build(race: RaceOrder): RaceView {
            val raceOrder = RaceOrderVisitor().visit(race)  { car: CarPosition, pos: Int, posInClass: Int?, carClass, prev: CarPosition? ->
                car.position = pos
                posInClass?.let {
                    car.positionInClass = it
                }
                car.carAhead = prev
            }
            return RaceView(race.raceStatus, raceOrder.map { it.carNumber to it }.toMap())
        }
    }

}

class CarPosition(val carNumber: String, val classId: String, internal val origin: RaceOrder.Car) {

    internal var position:Int = 0
    internal var positionInClass: Int = 0
    internal var carAhead: CarPosition? = null
    // we copy these over from the origin to prevent us picking up changing data
    internal val lapsCompleted = origin.lapsCompleted
    internal val lastLapTime = origin.lastLapTime
    internal var fastestLap = origin.fastestLap
    internal var fastestLapTime = origin.fastestLapTime
    internal var lastLapAbsTimestamp = origin.lastLapAbsTimestamp
    internal var gapToFront = origin.gapToFront
    internal var gapToFrontDelta = origin.gapToFrontDelta

    /**
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
        if (carAhead.origin.lastLapTimestamp > 0 && this.origin.lastLapTimestamp > 0) {
            secondsDiff = (this.origin.lastLapTimestamp - carAhead.origin.lastLapTimestamp) / 1000
        }

        if (carAhead.origin.lapsCompleted >= 0 && this.origin.lapsCompleted >= 0) {
            val lapDiff = carAhead.origin.lapsCompleted - this.origin.lapsCompleted
            if (lapDiff > 0) {
                // if it's been more than 5 minutes between one of these two cars
                // crossed the line, then one or both are pitted
                return if (secondsDiff < 300) {
                    "$lapDiff L"
                } else {
                    "$lapDiff L(p)"
                }
            }
        }

        if (secondsDiff > 0) {
            return if (secondsDiff >= 60) {
                val mDiff = secondsDiff / 60
                val sDiff = secondsDiff % 60
                "${mDiff}:%02d".format(sDiff)
            } else {
                "${secondsDiff}s"
            }
        }
        return "-"

    }

    fun getCarAhead(positionMode: PositionEnum) : CarPosition? {
        return when (positionMode) {
            PositionEnum.OVERALL -> {
                carAhead
            }
            PositionEnum.IN_CLASS -> {
                var cursor: CarPosition? = carAhead
                while( cursor != null && cursor.classId != classId ) {
                    cursor = cursor.carAhead
                }
                cursor
            }
        }
    }

}

enum class PositionEnum(val value:Int) {
    OVERALL(1),
    IN_CLASS(2)
}

