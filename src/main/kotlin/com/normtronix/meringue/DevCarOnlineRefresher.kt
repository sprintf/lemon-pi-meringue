package com.normtronix.meringue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Dev-only component that periodically refreshes the Firestore TTL for all
 * in-memory connected cars.
 *
 * In production, the GCP load balancer kills connections every ~5 minutes,
 * forcing the Android app to reconnect and naturally refresh the TTL.
 * Locally there is no LB, so connections stay open indefinitely and the TTL
 * expires while the car is still connected.
 */
@Component
@Profile("dev")
class DevCarOnlineRefresher(
    private val server: Server,
    private val connectedCarStore: ConnectedCarStore
) {

    companion object {
        val log: Logger = LoggerFactory.getLogger(DevCarOnlineRefresher::class.java)
        private const val REFRESH_INTERVAL_MS = 4 * 60 * 1000L
    }

    @EventListener(ApplicationReadyEvent::class)
    fun startRefresher() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                refreshAll()
            }
        }
        log.info("dev TTL refresher started (interval={}ms)", REFRESH_INTERVAL_MS)
    }

    private suspend fun refreshAll() {
        server.toCarIndex.forEach { (trackCode, cars) ->
            cars.keys.forEach { carNumber ->
                connectedCarStore.refreshTtl(trackCode, carNumber)
                log.debug("dev: refreshed online TTL for $carNumber at $trackCode")
            }
        }
    }
}
