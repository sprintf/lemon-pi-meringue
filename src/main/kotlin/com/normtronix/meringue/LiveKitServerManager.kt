package com.normtronix.meringue

import com.google.cloud.compute.v1.InstancesClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import javax.annotation.PreDestroy

@Component
class LiveKitServerManager(
    @Value("\${livekit.gcp.project}") private val project: String,
    @Value("\${livekit.gcp.zone}") private val zone: String,
    @Value("\${livekit.gcp.instance}") private val instance: String,
) {
    private val log = LoggerFactory.getLogger(LiveKitServerManager::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun startLiveKitServer() {
        try {
            InstancesClient.create().use { client ->
                val status = client.get(project, zone, instance).status
                if (status == "RUNNING") {
                    log.info("LiveKit VM $instance is already running")
                    return
                }
                log.info("Starting LiveKit VM $instance (current status: $status)")
                client.startAsync(project, zone, instance).get()
                log.info("LiveKit VM $instance start initiated")
            }
        } catch (e: Exception) {
            log.warn("Could not start LiveKit VM $instance: ${e.message}")
        }
    }

    @PreDestroy
    fun stopLiveKitServer() {
        try {
            InstancesClient.create().use { client ->
                val status = client.get(project, zone, instance).status
                if (status != "RUNNING") {
                    log.info("LiveKit VM $instance is not running (status: $status), skipping stop")
                    return
                }
                log.info("Stopping LiveKit VM $instance")
                client.stopAsync(project, zone, instance).get()
                log.info("LiveKit VM $instance stop initiated")
            }
        } catch (e: Exception) {
            log.warn("Could not stop LiveKit VM $instance: ${e.message}")
        }
    }
}
