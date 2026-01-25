package com.normtronix.meringue.event

import com.normtronix.meringue.LemonPi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.stream.Collectors

interface EventHandler {

    suspend fun handleEvent(e: Event)
}

class Events {

    companion object {
        val registry: MutableMap<Class<*>, MutableList<HandlerAndFilter>> = mutableMapOf()

        // Application-scoped coroutine scope for async event emission
        // Uses SupervisorJob so one failed event doesn't cancel others
        private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Register to hear about a type of event when it is emitted.
         * Callers should do this in their init { } block so it occurs only
         * once.
         * Optionally a filter can be provided in order to only call back on
         *   more specific events
         */
        fun register(clazz: Class<*>,
                     handler: EventHandler,
                     filter: (Event) -> Boolean = { true }) {
            if (!registry.containsKey(clazz)) {
                registry[clazz] = mutableListOf(HandlerAndFilter(handler, filter))
            } else {
                registry[clazz]?.add(HandlerAndFilter(handler, filter))
            }
        }

        fun unregister(handler: EventHandler) {
            // find all instances of datasource handler and remove them
            registry.entries
                .stream()
                .forEach { outer ->
                    val deletionList = outer.value.stream().map {
                        if (it.handler == handler) {
                            it
                        } else {
                            null
                        }
                    }.collect(Collectors.toList())
                    deletionList.stream().forEach {
                        outer.value.remove(it)
                    }
                }
        }

        fun launchAsync(block: suspend () -> Unit) {
            eventScope.launch { block() }
        }

        fun shutdown() {
            eventScope.cancel()
        }

        var lastEmittedEvent: Event? = null
    }

    data class HandlerAndFilter(val handler: EventHandler, val filter: (Event) -> Boolean)
}

open class Event(val debounce: Boolean = false) {

    suspend fun emit() {
        // this is a crude debounce ... we're seeing multiple events updated and sent to the car when
        // the car crosses the line. This should reduce all the messages being sent to the car.
        // The only events that have debounce turned on are the LapCompletedEvent.
        if (this.debounce && this.equals(Events.lastEmittedEvent)) {
            log.info("suppressing $this")
            return
        }
        if (this !is GpsPositionEvent) {
            log.info("emitting $this")
        }
        val event = this
        // for each of the handlers, call them
        val handlers = Events.registry[this.javaClass]
        coroutineScope() {
            handlers?.forEach {
                if (it.filter(event)) {
                    launch {
                        it.handler.handleEvent(event)
                    }
                }
            }
        }
        Events.lastEmittedEvent = event
    }

    fun emitAsync() {
        Events.launchAsync { emit() }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(Event::class.java)
    }
}

class RaceStatusEvent(val trackCode: String, val flagStatus: String) :Event() {

    override fun toString(): String {
        return "RaceStatusEvent : $flagStatus ($trackCode)"
    }
}

class LapCompletedEvent(
    val trackCode: String,
    val carNumber: String,
    val lapCount: Int,
    val position: Int,
    val positionInClass: Int,
    val ahead: String?,
    val gap: String,
    val gapToFront: Double,
    val gapToFrontDelta: Double,
    val lastLapTime: Double,
    val flagStatus: String) : Event(debounce = true) {

    override fun toString(): String {
        return "LapCompletedEvent : $carNumber lap=$lapCount position=$position/$positionInClass ahead=$ahead by $gap"
    }

    override fun equals(other: Any?): Boolean {
        return other is LapCompletedEvent &&
                this.trackCode == other.trackCode &&
                this.carNumber == other.carNumber &&
                this.lapCount == other.lapCount
    }
}

class CarConnectedEvent(
    val trackCode: String,
    val carNumber: String,
) : Event() {

    override fun toString(): String {
        return "CarConnectedEvent : $carNumber ($trackCode)"
    }
}

class RaceDisconnectEvent(
    val trackCode: String
) : Event() {

    override fun toString(): String {
        return "RaceDisconnectedEvent : $trackCode"
    }
}

class CarTelemetryEvent(
    val trackCode: String,
    val carNumber: String,
    val lapCount: Int,
    val lastLapTimeSec: Float,
    val coolantTemp: Int,
    val fuelRemainingPercent: Int,
    val extraSensors: Map<String, Int> = emptyMap()
) : Event() {

    override fun toString(): String {
        return "CarTelemetryEvent : $carNumber ($trackCode) coolant=$coolantTemp"
    }

    override fun equals(other: Any?): Boolean {
        return other is CarTelemetryEvent &&
                this.trackCode == other.trackCode &&
                this.carNumber == other.carNumber &&
                this.lapCount == other.lapCount
    }
}

class DriverMessageEvent(
    val trackCode: String,
    val carNumber: String,
    val message: String
): Event() {

    override fun toString(): String {
        return "DriverMessageEvent : $carNumber ($trackCode)"
    }

    override fun equals(other: Any?): Boolean {
        return other is DriverMessageEvent &&
                this.trackCode == other.trackCode &&
                this.carNumber == other.carNumber &&
                this.message == other.message
    }
}

class GpsPositionEvent(
    val trackCode: String,
    val carNumber: String,
    val position: LemonPi.GpsPosition
): Event() {

    override fun toString(): String {
        return "GpsEvent : $carNumber ($trackCode)"
    }
}

class CarPittingEvent(
    val trackCode: String,
    val carNumber: String
): Event() {

    override fun toString(): String {
        return "PittingEvent : $carNumber ($trackCode)"
    }
}

class CarLeavingPitEvent(
    val trackCode: String,
    val carNumber: String
): Event() {

    override fun toString(): String {
        return "PitExitEvent : $carNumber ($trackCode)"
    }
}

class NewDeviceRegisteredEvent(
    val deviceId: String,
    val trackCode: String,
    val carNumber: String
): Event() {

    override fun toString(): String {
        return "NewDeviceRegisteredEvent : device=$deviceId track=$trackCode car=$carNumber"
    }
}

class NewEmailAddressAddedEvent(
    val email: String,
    val carNumber: String
): Event() {

    override fun toString(): String {
        return "NewEmailAddressAddedEvent : email=$email car=$carNumber"
    }
}
