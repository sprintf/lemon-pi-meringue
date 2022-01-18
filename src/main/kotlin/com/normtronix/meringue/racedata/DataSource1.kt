package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import com.normtronix.meringue.event.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Instant

open class DataSource1(val raceId:String) : EventHandler {

    val provider = "race-monitor"
    var stopped = false

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

        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == handler.trackCode})

        try {
            client.webSocket(streamUrl) {
                while (!stopped) {
                    val othersMessage = incoming.receive() as? Frame.Text
                    val message = othersMessage?.readText()
                    if (message != null) {
                        val lines = message.split("\n")
                        for (line in lines) {
                            if (line.startsWith("$")) {
                                handler.handleWebSocketMessage(line)
                            } else {
                                log.info("discarding input: $line")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(e)
        } finally {
            client.close()
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource1::class.java)
    }

    override suspend fun handleEvent(e: Event) {
        if (e is RaceDisconnectEvent) {
            stopped = true
        }
    }

}

class InvalidRaceId(): Exception()
