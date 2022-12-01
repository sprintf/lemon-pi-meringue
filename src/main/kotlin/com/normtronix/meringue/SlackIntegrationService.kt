package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import com.normtronix.meringue.event.*
import com.slack.api.Slack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


private const val TRACK_CODE = "track_code"

private const val CAR_NUMBER = "car_number"

private const val SLACK_PIT_CHANNEL = "slack_pit_channel"

private const val SLACK_INFO_CHANNEL = "slack_info_channel"

private const val SLACK_TOKEN = "slack_token"

@Service
class SlackIntegrationService(): InitializingBean, EventHandler {

    val slackKeys = mutableMapOf<String, String>()
    val slackPitChannels = mutableMapOf<String, String>()
    val slackInfoChannels = mutableMapOf<String, String>()
    var coolantAlertLevel: Int = 210

    @Autowired
    lateinit var db: Firestore

    @Value("\${slack.coolantAlertLevel}")
    lateinit var coolantAlertLevelStr: String

    override fun afterPropertiesSet() {
        coolantAlertLevel = coolantAlertLevelStr.toInt()
        log.info("coolant Slack alert level set to $coolantAlertLevel")
        Events.register(CarPittingEvent::class.java, this)
        Events.register(CarLeavingPitEvent::class.java, this)
        Events.register(CarTelemetryEvent::class.java, this)
    }

    @Scheduled(fixedDelayString = "5", timeUnit = TimeUnit.MINUTES)
    internal fun loadSlackKeys() {
        db.collection("lemon_pi_slack").get().get()?.documents?.forEach {
            if (it.contains(CAR_NUMBER) &&
                    it.contains(TRACK_CODE) &&
                    it.contains(SLACK_TOKEN)) {
                slackKeys[buildKey(it[TRACK_CODE] as String, it[CAR_NUMBER] as String)] =
                    it[SLACK_TOKEN] as String

                if (it.contains(SLACK_PIT_CHANNEL)) {
                    slackPitChannels[buildKey(it[TRACK_CODE] as String, it[CAR_NUMBER] as String)] =
                        it[SLACK_PIT_CHANNEL] as String
                }

                if (it.contains(SLACK_INFO_CHANNEL)) {
                    slackInfoChannels[buildKey(it[TRACK_CODE] as String, it[CAR_NUMBER] as String)] =
                        it[SLACK_INFO_CHANNEL] as String
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
                        val alert = if (e.coolantTemp >= coolantAlertLevel) { "<!channel>" } else { "" }
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