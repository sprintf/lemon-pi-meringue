package com.normtronix.meringue

import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode
import com.google.cloud.compute.v1.InstancesClient
import com.normtronix.meringue.event.CarConnectedEvent
import com.normtronix.meringue.event.Event
import com.normtronix.meringue.event.EventHandler
import com.normtronix.meringue.event.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import javax.annotation.PreDestroy

@Component
class LiveKitServerManager(
    @Value("\${livekit.gcp.project}") private val project: String,
    @Value("\${livekit.gcp.zone}") private val zone: String,
    @Value("\${livekit.gcp.instance}") private val instance: String,
) : EventHandler {
    private val log = LoggerFactory.getLogger(LiveKitServerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastCarActivityAt: Instant = Instant.EPOCH

    companion object {
        private val RETRY_DELAYS_MS = listOf(1_000L, 5_000L, 10_000L, 30_000L, 60_000L)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun startLiveKitServer() {
        Events.register(CarConnectedEvent::class.java, this)
        scope.launch { startWithRetry() }
    }

    private suspend fun startWithRetry() {
        var attempt = 0
        while (true) {
            when (tryStart()) {
                StartResult.STARTED, StartResult.ALREADY_RUNNING -> return
                StartResult.SPOT_EXHAUSTED -> {
                    val delayMs = RETRY_DELAYS_MS.getOrElse(attempt) { 60_000L }
                    log.warn("No spot capacity for LiveKit VM $instance, retrying in ${delayMs / 1000}s (attempt ${attempt + 1})")
                    delay(delayMs)
                    attempt++
                }
                StartResult.FAILED -> return
            }
        }
    }

    private fun tryStart(): StartResult {
        return try {
            InstancesClient.create().use { client ->
                val status = client.get(project, zone, instance).status
                if (status == "RUNNING") {
                    log.info("LiveKit VM $instance is already running")
                    return StartResult.ALREADY_RUNNING
                }
                log.info("Starting LiveKit VM $instance (current status: $status)")
                client.startAsync(project, zone, instance).get()
                log.info("LiveKit VM $instance start initiated")
                StartResult.STARTED
            }
        } catch (e: ApiException) {
            if (e.statusCode.code == StatusCode.Code.RESOURCE_EXHAUSTED) {
                StartResult.SPOT_EXHAUSTED
            } else {
                log.warn("Could not start LiveKit VM $instance: ${e.message}")
                StartResult.FAILED
            }
        } catch (e: Exception) {
            log.warn("Could not start LiveKit VM $instance: ${e.message}")
            StartResult.FAILED
        }
    }

    private enum class StartResult { STARTED, ALREADY_RUNNING, SPOT_EXHAUSTED, FAILED }

    override suspend fun handleEvent(e: Event) {
        lastCarActivityAt = Instant.now()
    }

    @PreDestroy
    fun stopLiveKitServer() {
        scope.cancel()
        val minutesSinceLastCar = java.time.Duration.between(lastCarActivityAt, Instant.now()).toMinutes()
        if (minutesSinceLastCar < 10) {
            log.info("Car connected ${minutesSinceLastCar}m ago, skipping LiveKit shutdown (likely rolling deploy)")
            return
        }
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
