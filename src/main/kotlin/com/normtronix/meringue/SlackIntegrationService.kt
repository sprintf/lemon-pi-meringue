package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import com.normtronix.meringue.event.*
import com.slack.api.Slack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


@Service
class SlackIntegrationService(): InitializingBean, EventHandler {

    val slackKeys = mutableMapOf<String, String>()
    val slackPitChannels = mutableMapOf<String, String>()
    val slackInfoChannels = mutableMapOf<String, String>()

    @Autowired
    lateinit var db: Firestore

    override fun afterPropertiesSet() {
        Events.register(CarPittingEvent::class.java, this)
        Events.register(CarLeavingPitEvent::class.java, this)
        Events.register(CarTelemetryEvent::class.java, this)
    }

    @Scheduled(fixedDelayString = "5", timeUnit = TimeUnit.MINUTES)
    internal fun loadSlackKeys() {
        db.collection("lemon_pi_slack")?.get()?.get()?.documents?.forEach {
            if (it.contains("car_number") &&
                    it.contains("track_code") &&
                    it.contains("slack_token")) {
                slackKeys[buildKey(it["track_code"] as String, it["car_number"] as String)] =
                    it["slack_token"] as String

                if (it.contains("slack_pit_channel")) {
                    slackPitChannels[buildKey(it["track_code"] as String, it["car_number"] as String)] =
                        it["slack_pit_channel"] as String
                }

                if (it.contains("slack_info_channel")) {
                    slackInfoChannels[buildKey(it["track_code"] as String, it["car_number"] as String)] =
                        it["slack_info_channel"] as String
                }
            }
        }
    }

    override suspend fun handleEvent(e: Event) {
        when (e) {
            is CarPittingEvent -> {
                val channel = slackPitChannels[buildKey(e.trackCode, e.carNumber)]
                slackKeys[buildKey(e.trackCode, e.carNumber)]?.apply {
                    channel?.let {
                        sendSlackMessage(channel, "${getTime()} -> <!channel> Car ${e.carNumber} Pitting", this)
                    }
                }
            }
            is CarLeavingPitEvent -> {
                val channel = slackPitChannels[buildKey(e.trackCode, e.carNumber)]
                slackKeys[buildKey(e.trackCode, e.carNumber)]?.apply {
                    channel?.let {
                        sendSlackMessage(channel, "${getTime()} -> <!here> Car ${e.carNumber} Leaving Pits", this)
                    }
                }
            }
            is CarTelemetryEvent -> {
                val channel = slackInfoChannels[buildKey(e.trackCode, e.carNumber)]
                slackKeys[buildKey(e.trackCode, e.carNumber)]?.apply {
                    channel?.let {
                        val readableLapTime = "${(e.lastLapTimeSec / 60).toInt()}:${(e.lastLapTimeSec % 60).toInt()}"
                        // todo : move this into a configuration file
                        val alert = if (e.coolantTemp >= 220) { "<!channel>" } else { "" }
                        val message =
                            "${getTime()} ->   Car ${e.carNumber}  lap:${e.lapCount}  time:$readableLapTime   temp:${e.coolantTemp}F $alert"
                        log.info("sending $message to slack")
                        sendSlackMessage(channel, message, this)
                    }
                }
            }
            else -> {
                log.warn("no handler for event $e")
            }
        }
    }

    internal fun getTime() = SimpleDateFormat("h:mm:ss a").apply {
        timeZone = TimeZone.getTimeZone("PST")
    }.format(Date())

    internal suspend fun sendSlackMessage(channel: String, message: String, token: String) {
        Slack.getInstance().methodsAsync(token).chatPostMessage { b ->
            b.text(message)
            b.channel(channel)
        }
    }

    internal fun buildKey(trackCode: String, carNumber: String): String {
        return "${trackCode}:${carNumber}"
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(SlackIntegrationService::class.java)
    }
}