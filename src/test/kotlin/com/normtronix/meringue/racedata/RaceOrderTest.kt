package com.normtronix.meringue.racedata

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class RaceOrderTest {

    @Test
    fun testAddingOneCar() {
        val race = RaceOrder()
        race.addCar("181", "")
        val view = race.createRaceView()
        val car = view.lookupCar("181")
        assertNotNull(car)
        assertEquals("181", car?.carNumber)
    }

    @Test
    fun testAddingTwoCars() {
        val race = RaceOrder()
        race.addCar("10", "")
        race.addCar("55", "")
        val view = race.createRaceView()
        assertEquals("55", view.lookupCar("55")?.carNumber)
        assertEquals("10", view.lookupCar("10")?.carNumber)
        // expecting to be alphabetical numerical order initially
        assertEquals(1, view.lookupCar("10")?.position)
        assertEquals(2, view.lookupCar("55")?.position)
    }

    @Test
    fun testOrderAfterOneLap() {
        val race = RaceOrder()
        race.addCar("701", "")
        race.addCar("55", "")
        race.updatePosition("701", 1, 1, 2000.0)
        race.updatePosition("55", 2, 1, 2001.0)
        assertRaceOrder(race.createRaceView(), "701", "55")
    }

    @Test
    fun testNegativeLapPenalty() {
        val race = RaceOrder()
        race.addCar("701", "")
        race.addCar("55", "")
        race.updatePosition("701", 1, -6, 2000.0)
        race.updatePosition("55", 1, 1, 2001.0)
        assertRaceOrder(race.createRaceView(), "55", "701")
    }

    @Test
    fun testChangesInPositionAtRaceStart() {
        val race = RaceOrder()
        race.addCar("10", "")
        race.addCar("701", "")
        race.addCar("55", "")
        race.updatePosition("55", 1, -6, 2000.0)
        assertRaceOrder(race.createRaceView(), "10", "701", "55")
        race.updatePosition("701", 1, 1, 2001.0)
        race.updatePosition("10", 2, 1, 2002.0)
        assertRaceOrder(race.createRaceView(), "701", "10", "55")
    }

    @Test
    fun testPositionInClassAndCarAhead() {
        val race = RaceOrder()
        race.addClass("A", "A Class")
        race.addClass("B", "B Class")
        race.addCar("10", "", "A")
        race.addCar("701", "", "A")
        race.addCar("55", "", "B")
        val view = race.createRaceView()
        val car55 = view.lookupCar("55")
        assertEquals(1, car55?.positionInClass)
        assertEquals(2, car55?.position)
        val car701 = view.lookupCar("701")
        assertEquals(2, car701?.positionInClass)
        assertEquals(3, car701?.position)
        val car10 = view.lookupCar("10")
        assertEquals(null, car10?.carAhead)
        assertEquals(car10, car55?.carAhead)
        assertEquals(car55, car701?.carAhead)
        assertEquals(null, car10?.getCarAhead(PositionEnum.OVERALL))
        assertEquals(null, car10?.getCarAhead(PositionEnum.IN_CLASS))
        assertEquals(car10, car55?.getCarAhead(PositionEnum.OVERALL))
        assertEquals(null, car55?.getCarAhead(PositionEnum.IN_CLASS))
        assertEquals(car55, car701?.getCarAhead(PositionEnum.OVERALL))
        assertEquals(car10, car701?.getCarAhead(PositionEnum.IN_CLASS))
    }

    private fun assertRaceOrder(view: RaceView, vararg expectedCarNumbers: String) {
        for (tuple in expectedCarNumbers.withIndex()) {
            assertEquals(tuple.index + 1, view.lookupCar(tuple.value)?.position)
        }
    }
}