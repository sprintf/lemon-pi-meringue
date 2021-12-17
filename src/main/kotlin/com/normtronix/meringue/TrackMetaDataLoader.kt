package com.normtronix.meringue

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import java.net.URL

@Component
class TrackMetaDataLoader {

    val trackCodes = mutableSetOf<String>()

    init {
        loadData()
    }

    fun isValidTrackCode(code: String): Boolean {
        return trackCodes.contains(code)
    }

    private fun loadData() {
        // https://storage.googleapis.com/perplexus/public/tracks.yaml
        log.info("loading track data")
        val yaml = URL("https://storage.googleapis.com/perplexus/public/tracks.yaml").readText()
        val tracks: Tracks = Yaml(Constructor(Tracks::class.java)).load(yaml)
        tracks.tracks.stream().forEach { trackCodes.add(it.code) }
        log.info("finished loading track data")
    }

    class Tracks() {
        var tracks: MutableList<Track> = mutableListOf()
    }

    // the snake case names are needed to correctly read the yaml
    class Track() {
        var name: String = ""
        var code: String = ""
        var start_finish_coords: String = ""
        var start_finish_direction: String = ""
        var pit_entry_coords: String = ""
        var pit_entry_direction: String = ""
        var pit_out_coords: String = ""
        var pit_out_direction: String = ""
        var radio_sync_list: Object = Object()
        var radio_sync_coords: String = ""
        var radio_sync_direction: String = ""
        var hidden: Boolean = false
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(TrackMetaDataLoader::class.java)
    }
}