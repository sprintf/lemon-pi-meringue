package com.normtronix.meringue

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import java.net.URL
import java.util.stream.Collectors

@Component
class TrackMetaDataLoader {

    val trackCodes = mutableSetOf<String>()
    val trackNameMap = mutableMapOf<String, String>()
    val trackLatLng = mutableMapOf<String, Pair<Double, Double>>()
    var tracks = listOf<Track>()

    init {
        loadData()
    }

    /** Reference location (midpoint of the start/finish line) for a track, or null. */
    fun getTrackLatLng(code: String): Pair<Double, Double>? = trackLatLng[code]

    fun isValidTrackCode(code: String): Boolean {
        return trackCodes.contains(code)
    }

    fun validateTrackCode(code: String): Unit {
        if (!trackCodes.contains(code)) {
            throw InvalidTrackCode()
        }
    }

    fun codeToName(code: String): String {
        return trackNameMap[code] ?: "unknown"
    }

    fun listTracks(): List<Track> {
        return tracks
    }

    private fun loadData() {
        // https://storage.googleapis.com/perplexus/public/tracks.yaml
        log.info("loading track data")
        val yaml = URL("https://storage.googleapis.com/perplexus/public/tracks.yaml").readText()
        val trackList: Tracks = Yaml(Constructor(Tracks::class.java)).load(yaml)
        trackList.tracks.stream().forEach { trackCodes.add(it.code) }
        this.tracks = trackList.tracks.stream().collect(Collectors.toList())
        trackList.tracks.stream().forEach {
            trackNameMap[it.code] = it.name
            parseMidpoint(it.start_finish_coords)?.let { ll -> trackLatLng[it.code] = ll }
        }
        log.info("finished loading track data")
    }

    /** Parse "(lat1,lng1),(lat2,lng2)" → midpoint (lat,lng). */
    private fun parseMidpoint(coords: String): Pair<Double, Double>? {
        val pts = Regex("""\((-?\d+\.?\d*),\s*(-?\d+\.?\d*)\)""").findAll(coords)
            .map { Pair(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
        if (pts.isEmpty()) return null
        return Pair(pts.map { it.first }.average(), pts.map { it.second }.average())
    }

    class Tracks {
        var tracks: MutableList<Track> = mutableListOf()
    }

    // the snake case names are needed to correctly read the yaml
    class Track {
        var name: String = ""
        var code: String = ""
        var start_finish_coords: String = ""
        var start_finish_direction: String = ""
        var pit_entry_coords: String = ""
        var pit_entry_direction: String = ""
        var pit_out_coords: String = ""
        var pit_out_direction: String = ""
        var radio_sync_list: Any = Any()
        var radio_sync_coords: String = ""
        var radio_sync_direction: String = ""
        var sectors: Any = Any()
        var hidden: Boolean = false
        var reversed: Boolean = false
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(TrackMetaDataLoader::class.java)
    }
}

class InvalidTrackCode(): Exception()
