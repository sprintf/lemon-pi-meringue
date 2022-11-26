package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.event.CarTelemetryEvent
import com.normtronix.meringue.event.DriverMessageEvent
import com.normtronix.meringue.racedata.CarPosition
import com.normtronix.meringue.racedata.InvalidRaceId
import com.normtronix.meringue.racedata.RaceOrder
import com.normtronix.meringue.racedata.RaceView
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class CarDataServiceTest {

    @Test
    fun ping() {
        runBlocking {
            val ping = CarDataService().ping(Empty.getDefaultInstance())
            assertEquals(Empty.getDefaultInstance(), ping)
        }
    }

    @Test
    fun testEventHandling() {
        runBlocking {
            val cds = CarDataService()
            cds.afterPropertiesSet()
            assertEquals(0, cds.driverMessageMap.size())
            assertEquals(0, cds.telemetryMap.size())
            CarTelemetryEvent("track", "car", 1, 60.0F, 100, 0).emit()
            val ev1 = cds.telemetryMap.getIfPresent("track:car")
            assertEquals(100, ev1?.coolantTemp)
            assertEquals(0, ev1?.fuelRemainingPercent)
            DriverMessageEvent("track", "car", "wanker").emit()
            val ev2 = cds.driverMessageMap.getIfPresent("track:car")
            assertEquals("wanker", ev2?.message)
        }
    }

    @Test
    fun testGettingCarDataBadTrack() {
        assertThrows<InvalidRaceId> {
            runBlocking {
                val cds = CarDataService()
                cds.adminService = AdminService()
                mockkObject(cds.adminService)
                val request = CarData.CarDataRequest.newBuilder()
                    .setTrackCode("foo")
                    .build()
                cds.getCarData(request)
            }
        }
    }

    @Test
    fun testGettingCarDataBadCar() {
        assertThrows<CarDataService.NoSuchCarException> {
            runBlocking {
                val cds = CarDataService()
                cds.adminService = AdminService()
                mockkObject(cds.adminService)
                every { cds.adminService.getRaceView("track1") } returns buildRaceView()
                val request = CarData.CarDataRequest.newBuilder()
                    .setTrackCode("track1")
                    .setCarNumber("182")
                    .build()
                cds.getCarData(request)
            }
        }
    }

    @Test
    fun testGettingCarData() {
        runBlocking {
            val cds = CarDataService()
            cds.afterPropertiesSet()
            DriverMessageEvent("track1", "181", "wanker!").emit()
            CarTelemetryEvent("track1", "181", 2, 60.1f, 100, 0).emit()
            cds.adminService = AdminService()
            mockkObject(cds.adminService)
            every { cds.adminService.getRaceView("track1") } returns buildRaceView()
            val request = CarData.CarDataRequest.newBuilder()
                .setTrackCode("track1")
                .setCarNumber("181")
                .build()
            val response = cds.getCarData(request)
            assertEquals("181", response.carNumber)
            assertEquals(10, response.position)
            assertEquals(1, response.positionInClass)
            assertEquals("wanker!", response.driverMessage)
            assertEquals(100, response.coolantTemp)
        }
    }

    @Test
    fun testConvertingStatus() {
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.GREEN, CarDataService.convertStatus("green"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.GREEN, CarDataService.convertStatus("GREEN"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.RED, CarDataService.convertStatus("RED"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.YELLOW, CarDataService.convertStatus("yellow"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.BLACK, CarDataService.convertStatus("black"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.UNKNOWN, CarDataService.convertStatus("finished"))
        assertEquals(RaceFlagStatusOuterClass.RaceFlagStatus.UNKNOWN, CarDataService.convertStatus(""))
    }

    private fun buildRaceView() : RaceView {

        return RaceView("green",
            mapOf("181" to CarPosition("181", "", RaceOrder.Car("181", "", "")).apply {
                position = 10
                positionInClass = 1
            })
        )
    }
}