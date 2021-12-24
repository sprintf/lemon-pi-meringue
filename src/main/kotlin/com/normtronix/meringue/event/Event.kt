package com.normtronix.meringue.event

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

interface EventHandler {

    suspend fun handleEvent(e: Event)
}

class Events {

    companion object {
        val registry: MutableMap<Any, MutableList<EventHandler>> = mutableMapOf()

        fun register(clazz: Any, handler: EventHandler) {
            if (!registry.containsKey(clazz)) {
                registry[clazz] = mutableListOf(handler)
            } else {
                registry[clazz]?.add(handler)
            }
        }
    }
}

open class Event {

    suspend fun emit() {
        log.info("emitting $this")
        val event = this
        // for each of the handlers, call them
        val handlers = Events.registry[this.javaClass]
        coroutineScope() {
            handlers?.forEach {
                launch {
                    it.handleEvent(event)
                }
            }
        }

    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(Event::class.java)
    }
}

class RaceStatusEvent(val flagStatus: String) :Event() {

    override fun toString(): String {
        return "RaceStatusEvent : $flagStatus"
    }
}

class LapCompletedEvent(
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

