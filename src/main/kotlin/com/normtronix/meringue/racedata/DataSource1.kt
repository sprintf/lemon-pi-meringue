package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.time.Instant

class DataSource1(val raceId:String) {

    val provider = "race-monitor"

    fun connect() : String {
        val now = Instant.now().epochSecond
        val jsonText = URL("https://api.${provider}.com/Info/WebRaceList?accountID=&seriesID=&raceID=${raceId}&styleID=&t=${now}").readText()
        val streamData = JsonParser.parseString(jsonText).asJsonObject
        val race = streamData["CurrentRaces"].asJsonArray[0].asJsonObject
        val liveTimingToken = streamData["LiveTimingToken"].asString
        val liveTimingHost = streamData["LiveTimingHost"].asString
        val instance = race["Instance"].asString
        println("race = $race instance = $instance")
        println("token = $liveTimingToken  host = $liveTimingHost")
        return "wss://${liveTimingHost}/instance/${instance}/${liveTimingToken}"
    }

    fun stream(streamUrl: String, handler: DataSourceHandler) {
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        runBlocking {
            println("connecting to $streamUrl")
            client.webSocket(streamUrl) {
                while(true) {
                    val othersMessage = incoming.receive() as? Frame.Text
                    val message = othersMessage?.readText()
                    if (message != null && message.startsWith("$")) {
                        handler.handleWebSocketMessage(message)
                    }
                }
            }
        }
        client.close()
    }


}

//fun main() {
//    println("running")
//    val ds1 = DataSource1("37872")
//    val streamUrl = ds1.connect()
//    ds1.stream(streamUrl, DataSourceHandler(RaceOrder(), listOf("23", "49")))
//    println("finished")
//}