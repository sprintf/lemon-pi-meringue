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
    var tracks = listOf<Track>()

    init {
        loadData()
    }

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
        }
        log.info("finished loading track data")
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
        var hidden: Boolean = false
        var reversed: Boolean = false
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(TrackMetaDataLoader::class.java)
    }
}

class InvalidTrackCode(): Exception()
