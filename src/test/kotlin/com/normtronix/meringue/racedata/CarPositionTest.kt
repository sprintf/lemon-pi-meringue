package com.normtronix.meringue.racedata

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class CarPositionTest{

    @Test
    fun testCreatingCars() {
        val car1 = CarPosition("181", "", "A")
        val car2 = CarPosition("183", "", "A")
        car2.carInFront = car1
        car1.carInFront = null
        assertEquals(car1, car2.getCarInFront(PositionEnum.IN_CLASS))
        assertEquals(car1, car2.getCarInFront(PositionEnum.OVERALL))
        assertEquals(null, car1.getCarInFront(PositionEnum.IN_CLASS))
        assertEquals(null, car1.getCarInFront(PositionEnum.OVERALL))
    }
}