package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import com.normtronix.meringue.event.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Instant

open class DataSource1(val raceId:String) : EventHandler {

    val provider = "race-monitor"
    val fields: MutableMap<String, String> = mutableMapOf()
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
        val liveTimingHost = streamData["LiveTimingHost"].asString
        fields["token"] = streamData["LiveTimingToken"].asString
        fields["instance"] = race["Instance"].asString
        log.debug("fields = ${fields.values}")
        return "wss://${liveTimingHost}/instance/${fields["instance"]}/${fields["token"]}"
    }

    suspend fun stream(streamUrl: String, handler: DataSourceHandler) {
        val client = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 5000
            }
            install(HttpTimeout) {
                socketTimeoutMillis = 3000
            }
            BrowserUserAgent()
        }
        log.info("connecting to $streamUrl")

        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == handler.trackCode})

        try {
            client.webSocket(streamUrl) {
                log.info("connected to url, timeout = ${this.timeoutMillis},  ping_interval = ${this.pingIntervalMillis}")
                val joinMsg = "\$JOIN,${fields["instance"]},${fields["token"]}}"
                val joinMessage = Frame.byType(true, FrameType.TEXT, joinMsg.encodeToByteArray())
                outgoing.send(joinMessage)
                while (!stopped) {
                    val joinedMessage = StringBuilder()
                    do {
                        val incomingFrame = incoming.receive()
                        if (incomingFrame is Frame.Close) {
                            log.warn("websocket stream has ended")
                            stopped = true
                            break
                        }
                        val othersMessage = incomingFrame as? Frame.Text
                        joinedMessage.append(othersMessage?.readText())
                    } while (othersMessage != null && !othersMessage.fin)
                    val message = joinedMessage.toString()
                    if (message.isNotEmpty()) {
                        val lines = message.split("\n")
                        for (line in lines) {
                            if (line.startsWith("$")) {
                                handler.handleWebSocketMessage(line)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("got an exception from websocket", e)
        } finally {
            log.warn("client is closing, unclear if exception was thrown")
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
