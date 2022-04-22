package com.normtronix.meringue.event

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
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
    }

    data class HandlerAndFilter(val handler: EventHandler, val filter: (Event) -> Boolean)
}

open class Event {

    suspend fun emit() {
        log.info("emitting $this")
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
    }

    fun emitAsync() {
        GlobalScope.async { emit() }
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
    val lastLapTime: Double,
    val flagStatus: String) : Event() {

    override fun toString(): String {
        return "LapCompletedEvent : $carNumber lap=$lapCount position=$position/$positionInClass ahead=$ahead by $gap"
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
    val coolantTemp: Int,
    val fuelRemainingPercent: Int
) : Event() {

    override fun toString(): String {
        return "CarTelemetryEvent : $carNumber ($trackCode) coolant=$coolantTemp"
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
}
