package com.normtronix.meringue.racedata

import java.time.Duration
import java.time.Instant
import java.util.stream.Collectors

/**
 * An ordering of the current field in the race
 */
class RaceOrder {

    internal val numberLookup: MutableMap<String, Car> = mutableMapOf()
    internal val classLookup: MutableMap<String, String> = mutableMapOf()

    class Car(val carNumber:String,
              val teamDriverName: String,
              val classId:String? = null) : Comparable<Car> {
        var position = 0
        var lapsCompleted = 0

        // float, seconds and parts of seconds
        var lastLapTime = 0.0
        var lastLapTimestamp: Instant? = null
        // float, seconds and int lap
        var fastestLapTime = 0.0
        var fastestLap = 0

        override fun compareTo(other: Car): Int {
            // todo : base this on nullable field rather than negativelable field
            if (this.lapsCompleted == 0 && other.lapsCompleted == 0) {
                return this.carNumber.compareTo(other.carNumber)
            }
            val lapDiff = other.lapsCompleted - this.lapsCompleted
            if (lapDiff != 0) {
                return lapDiff
            }
            if (this.lastLapTimestamp == null) {
                return -1
            }
            if (other.lastLapTimestamp == null) {
                return 1
            }
            val timestampDiff = Duration.between(other.lastLapTimestamp, this.lastLapTimestamp)
            return timestampDiff.toMillis().toInt()
        }
    }

    /**
     * Add a car to the race.
     */
    fun addCar(carNumber:String,
               teamDriverName: String,
               classId:String? = null) {
        val newCar = Car(carNumber, teamDriverName, classId)
        numberLookup[carNumber] = newCar
    }

    /**
     * Add a class to the race
     */
    fun addClass(classId: String, name: String) {
        classLookup[classId] = name
    }

    /**
     * Update the position of a car in the race
     */
    fun updatePosition(carNumber: String, position: Int, lapCount: Int, timestamp: Double) {
        numberLookup[carNumber]?.let {
            it.position = position
            it.lapsCompleted = lapCount
            it.lastLapTimestamp = Instant.ofEpochMilli((timestamp * 1000).toLong())
        }
    }

    fun updateLastLap(carNumber: String, lapTimeSeconds: Double) {
        numberLookup[carNumber]?.let {
            it.lastLapTime = lapTimeSeconds
        }
    }

    fun updateFastestLap(carNumber: String, fastestLap: Int, lapTimeSeconds: Double) {
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




}

/**
 * An immutable view of the race at any point in time.
 *
 * Allows the caller to locate a car, find it's position in class and in the race,
 * and find the cars ahead and behind
 */
class RaceView internal constructor(private val raceOrder: Map<String, CarPosition>) {

    fun lookupCar(carNumber: String) : CarPosition? {
        return raceOrder[carNumber]
    }

    companion object {
        fun build(race: RaceOrder): RaceView {
            val raceOrder = race.numberLookup.values.stream().sorted().map {
                CarPosition(it.carNumber, it.classId, it)
            }.collect(Collectors.toList())
            val classCounts = mutableMapOf<String, Int>()
            race.classLookup.keys.map { classCounts[it] = 1 }
            var prev:CarPosition? = null
            raceOrder.withIndex().forEach {
                it.value.position = it.index + 1
//                if (it.value.position != it.value.origin.position && it.value.origin.position > 0) {
//                    println("thats odd : we think ${it.value.carNumber} is in pos ${it.index + 1} they think its in ${it.value.origin.position}")
//                }
                it.value.carAhead = prev
                prev = it.value
                val carClass = it.value.classId
                if (!classCounts.isEmpty() && carClass != null) {
                    classCounts[it.value.classId]?.let { posInClass ->
                        it.value.positionInClass = posInClass
                        classCounts[carClass] = posInClass + 1
                    }
                }
            }
            return RaceView(raceOrder.map { it.carNumber to it }.toMap())
        }
    }

}

class CarPosition(val carNumber: String, val classId: String?, internal val origin: RaceOrder.Car) {

    internal var position:Int = 0
    internal var positionInClass: Int = 0
    internal var carAhead: CarPosition? = null

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
        if (carAhead.origin.lastLapTimestamp != null && this.origin.lastLapTimestamp != null) {
            secondsDiff = Duration.between(carAhead.origin.lastLapTimestamp, this.origin.lastLapTimestamp).toSeconds()
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

    fun getLapsCompleted(): Int {
        return origin.lapsCompleted
    }

    fun getLastLapTime(): Double {
        return origin.lastLapTime
    }

}

enum class PositionEnum(val value:Int) {
    OVERALL(1),
    IN_CLASS(2)
}