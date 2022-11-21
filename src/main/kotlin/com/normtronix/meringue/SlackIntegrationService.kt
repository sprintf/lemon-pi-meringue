package com.normtronix.meringue

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.normtronix.meringue.event.*
import com.slack.api.Slack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit


@Service
class SlackIntegrationService: InitializingBean, EventHandler {

    private var db: Firestore? = null
    val slackKeys = mutableMapOf<String, String>()
    val slackChannels = mutableMapOf<String, String>()

    override fun afterPropertiesSet() {
        Events.register(CarPittingEvent::class.java, this)
        Events.register(CarLeavingPitEvent::class.java, this)

        val firestoreOptions = FirestoreOptions.getDefaultInstance().toBuilder()
            .setProjectId("meringue")
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .build()
        this.db = firestoreOptions.getService()
    }

    @Scheduled(fixedDelayString = "5", timeUnit = TimeUnit.MINUTES)
    internal fun loadSlackKeys() {
        db?.collection("lemon_pi_slack")?.get()?.get()?.documents?.forEach {
            if (it.contains("car_number") &&
                    it.contains("track_code") &&
                    it.contains("slack_token")) {
                slackKeys[buildKey(it["track_code"] as String, it["car_number"] as String)] = it["slack_token"] as String
                slackChannels[buildKey(it["track_code"] as String, it["car_number"] as String)] = it["slack_channel"] as String
            }
        }
    }

    override suspend fun handleEvent(e: Event) {
        when (e) {
            is CarPittingEvent -> {
                val channel = slackChannels[buildKey(e.trackCode, e.carNumber)]
                slackKeys[buildKey(e.trackCode, e.carNumber)]?.apply {
                    channel?.let {
                        sendSlackMessage(channel, "Car ${e.carNumber} Pitting", this)
                    }
                }
            }
            is CarLeavingPitEvent -> {
                val channel = slackChannels[buildKey(e.trackCode, e.carNumber)]
                slackKeys[buildKey(e.trackCode, e.carNumber)]?.apply {
                    channel?.let {
                        sendSlackMessage(channel, "Car ${e.carNumber} Leaving Pits", this)
                    }
                }
            }
            else -> {

            }
        }
    }

    internal suspend fun sendSlackMessage(channel: String, message: String, token: String) {
        Slack.getInstance().methodsAsync(token).chatPostMessage { b ->
            b.text(message)
            b.channel(channel)
        }
    }

    private fun buildKey(trackCode: String, carNumber: String): String {
        return "${trackCode}:${carNumber}"
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(SlackIntegrationService::class.java)
    }
}