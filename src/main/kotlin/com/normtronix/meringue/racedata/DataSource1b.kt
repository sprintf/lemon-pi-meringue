package com.normtronix.meringue.racedata

import com.normtronix.meringue.event.*
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

open class DataSource1b(val raceId:String) : EventHandler, RaceDataSource {

    val provider = "race-monitor"
    var page: Page? = null
    override var logRaceData = false

    override fun connect() : String {
        // return the url to the resource
        return "https://api.${provider}.com/Timing/?raceid=${raceId}&source=${provider}.com"
    }

    override suspend fun stream(context: Any, baseHandler: BaseDataSourceHandler) {
        val handler = baseHandler as DataSourceHandler
        val streamUrl = context as String
        log.info("connecting to $streamUrl")
        File("logs").mkdir()

        Events.register(
            RaceDisconnectEvent::class.java, this,
            filter = { it is RaceDisconnectEvent && it.trackCode == handler.trackCode })

        try {
            Playwright.create().use { playwright ->
                coroutineScope {
                    log.info("launching headless browser")
                    val browser = playwright.chromium().launch()
                    log.info("browser running")
                    page = browser.newPage()

                    page?.onWebSocket {
                        it.onFrameReceived {
                            val lines = it.text().split("\n")
                            for (line in lines) {
                                if (line.startsWith("$")) {
                                    launch(Dispatchers.IO) {
                                        handler.handleWebSocketMessage(line)
                                    }
                                } else if (line.trim().isNotEmpty()) {
                                    log.warn("IGNORING >> $line")
                                }
                            }
                            if (logRaceData) {
                                try {
                                    File("logs/race-$raceId.log").appendText(it.text())
                                } catch (e: IOException) {
                                    log.warn("failed to log racedata : ${e.message}")
                                }
                            }
                        }
                        it.onClose {
                            log.warn("websocket closed")
                            page?.close()
                        }
                        it.onSocketError {
                            log.warn("websocket socket error")
                            page?.close()
                        }
                    }

                    page?.navigate(streamUrl)
                    page?.setDefaultTimeout(86400000.0)
                    page?.waitForWebSocket {
                        log.info("websocket working")
                    }
                }
            }
        } catch (e: PlaywrightException) {
            log.warn("caught playwrite exception", e)
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource1b::class.java)
    }

    override suspend fun handleEvent(e: Event) {
        if (e is RaceDisconnectEvent) {
            page?.apply { if (!this.isClosed) { this.close() }}
        }
    }

}

