package com.normtronix.meringue.racedata

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.streams.asStream

@Component
class DS2RaceLister {

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
        /*
            <h4 class="list-group-item-heading">
                  Thunderhill Raceway Nov 12-13
                 <i class="fa fa-bullhorn text-green" title="Live Event"></i>
             </h4>
              <span class="details">NASA - NorCal<br />November 12, 2022 &mdash; Thunderhill 2M</span>
        <div class="row-text-container">
          <h4 class="list-group-item-heading">
               2022 JRRC 3 50s [22-R-57196]
            </h4>
            <span class="details">South Jersey Region, SCCA<br />October 21, 2022 &mdash; New Jersey Motorsports Park</span>
           </div>
         */
        val linkRE = Regex("<a class=\"list-group-item\" href=\"/events/(.*?)\">")

        val detailsRE = Regex("<h4 class=\"list-group-item-heading\">(.*?)</h4>.*?<span class=\"details\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        //val linkRE = Regex("<a class=\"list-group-item\" href=\"(.*?)\">.*?<div class=\"row-text-container\">.*?<h4 .*?>(.*?)</h4>.*<span class=\"details\">(.*?)</span>", RegexOption.MULTILINE)
        val raceListHtml = URL("https://racehero.io/events").readText()
        // val raceListHtml = File("/tmp/foo.html").readText()
        val tmpEventList:MutableMap<String, RaceDataIndexItem> = mutableMapOf()

        val splitDetailsRE = Regex("(.*?)<br />.*?&mdash;.(.*?)$", RegexOption.DOT_MATCHES_ALL)

        // File("/tmp/foo.html").writeText(raceListHtml)

        val matchResult = linkRE.findAll(raceListHtml)
        for (result in matchResult) {
            val event = result.groupValues[1]
            log.debug("found event $event at ending place ${result.range.last}")
            val linkResult = detailsRE.findAll(raceListHtml, result.range.first)


            linkResult.asStream().findFirst().map {
                if (it.groupValues.size == 3 && "Live Event" in it.groupValues[1]) {
                    // we get some double spaces that can cause match problems w/ target date
                    val orgAndDateAndTrack = it.groupValues[2].replace("  ", " ")
                    val title = it.groupValues[1].trim().split("\n").first()
                    log.debug("got interesting event ${orgAndDateAndTrack} + $title")
                    val fields = splitDetailsRE.findAll(orgAndDateAndTrack)
                    fields.asStream().findFirst().map {
                        val track = it.groupValues[2].trim()
                        tmpEventList[event] = RaceDataIndexItem(event, track, title)
                    }
                }
            }
        }

        eventList = tmpEventList.toMap()
        log.info("finished loading race index data. Found ${eventList.size} items ")
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DS2RaceLister::class.java)
    }
}
//
//fun main() {
//    DS2RaceLister().getLiveRaces(emptyList())
//}

