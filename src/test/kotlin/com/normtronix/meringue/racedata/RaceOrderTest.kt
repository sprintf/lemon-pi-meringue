package com.normtronix.meringue.racedata

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class RaceOrderTest {

    @Test
    fun testIntegrityWorksNoClassBeforeStartOfRace() {
        val car1 = CarPosition("100", "", null)
        val car2 = CarPosition("101", "", null)
        val car3 = CarPosition("102", "", null)

        val race = RaceOrder()
        race.addCar(car1)
        race.addCar(car2)
        race.addCar(car3)

        race.checkIntegrity()
        assertEquals(car1, race.getLeadCar())
    }

    @Test
    fun testIntegrityWorksWithClassBeforeStartOfRace() {
        val car1 = CarPosition("100", "", "A")
        val car2 = CarPosition("101", "", "B")
        val car3 = CarPosition("102", "", "A")

        val race = RaceOrder()
        race.addCar(car1)
        race.addCar(car2)
        race.addCar(car3)

        race.checkIntegrity()
        assertEquals(car1, race.getLeadCar())
    }

    @Test
    fun testIntegrityWorksWithClassOnFirstLap() {
        val car1 = CarPosition("100", "", "A")
        val car2 = CarPosition("101", "", "B")
        val car3 = CarPosition("102", "", "A")

        val race = RaceOrder()
        race.addCar(car1)
        race.addCar(car2)
        race.addCar(car3)

        race.updatePosition("100", 1, 1)
        race.updatePosition("102", 2, 1)
        race.updatePosition("101", 3, 1)

        race.checkIntegrity()

        assertEquals(car1, race.getLeadCar())
        assertEquals(car3, race.getLeadCar()?.carBehind)

        race.updatePosition("101", 1, 2)
        race.updatePosition("102", 2, 2)

        assertEquals(car2, race.getLeadCar())
        assertEquals(car3, race.getLeadCar()?.carBehind)
    }

    @Test
    fun randomTest150Cars() {
        val race = RaceOrder()
        val cars:List<CarPosition> = buildCars(150)
        cars.forEach { race.addCar(it) }

        for(lap in 1..300) {
            val orderThisLap = cars.shuffled()
            orderThisLap.withIndex().forEach { (a, b) -> race.updatePosition(b.carNumber, a, lap) }
        }

        race.checkIntegrity()
    }

    private fun buildCars(i: Int): List<CarPosition> {
        val result: MutableList<CarPosition> = mutableListOf()
        for(loop in 0..i) {
            result.add(CarPosition((loop + 1).toString(), "", "A"))
        }
        return result
    }


}