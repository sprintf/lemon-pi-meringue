package com.normtronix.meringue

import com.google.cloud.firestore.Firestore
import com.normtronix.meringue.event.*
import com.slack.api.Slack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.stream.Collectors


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
    internal fun loadSlackKeys() = runBlocking {
        withContext(Dispatchers.IO) {
            db.collection("lemon_pi_slack").get().get()?.documents?.forEach {
                val trackCode = it.getString(TRACK_CODE)
                val carNumber = it.getString(CAR_NUMBER)
                val slackToken = it.getString(SLACK_TOKEN)

                if (trackCode != null && carNumber != null && slackToken != null) {
                    val key = buildKey(trackCode, carNumber)
                    slackKeys[key] = slackToken

                    it.getString(SLACK_PIT_CHANNEL)?.let { channel ->
                        slackPitChannels[key] = channel
                    }

                    it.getString(SLACK_INFO_CHANNEL)?.let { channel ->
                        slackInfoChannels[key] = channel
                    }
                }
            }
        }
    }

    suspend fun createCarConnection(trackCode: String, carNumber: String, slackAppId: String, slackToken: String) {
        // todo : dont create if it already exists
        // todo : delete if this token is associated with the car at another track
        withContext(Dispatchers.IO) {
            db.collection("lemon-pi-slack")
                .document(slackAppId)
                .set(hashMapOf(
                    TRACK_CODE to trackCode,
                    CAR_NUMBER to carNumber,
                    SLACK_TOKEN to slackToken,
                    SLACK_PIT_CHANNEL to "car-pitting",
                    SLACK_INFO_CHANNEL to "car-race-info",
                ).toMap())
                .get(500, TimeUnit.MILLISECONDS)
        }
    }

    fun getCarsForSlackToken(slackToken: String): List<TrackAndCar>? {
        // avoid concurrent mod issues
        val keyList = slackKeys.keys.toList()
        return keyList.stream().filter {
            slackKeys[it]?.contains(slackToken)?:false
        }.map {
            TrackAndCar.from(it)
        }.collect(Collectors.toList())
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
                        val readableLapTime = "${(e.lastLapTimeSec / 60).toInt()}:%02d"
                            .format((e.lastLapTimeSec % 60).toInt())
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