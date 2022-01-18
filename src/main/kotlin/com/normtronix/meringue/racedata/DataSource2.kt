package com.normtronix.meringue.racedata

import com.google.gson.JsonParser
import com.normtronix.meringue.event.*
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.net.URL
import java.time.Instant


open class DataSource2(val raceId:String) : EventHandler {

    val provider = "race-monitor"
    private var streamSession: WSAdapter? = null

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

    fun stream(streamUrl: String, handler: DataSourceHandler) {
        log.info("connecting to $streamUrl")

        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter={it is RaceDisconnectEvent && it.trackCode == handler.trackCode})

        streamSession = WSAdapter(handler)
        streamSession?.run(streamUrl)
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource1::class.java)
    }

    override suspend fun handleEvent(e: Event) {
        if (e is RaceDisconnectEvent) {
            streamSession?.cancel()
        }
    }

}

private class WSAdapter(val handler: DataSourceHandler) : TextWebSocketHandler() {

    private var wsSession: WebSocketSession? = null

    fun run(url: String) {
        val ws = StandardWebSocketClient()
        wsSession = ws.doHandshake(this,
                                    WebSocketHttpHeaders(),
                                    URI.create(url)).get()

    }

    fun cancel() {
        wsSession?.close()
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        if (message.payload.startsWith("$")){
            runBlocking {
                handler.handleWebSocketMessage(message.payload)
            }
        }
    }

    // todo : add error / close handling
    // todo : retry if closed from other end
}
