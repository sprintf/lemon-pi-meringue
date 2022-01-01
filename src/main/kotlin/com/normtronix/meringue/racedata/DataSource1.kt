package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Instant

open class DataSource1(val raceId:String) {

    val provider = "race-monitor"

    fun connect() : String {
        val now = Instant.now().epochSecond
        val jsonText = URL("https://api.${provider}.com/Info/WebRaceList?accountID=&seriesID=&raceID=${raceId}&styleID=&t=${now}").readText()
        val streamData = JsonParser.parseString(jsonText).asJsonObject
        if (!streamData.has("CurrentRaces")) {
            throw InvalidRaceId()
        }
        if (streamData["CurrentRaces"].asJsonArray.isEmpty) {
            throw InvalidRaceId()
        }
        val race = streamData["CurrentRaces"].asJsonArray[0].asJsonObject
        val liveTimingToken = streamData["LiveTimingToken"].asString
        val liveTimingHost = streamData["LiveTimingHost"].asString
        val instance = race["Instance"].asString
        log.info("race = $race instance = $instance")
        log.debug("token = $liveTimingToken  host = $liveTimingHost")
        return "wss://${liveTimingHost}/instance/${instance}/${liveTimingToken}"
    }

    suspend fun stream(streamUrl: String, handler: DataSourceHandler) {
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        log.info("connecting to $streamUrl")
        client.webSocket(streamUrl) {
            while(true) {
                val othersMessage = incoming.receive() as? Frame.Text
                val message = othersMessage?.readText()
                if (message != null && message.startsWith("$")) {
                    handler.handleWebSocketMessage(message)
                }
            }
        }
        // todo client.close()
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource1::class.java)
    }

}

class InvalidRaceId(): Exception()
