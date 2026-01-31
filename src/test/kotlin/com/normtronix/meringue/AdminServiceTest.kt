package com.normtronix.meringue

import com.google.protobuf.Empty
import com.normtronix.meringue.MeringueAdmin.CarAddViaSlackRequest
import com.normtronix.meringue.MeringueAdmin.CarStatusSlackRequest
import com.normtronix.meringue.racedata.DataSource1
import com.normtronix.meringue.racedata.InvalidRaceId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

@kotlinx.coroutines.ExperimentalCoroutinesApi
internal class AdminServiceTest {

    @Test
    fun ping() {
        runBlocking {
            val ping = AdminService().ping(Empty.getDefaultInstance())
            assertEquals(Empty.getDefaultInstance(), ping)
        }
    }

    @Test
    fun listTracks() {
        runBlocking {
            val admin = AdminService()
            mockTrackMetadata(admin)
            val result = admin.listTracks(Empty.getDefaultInstance())
            assertEquals(2, result.trackCount)
            // expect them in alpha order for now todo : apply sorting
            assertEquals("snma", result.getTrack(0).code)
            assertEquals("Sonoma", result.getTrack(0).name)
            assertEquals("thil", result.getTrack(1).code)
        }
    }

    @Test
    fun connectToRaceDataWithBadRaceId() {
        assertThrows<InvalidRaceId> {
            val admin = AdminService()
            mockTrackMetadata(admin)
            val ds = DataSource1("12345")
            mockkObject(ds)
            every { ds.connect() } throws InvalidRaceId()
            admin.raceDataSourceFactoryFn = fun(_:MeringueAdmin.RaceDataProvider, _: String): DataSource1 { return ds }

            runBlocking {
                val request = MeringueAdmin.ConnectToRaceDataRequest.newBuilder()
                    .setProvider(MeringueAdmin.RaceDataProvider.PROVIDER_RM)
                    .setProviderId("12345")
                    .setTrackCode("thil")
                    .build()
                admin.connectToRaceData(request)
            }
        }
    }

    @Test
    fun connectToRaceDataOK() {
        val admin = AdminService()
        mockTrackMetadata(admin)
        admin.lemonPiService = Server()
        admin.logRaceData = "false"
        admin.delayLapCompletedEvent = "150"
        val ds = DataSource1("12345")
        mockkObject(ds)
        every { ds.connect() } returns "wss://localhost:443/foo.json"
        coEvery { ds.stream("wss://localhost:443/foo.json", any()) } returns Unit
        admin.raceDataSourceFactoryFn = fun(_:MeringueAdmin.RaceDataProvider, _: String): DataSource1 { return ds }

        runBlocking {
            val request = MeringueAdmin.ConnectToRaceDataRequest.newBuilder()
                .setProvider(MeringueAdmin.RaceDataProvider.PROVIDER_RM)
                .setProviderId("12345")
                .setTrackCode("thil")
                .build()
            val result = admin.connectToRaceData(request)
            assertEquals("thil:12345", result.handle)
            assertEquals("Thunderhill", result.trackName)
            //assertFalse(result.running)
            //println(result)
        }
    }

    private fun mockTrackMetadata(admin: AdminService) {
        val tmdl = TrackMetaDataLoader()
        mockkObject(tmdl)
        admin.logRaceData = "false"
        admin.trackMetaData = tmdl
        every { tmdl.listTracks() } returns fakeTracks()
    }

    // uncomment the
    // @Test
    fun manualTest() {
        val runTimeSeconds = 100
        val admin = AdminService()
        admin.delayLapCompletedEvent = "0"
        admin.logRaceData = "false"
        admin.lemonPiService = Server()
        admin.trackMetaData = TrackMetaDataLoader()
        runBlocking {
            val tracks = admin.listTracks(Empty.getDefaultInstance())
            assertTrue(tracks.trackCount > 10)
            var current = admin.listRaceDataConnections(Empty.getDefaultInstance())
            assertTrue(current.responseCount == 0)
            // connect to a real race
            val request = MeringueAdmin.ConnectToRaceDataRequest.newBuilder()
                .setProvider(MeringueAdmin.RaceDataProvider.PROVIDER_RM)
                .setProviderId("37820") // 37820 or 37872
                .setTrackCode("lgna")
                .build()

            coroutineScope {
                launch {
                    admin.connectToRaceData(request)
                }
                launch {
                    delay(runTimeSeconds * 1000L)
                    current = admin.listRaceDataConnections(Empty.getDefaultInstance())
                    assertEquals(1, current.responseCount)
                    println(current.getResponse(0))
                }
                launch {
                    delay((runTimeSeconds + 1) * 1000L)
                    val cancelRequest = MeringueAdmin.RaceDataConnectionRequest.newBuilder()
                        .setHandle(current.getResponse(0).handle)
                        .build()
                    admin.disconnectRaceData(cancelRequest)

                    delay(1000)
                    current = admin.listRaceDataConnections(Empty.getDefaultInstance())
                    println(current)
                    assertEquals(1, current.responseCount)
                    assertFalse(current.getResponse(0).running)

                    delay(30000)
                }
            }
        }
    }

    // if there is an existing active connection to a race, then reuse it
    // when we cancel a job it becomes inactive
    // we can get a list of all connected races
    // we can disconnect a running race

    private fun fakeTracks(): List<TrackMetaDataLoader.Track> {
        return listOf(
            makeTrack("Sonoma", "snma"),
            makeTrack("Thunderhill", "thil")
        )
    }

