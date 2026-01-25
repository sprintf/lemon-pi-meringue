package com.normtronix.meringue

import com.normtronix.meringue.event.*
import com.slack.api.Slack
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AdminNotificationService : InitializingBean, EventHandler {

    @Value("\${slack-lemons-racer-app-token:}")
    lateinit var slackToken: String

    private val channel = "new-app-installs"

    override fun afterPropertiesSet() {
        Events.register(NewDeviceRegisteredEvent::class.java, this)
        Events.register(NewEmailAddressAddedEvent::class.java, this)
        log.info("AdminNotificationService registered for device and email events")
    }

    override suspend fun handleEvent(e: Event) {
        if (slackToken.isBlank()) {
            log.warn("Slack token not configured, skipping admin notification")
            return
        }

        when (e) {
            is NewDeviceRegisteredEvent -> {
                val message = ":new: New device registered: `${e.deviceId}` for car ${e.carNumber} at ${e.trackCode}"
                sendSlackMessage(message)
            }
            is NewEmailAddressAddedEvent -> {
                val message = ":email: New email added: `${e.email}` for car ${e.carNumber}"
                sendSlackMessage(message)
            }
        }
    }

    private fun sendSlackMessage(message: String) {
        try {
            val response = Slack.getInstance().methods(slackToken).chatPostMessage { req ->
                req.channel(channel)
                req.text(message)
            }
            if (response.isOk) {
                log.info("Sent admin notification to Slack: {}", message)
            } else {
                log.error("Failed to send Slack message: {}", response.error)
            }
        } catch (e: Exception) {
            log.error("Error sending Slack notification: {}", e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdminNotificationService::class.java)
    }
}
