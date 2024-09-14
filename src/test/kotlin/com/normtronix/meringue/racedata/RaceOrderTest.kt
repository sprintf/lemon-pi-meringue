package com.normtronix.meringue.racedata

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.stream.Collectors

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
        race.updatePosition("701", 1, 1, 2.0)
        race.updatePosition("55", 2, 1, 2.1)
        assertRaceOrder(race.createRaceView(), "701", "55")
    }

    @Test
    fun testNegativeLapPenalty() {
        val race = RaceOrder()
        race.addCar("701", "")
        race.addCar("55", "")
        race.updatePosition("701", 1, -6, 2.0)
        race.updatePosition("55", 1, 1, 2.1)
        assertRaceOrder(race.createRaceView(), "55", "701")
    }

    @Test
    fun testChangesInPositionAtRaceStart() {
        val race = RaceOrder()
        race.addCar("10", "")
        race.addCar("701", "")
        race.addCar("55", "")
        race.updatePosition("55", 1, -6, 2.0)
        assertRaceOrder(race.createRaceView(), "10", "701", "55")
        race.updatePosition("701", 1, 1, 2.1)
        race.updatePosition("10", 2, 1, 2.2)
        assertRaceOrder(race.createRaceView(), "701", "10", "55")
    }

    @Test
    fun testGap() {
        val race = RaceOrder()
        race.addCar("10", "")
        race.addCar("701", "")
        var view = race.createRaceView()
        assertEquals("-", view.lookupCar("701")?.gap(view.lookupCar("10")))
        race.updatePosition("701", 1, 5, 2.0)
        race.updatePosition("10", 2, 5, 4.0)
        view = race.createRaceView()
        assertEquals("2s", view.lookupCar("10")?.gap(view.lookupCar("701")))
    }

    @Test
    fun testGapToFrontNoClass() {
        val race = RaceOrder()
        race.addCar("10", "")
        race.addCar("701", "")
        race.addCar("181", "")
        var view = race.createRaceView()
        assertEquals(0.0, view.lookupCar("701")?.gapToFront)
        assertEquals(0.0, view.lookupCar("10")?.gapToFront)
        assertEquals(0.0, view.lookupCar("181")?.gapToFront)
        race.updatePosition("701", 1, 4, 2.0)
        Thread.sleep(100)
        race.updatePosition("10", 2, 4, 4.0)
        Thread.sleep(100)
        race.createRaceView()
        race.updatePosition("701", 1, 5, 6.0)
        Thread.sleep(100)
        race.updatePosition("10", 2, 5, 8.0)
        Thread.sleep(100)
        race.updatePosition("181", 3, 4, 10.0)
        view = race.createRaceView()
        assertEquals(0.1, view.lookupCar("10")!!.gapToFront, 0.05)
        assertEquals(0.4, view.lookupCar("181")!!.gapToFront, 0.05)
        assertEquals(0.0, view.lookupCar("701")!!.gapToFront)
        Thread.sleep(100)
        race.updatePosition("181", 3, 5, 12.0)
        view = race.createRaceView()
        assertEquals(0.3, view.lookupCar("181")!!.gapToFront, 0.05)
        assertEquals(-0.1, view.lookupCar("181")!!.gapToFrontDelta, 0.05)
    }

    @Test
    fun testGapToFrontWithClass() {
        val race = RaceOrder()
        race.addClass("A", "A")
        race.addClass("B", "B")
        race.addCar("10", "", classId = "A")
        race.addCar("701", "", classId = "B")
        race.addCar("181", "", classId = "B")
        var view = race.createRaceView()
        assertEquals(0.0, view.lookupCar("701")?.gapToFront)
        assertEquals(0.0, view.lookupCar("10")?.gapToFront)
        assertEquals(0.0, view.lookupCar("181")?.gapToFront)
        race.updatePosition("701", 1, 4, 2.0)
        Thread.sleep(100)
        race.updatePosition("10", 2, 4, 4.0)
        Thread.sleep(100)
        race.createRaceView()
        race.updatePosition("701", 1, 5, 6.0)
        Thread.sleep(100)
        race.updatePosition("10", 2, 5, 8.0)
        Thread.sleep(100)
        race.updatePosition("181", 3, 4, 10.0)
        view = race.createRaceView()
        assertEquals(0.0, view.lookupCar("10")!!.gapToFront)
        assertEquals(0.4, view.lookupCar("181")!!.gapToFront, 0.05)
        assertEquals(0.0, view.lookupCar("701")!!.gapToFront)
        Thread.sleep(100)
        race.updatePosition("181", 3, 5, 12.0)
        view = race.createRaceView()
        assertEquals(0.3, view.lookupCar("181")!!.gapToFront, 0.05)
        assertEquals(-0.1, view.lookupCar("181")!!.gapToFrontDelta, 0.05)
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

    @Test
    fun testGapToFrontDelta() {
        val race = RaceOrder()
        race.addClass("A", "A Class")
        race.addCar("10", "", "A")
        race.addCar("701", "", "A")
        race.updatePosition("701", 1, 1, 0.1)
        Thread.sleep(100)
        race.updatePosition("10", 2, 1, 4.1)
        assertEquals(0.1, race.numberLookup["10"]!!.gapToFrontDelta, 0.05)
        race.updatePosition("701", 1, 2, 1000.0)
        race.updatePosition("10", 2, 2, 1001.0)
        assertEquals(-0.1, race.numberLookup["10"]!!.gapToFrontDelta, 0.05)
    }

    @Test
    fun testOrdering1() {
        val cars = listOf(
            RaceOrder.Car("244", "volv", "B").apply {
                this.lapsCompleted = 0
            },
            RaceOrder.Car("181", "pp", "A").apply {
                this.lapsCompleted = 0
            },
            RaceOrder.Car("95", "wnker", "A").apply {
                this.lapsCompleted = 0
            }
        )
        val result = cars.stream().sorted().collect(Collectors.toList())
        assertEquals("181", result[0].carNumber)
    }

    @Test
    fun testOrdering2() {
        val cars = listOf(
            RaceOrder.Car("15", "arg", "B").apply {
                this.lapsCompleted = 12
                this.lastLapTimestamp = 5502
            },
            RaceOrder.Car("181", "pp", "A").apply {
                this.lapsCompleted = 12
                this.lastLapTimestamp = 5500
            },
            RaceOrder.Car("95", "wnker", "A").apply {
                this.lapsCompleted = 12
                this.lastLapTimestamp = 5501
            }
        )
        val result = cars.stream().sorted().collect(Collectors.toList())
        assertEquals("181", result[0].carNumber)
    }

    @Test
    fun testOrdering3() {
        val cars = listOf(
            RaceOrder.Car("15", "arg", "B").apply {
                this.lapsCompleted = 10
            },
            RaceOrder.Car("181", "pp", "A").apply {
                this.lapsCompleted = 12
            },
            RaceOrder.Car("95", "wnker", "A").apply {
                this.lapsCompleted = 11
            }
        )
        val result = cars.stream().sorted().collect(Collectors.toList())
        assertEquals("181", result[0].carNumber)
    }

    @Test
    fun testOrdering4() {
        val cars = listOf(
            RaceOrder.Car("15", "arg", "B").apply {
                this.lapsCompleted = -1
            },
            RaceOrder.Car("181", "pp", "A").apply {
                this.lapsCompleted = 0
            },
            RaceOrder.Car("95", "wnker", "A").apply {
                this.lapsCompleted = -11
            }
        )
        val result = cars.stream().sorted().collect(Collectors.toList())
        assertEquals("181", result[0].carNumber)
    }

    private fun assertRaceOrder(view: RaceView, vararg expectedCarNumbers: String) {
        for (tuple in expectedCarNumbers.withIndex()) {
            assertEquals(tuple.index + 1, view.lookupCar(tuple.value)?.position)
        }
    }
}