package com.normtronix.meringue.racedata

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URL

class RaceDataIndexItem(val raceId: String, val trackName: String, var eventName: String? = null)

@Component
class DS1RaceLister {

    val eventList:MutableMap<String, RaceDataIndexItem> = mutableMapOf()

    init {
        loadData()
    }

    private fun loadData() {
        // https://storage.googleapis.com/perplexus/public/tracks.yaml
        log.info("loading race index data")
        val linkRE = Regex("@ <a href=\"(.*?)\">(.*?)</a")
        val raceListHtml = URL("https://www.race-monitor.com/Live").readText()


        val matchResult = linkRE.findAll(raceListHtml)
        for (result in matchResult) {
            // println("got matchResult of ${result}")
            val link = result.groupValues[1]
            val raceId = link.split("/").let {
                it[it.size - 1].toInt().toString()
            }
            val trackName = result.groupValues[2]
            eventList[raceId] = RaceDataIndexItem(raceId, trackName)
        }

        val eventNameRE = Regex("class=\"largeRaceText\"><a href=\"(.*?)\">(.*?)</a")
        val eventMatchResult = eventNameRE.findAll(raceListHtml)
        for (result in eventMatchResult) {
            val link = result.groupValues[1]
            val raceId = link.split("/").let {
                it[it.size - 1].toInt().toString()
            }
            val eventName = result.groupValues[2]
            eventList[raceId]?.let {
                it.eventName = eventName
            }
        }

        log.info("finished loading race index data ")
        // println(eventList.values)
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DS1RaceLister::class.java)
    }
}

