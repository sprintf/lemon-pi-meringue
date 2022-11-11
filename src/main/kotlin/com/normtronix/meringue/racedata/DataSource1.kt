package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import com.normtronix.meringue.event.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant

open class DataSource1(val raceId:String) : EventHandler, RaceDataSource {

    val provider = "race-monitor"
    val fields: MutableMap<String, String> = mutableMapOf()
    var stopped = false
    override var logRaceData = false

    override fun connect() : String {
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

    override suspend fun stream(context: Any, baseHandler: BaseDataSourceHandler) {
        val handler = baseHandler as DataSourceHandler
        val streamUrl = context as String
        log.info("connecting to $streamUrl")
        File("logs").mkdir()

        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == handler.trackCode})

        while (!stopped) {
            val client = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = 5000
                }
                install(HttpTimeout) {
                    socketTimeoutMillis = 3000
                }
                BrowserUserAgent()
            }
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
                                    launch {
                                        handler.handleWebSocketMessage(line)
                                    }
                                }
                            }
                            if (logRaceData) {
                                try {
                                    File("logs/race-$raceId.log").appendText(message)
                                } catch (e: IOException) {
                                    log.warn("failed to log racedata : ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                log.info("got an exception reading from websocket, will reconnect", e)
            } catch (e: Exception) {
                log.error("got an exception", e)
            }
            client.close()
            delay(10000)
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