    private fun makeTrack(name: String, code: String) : TrackMetaDataLoader.Track {
        val t = TrackMetaDataLoader.Track()
        t.name = name
        t.code = code
        return t
    }

    @Test
    fun listRaceDataConnections() {
    }

    @Test
    fun disconnectRaceData() {
    }

    @Test
    fun testFuzzyComparator() {
        val r1: MeringueAdmin.LiveRace = MeringueAdmin.LiveRace.newBuilder()
            .setRaceId("foo1")
            .setEventName("Yokohama Stuntin and Splodin")
            .setTrackName("MSR Houston")
            .build()

        val r2: MeringueAdmin.LiveRace = MeringueAdmin.LiveRace.newBuilder()
            .setRaceId("foo2")
            .setEventName("Arse Freeze Apalooza")
            .setTrackName("Sonoma Raceway")
            .build()

        assertEquals(listOf(r1, r2), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(emptyList())))
        assertEquals(listOf(r1, r2), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(listOf("Houston"))))
        assertEquals(listOf(r1, r2), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(listOf("houston"))))
        assertEquals(listOf(r1, r2), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(listOf("houston something"))))
        assertEquals(listOf(r2, r1), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(listOf("sonoma"))))
        assertEquals(listOf(r2, r1), listOf(r1, r2).sortedWith(AdminService.FuzzyComparator(listOf("sonoma", "raceway"))))
        
    }

    @Test
    fun getCarStatusNothingConnected() {
        runBlocking {
            val admin = AdminService()
            admin.slackService = mockk("foo")
            every { admin.slackService.getCarsForSlackToken("slack") } returns emptyList()
            val request = CarStatusSlackRequest.newBuilder().apply { this.slackToken = "slack" }.build()
            val result = admin.getCarStatus(request)
            assertTrue(result.statusListCount == 0)
        }
    }

    @Test
    fun getCarStatusOneConnected() {
        runBlocking {
            val admin = AdminService()
            admin.slackService = mockk("foo")
            admin.connectedCarStore = mockk("bar")
            every { admin.slackService.getCarsForSlackToken("slack") } returns listOf(TrackAndCar("tr1", "181"))
            coEvery { admin.connectedCarStore.getStatus("tr1", "181") } returns
                    ConnectedCarStore.CarConnectedStatus(true, "0:0:0:0", "device1")
            val request = CarStatusSlackRequest.newBuilder().apply { this.slackToken = "slack" }.build()
            val result = admin.getCarStatus(request)
            assertTrue(result.statusListCount == 1)
            assertEquals("181", result.getStatusList(0).carNumber)
            assertEquals("tr1", result.getStatusList(0).trackCode)
            assertEquals(true, result.getStatusList(0).online)
            assertEquals("0:0:0:0", result.getStatusList(0).ipAddress)
        }
    }

    @Test
    fun getCarStatusMultipleCarsConnected() {
        runBlocking {
            val admin = AdminService()
            admin.slackService = mockk("foo")
            admin.connectedCarStore = mockk("bar")
            every { admin.slackService.getCarsForSlackToken("slack") } returns
                    listOf(TrackAndCar("tr1", "181"),
                    TrackAndCar("tr1", "182"),
                    TrackAndCar("tr1", "183"))
            coEvery { admin.connectedCarStore.getStatus("tr1", any()) } returns
                    ConnectedCarStore.CarConnectedStatus(true, "0:0:0:0", "device1")
            val request = CarStatusSlackRequest.newBuilder().apply { this.slackToken = "slack" }.build()
            val result = admin.getCarStatus(request)
            assertTrue(result.statusListCount == 3)
        }
    }

    @Test
    fun locateAndAddCarNoCar() {
        runBlocking {
            val admin = AdminService()
            admin.slackService = mockk("foo")
            admin.connectedCarStore = mockk("bar")
            coEvery { admin.connectedCarStore.getConnectedCars("127.0.0.1") } returns emptyList()
            every { admin.slackService.getCarsForSlackToken("slack") } returns emptyList()
            val request = CarAddViaSlackRequest.newBuilder().apply {
                this.slackToken = "slack"
                this.ipAddress = "127.0.0.1"
            }.build()
            val result = admin.associateCarWithSlack(request)
            assertTrue(result.statusListCount == 0)
        }
    }

    @Test
    fun locateAndAddCarFindsCar() {
        runBlocking {
            val admin = AdminService()
            admin.slackService = mockk("foo")
            admin.connectedCarStore = mockk("bar")
            coEvery { admin.connectedCarStore.getConnectedCars("127.0.0.1") } returns listOf(TrackAndCar("tr1", "181"))
            coEvery { admin.slackService.createCarConnection("tr1", "181", "app", "token") } returns
                    Unit
            every { admin.slackService.getCarsForSlackToken("token") } returns listOf(TrackAndCar("tr1", "181"))
            coEvery { admin.connectedCarStore.getStatus("tr1", "181")} returns ConnectedCarStore.CarConnectedStatus(true, "127.0.0.1", "device1")
            val request = CarAddViaSlackRequest.newBuilder().apply {
                this.slackToken = "token"
                this.ipAddress = "127.0.0.1"
                this.slackAppId = "app"
            }.build()
            val result = admin.associateCarWithSlack(request)
            assertTrue(result.statusListCount == 1)
        }
    }

}