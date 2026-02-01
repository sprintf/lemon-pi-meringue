package com.normtronix.meringue.racedata

import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class RaceDataIndexItem(val raceId: String, val trackName: String, val eventName: String? = null)

@Component
class DS1RaceLister {

    private var eventList:Map<String, RaceDataIndexItem> = mapOf()

    init {
        loadData()
    }

    fun getLiveRaces(terms: List<String>): Stream<RaceDataIndexItem> {
        return eventList.values.stream()
    }

    @Scheduled(fixedDelayString = "5", timeUnit = TimeUnit.MINUTES)
    private fun loadData() {
        log.info("loading race index data")


        val raceListHtml = URL("https://www.race-monitor.com/Live").readText()
        val tmpEventList:MutableMap<String, RaceDataIndexItem> = mutableMapOf()

        val doc = Jsoup.parse(raceListHtml)
        val raceItems = doc.select("div.raceItem.clickableRace")
        raceItems.forEach { item ->
            val eventName = item.select("span.largeRaceText a").text()
            val trackName = item.select("span.smallRaceText a").text()
            val raceId = item.select("span.largeRaceText a").attr("href")
                .substringAfterLast("/")

            tmpEventList[raceId] = RaceDataIndexItem(raceId, eventName, trackName)
            log.debug("found race <${raceId}> called $eventName at $trackName")
        }

        log.info("finished loading race index data : found ${tmpEventList.size} live races ")
        eventList = tmpEventList.toMap()
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DS1RaceLister::class.java)
    }
}

