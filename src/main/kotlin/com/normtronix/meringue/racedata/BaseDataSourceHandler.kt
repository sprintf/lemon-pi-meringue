package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.*
import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory

open class BaseDataSourceHandler(private val leaderboard: RaceOrder,
                            val trackCode: String,
                            targetCarParam: Set<String>) : EventHandler {

    protected val targetCars = targetCarParam.toMutableSet()

    protected suspend fun constructLapCompleteEvent(
        view: RaceView,
        thisCar: CarPosition,
    ) {
        val carNumber = thisCar.carNumber

        if (thisCar.position == 1) {
            DataSourceHandler.log.info("lead car $carNumber is starting lap ${thisCar.lapsCompleted + 1}")
        }

        if (targetCars.contains(carNumber)) {
            val ahead = getCarAhead(thisCar)
            emitLapCompleted(thisCar, ahead, view.raceStatus)
            DataSourceHandler.log.info("car of interest completed lap")
        } else {
            // it may be that this car is directly behind (in class or overall)
            val overallAhead = thisCar.getCarAhead(PositionEnum.OVERALL)
            val aheadInClass = thisCar.getCarAhead(PositionEnum.IN_CLASS)
            if (overallAhead != null && targetCars.contains(overallAhead.carNumber)) {
                emitLapCompleted(thisCar, overallAhead, view.raceStatus)
                DataSourceHandler.log.info("car following car of interest completed lap")
            } else if (aheadInClass != null && targetCars.contains(aheadInClass.carNumber)) {
                emitLapCompleted(thisCar, aheadInClass, view.raceStatus)
                DataSourceHandler.log.info("car following car of interest completed lap")
            }
        }
    }

    protected suspend fun emitLapCompleted(
        thisCar: CarPosition,
        ahead: CarPosition?,
        raceFlag: String
    ) {
        LapCompletedEvent(
            trackCode,
            thisCar.carNumber,
            thisCar.lapsCompleted,
            thisCar.position,
            positionInClass = thisCar.positionInClass,
            ahead = ahead?.carNumber,
            gap = thisCar.gap(ahead),
            gapToFront = thisCar.gapToFront,
            gapToFrontDelta = thisCar.gapToFrontDelta,
            thisCar.lastLapTime,
            raceFlag,
        ).emit()
    }

    internal fun getCarAhead(thisCar: CarPosition?) : CarPosition? {
        val directlyAhead = thisCar?.getCarAhead(PositionEnum.OVERALL)
        val aheadInClass = thisCar?.getCarAhead(PositionEnum.IN_CLASS)
        //
        if (aheadInClass != null && thisCar.positionInClass <= 5) {
            return aheadInClass
        }
        return directlyAhead
    }

    override suspend fun handleEvent(e: Event) {
        if (e is CarConnectedEvent) {
            if (targetCars.add(e.carNumber)) {
                log.info("registering car ${e.carNumber} filtering for cars $targetCars")
                val view = leaderboard.createRaceView()
                val thisCar = view.lookupCar(e.carNumber)
                thisCar?.let {
                    // wait a moment so the connection is there
                    delay(1000)
                    LapCompletedEvent(
                        trackCode,
                        thisCar.carNumber,
                        thisCar.lapsCompleted,
                        thisCar.position,
                        positionInClass = thisCar.positionInClass,
                        ahead = thisCar.carAhead?.carNumber,
                        gap = "-",
                        gapToFront = thisCar.gapToFront,
                        gapToFrontDelta = thisCar.gapToFrontDelta,
                        thisCar.lastLapTime,
                        view.raceStatus,
                    ).emit()
                }
            }
        } else if (e is RaceDisconnectEvent) {
            Events.unregister(this)
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(BaseDataSourceHandler::class.java)
    }

}