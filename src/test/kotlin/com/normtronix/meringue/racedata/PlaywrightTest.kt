package com.normtronix.meringue.racedata

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test


class PlaywrightTest {

    // @Test
    fun openWindow() {
        Playwright.create().use { playwright ->
            val browser = playwright.chromium().launch()
            val page: Page = browser.newPage()
            // page.navigate("https://api.race-monitor.com/Timing/?raceid=149008&source=web")
            page.onWebSocket {
                it.onFrameReceived {
                    println(it.text())
                }
                it.onClose {
                    println("closed")
                }
                it.onSocketError {
                    println("socket error")
                }
            }
 //           coroutineScope {
//                launch {
//                    println("sleeping for a minute")
//                    delay(5000)
//                    // hook this up to the disconnect request
//                    println("closing page")
//                    page.close()
//                    println("page closed")
//                }
//                launch {
                    println("navigating")
                    page.navigate("https://api.race-monitor.com/Timing/?raceid=37872&source=race-monitor.com")
                    println("completed")
                    page.setDefaultTimeout(86400000.0)
                    page.waitForWebSocket(Page.WaitForWebSocketOptions().setTimeout(86400000.0)) {
                        println("page closed")
                    }
                    println("set timeout")

//                    page.waitForWebSocket {
//                        println("got a websocket")
//                    }
//                }
//            }

            println("you never get here")

            //Thread.sleep(3000)
            //page.screenshot(Page.ScreenshotOptions().setPath(Paths.get("example.png")))
        }
    }

    // @Test
    fun testDataSource1b() {
        val ds = DataSource1b("37872")
        val url = ds.connect()
        val race = RaceOrder()
        val handler = DataSourceHandler(race, "test1", 150, setOf("236"))
        runBlocking {
            ds.stream(url, handler)
            println("finished")
        }
    }

}