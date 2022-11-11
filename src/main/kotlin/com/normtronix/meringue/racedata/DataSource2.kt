package com.normtronix.meringue.racedata

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.normtronix.meringue.event.*
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.*
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import com.pusher.client.util.HttpChannelAuthorizer
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.nio.charset.Charset

// TODO
// pull in whole page of data at start
// grab the control channel name too ... the one without provate
// detect when its not up yet and fail
// detect when the stream hasn't started
// detect when the stream has finished
// write our debug file of the stream
// write out standard file landing page
// implement support for "warmup" -> Green flag condition
// tidy up printlns

data class FlagRH(
    @SerializedName("flag_type")
    val flagType: String,
    val color: String
)

data class Payload(
    val name: String,
    @SerializedName("current_lap")
    val currentLap: Int,
    @SerializedName("total_laps")
    val totalLaps: Int,
    @SerializedName("total_time")
    val totalTime: String,
    @SerializedName("started_at")
    val startedAt: String,
    @SerializedName("ended_at")
    val endedAt: String,
    @SerializedName("latest_flag")
    val latestFlag : FlagRH,
    val passings: List<PassingRH>,
    @SerializedName("racer_sessions")
    val sessions: List<RacerSessionRH>,
)

data class PassingRH(
    @SerializedName("racer_session_id")
    val racerSessionId: Int,
    @SerializedName("current_lap")
    val currentLap: Int,
    @SerializedName("start_position_in_run")
    val startPositionOverall: Int,
    @SerializedName("start_position_in_class")
    val startPositionInClass: Int,
    @SerializedName("current_flag_type")
    val currentFlagType: String,
    @SerializedName("latest_lap_number")
    val latestLapNumber: Int,
    @SerializedName("position_in_run")
    val positionOverall: Int,
    @SerializedName("position_in_class")
    val positionInClass: Int,
    @SerializedName("best_lap_number")
    val bestLapNumber: Int,
    @SerializedName("best_lap_time")
    val bestLapTime: String,
    @SerializedName("best_lap_time_seconds")
    val bestLapTimeSeconds: Double,
    @SerializedName("total_seconds")
    val totalSeconds: Double,
    @SerializedName("last_lap_time_seconds")
    val lastLapTimeSeconds: Double,
    val timestamp: Double,
    )

data class RacerSessionRH (
    @SerializedName("racer_session_id")
    val racerSessionId: Int,
    val name: String,
    @SerializedName("racer_class")
    val racerClass: String,
    @SerializedName("racer_number")
    val number : String,
    @SerializedName("start_position")
    val startPosition: Int,
    )


data class PayloadWrapper(
    val event: String,
    val data: String
)

open class DataSource2(val raceId: String) : EventHandler, RaceDataSource {

    val provider = "racehero.io"
    val fields: MutableMap<String, String> = mutableMapOf()
    var stopped = false
    var pusherHandle: Pusher? = null
    override var logRaceData = false

    /* https://racehero.io/events
      <a class="list-group-item" href="/events/2022-pitt-race-lo206-race-5">
        search for /events/2022-pitt-race-lo206-race-5

     */

    data class PusherAttributes(
        val backend: String,
        val token: String,
        val channel: String,
        val authToken: String,
        val cookie: String,
        val jsonPathURL: String,
        val externalId: String
    )

    override fun connect() : PusherAttributes {
        //    push_service_backend: 'pusher',
        //    push_service_token: 'a7e468c845030a08a736',
        //    push_service_channel: 'private-event-568-9999999999-20180727-run',
        //    form_authenticity_token: 'Nqb8-Wfl1crxszlB-RFWw1MKml3xXtMGwUTu67DAhSNFteB5DZ8LVKcYbFWL1iwqmAcEfv-N_fxabpZ1_c1b5A',
        // json_path_for_run: '/events/live-timing-demo-resets-every-20-minutes/passings/1073750179.json',
        // run_external_id: '1073750179',

        log.info("loading race index data")
        val backendRE = Regex("push_service_backend: '(.*?)'", RegexOption.MULTILINE)
        val tokenRE = Regex("push_service_token: '(.*?)'", RegexOption.MULTILINE)
        val channelRE = Regex("push_service_channel: '(.*?)'", RegexOption.MULTILINE)
        val authTokenRE = Regex("form_authenticity_token: '(.*?)'", RegexOption.MULTILINE)
        val jsonPathRE = Regex("json_path_for_run: '(.*?)'", RegexOption.MULTILINE)
        val externalIdRE = Regex("run_external_id: '(.*?)'", RegexOption.MULTILINE)
        val urlConnection = URL("https://racehero.io/events/$raceId").openConnection()
        urlConnection.doOutput
        urlConnection.connect()
        val cookie = urlConnection.getHeaderField("set-cookie")
        val raceListHtml = String(urlConnection.getInputStream().readAllBytes(), Charset.forName("UTF-8"))
        val backendMatch = backendRE.find(raceListHtml)
        val tokenMatch = tokenRE.find(raceListHtml, backendMatch?.range?.last?:0)
        val channelMatch = channelRE.find(raceListHtml, backendMatch?.range?.last?:0)
        val authTokenMatch = authTokenRE.find(raceListHtml, backendMatch?.range?.last?:0)
        val jsonPathMatch = jsonPathRE.find(raceListHtml, backendMatch?.range?.last?:0)
        val externalIdMatch = externalIdRE.find(raceListHtml, backendMatch?.range?.last?:0)
        if (backendMatch?.groups?.size != 2) {
            throw Exception("can't find backend in $raceListHtml")
        }
        if (tokenMatch?.groups?.size != 2) {
            throw Exception()
        }
        if (channelMatch?.groups?.size != 2) {
            throw Exception()
        }
        if (authTokenMatch?.groups?.size != 2) {
            throw Exception()
        }
        if (jsonPathMatch?.groups?.size != 2) {
            throw Exception()
        }
        if (externalIdMatch?.groups?.size != 2) {
            throw Exception()
        }
        return PusherAttributes(
            backendMatch.groupValues[1],
            tokenMatch.groupValues[1],
            channelMatch.groupValues[1],
            authTokenMatch.groupValues[1],
            cookie,
            jsonPathMatch.groupValues[1],
            externalIdMatch.groupValues[1]
        )
    }

    class MyAuthorizer(endpoint: String) : HttpChannelAuthorizer(endpoint) {
        override fun authorize(channelName: String?, socketId: String?): String {
            return super.authorize(channelName, socketId)
        }
    }

    override suspend fun stream(context: Any, baseHandler: BaseDataSourceHandler) {
        val handler = baseHandler as DataSource2Handler
        val fields = context as PusherAttributes
        // first read the json page
        log.info("reading json data page")
        val summaryHtml = URL("https://racehero.io/${fields.jsonPathURL}").readText()
        val summary = Gson().fromJson(summaryHtml, Payload::class.java)
        handler.handlePayload(summary)
        log.info("initialized race leaderboard")

        val authorizer = MyAuthorizer(
            "https://racehero.io/pusher_auth"
        )
        log.debug("csrf token = ${fields.authToken}")
        authorizer.setHeaders(mapOf(
            "x-csrf-token" to fields.authToken,
            "cookie" to fields.cookie
        ))
        val options = PusherOptions()
            .setChannelAuthorizer(authorizer)
            .setUseTLS(true)
          //  .setCluster(fields.backend)

        var pusher = Pusher(fields.token, options)
        pusherHandle = pusher
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                log.debug("State changed to ${change.currentState}")
                if (change.currentState == ConnectionState.DISCONNECTED) {
                    log.info("disconnected from pusher")
                    pusherHandle = null
                }
                if (change.currentState == ConnectionState.CONNECTED) {
                    pusher.subscribePrivate(fields.channel, CustomEventListener(handler, pusher), "payload");
                }
            }

            override fun onError(message: String?, code: String?, e: Exception?) {
                log.warn("Problem connecting to pusher", e)
            }
        }, ConnectionState.ALL)


        val publicChannelName = fields.channel.replace("private-", "").replace("-run", "")
        log.info("public channel is $publicChannelName")
        val channel: Channel = pusher.subscribe(publicChannelName)

        log.info("binding outer channel")
        channel.bindGlobal({
                event -> log.info("Outer Channel Received event: $event")
        })
//        channel.bind("results", SubscriptionEventListener {
//                pusher.disconnect()
//            })
//        channel.bind("live_run_updated", SubscriptionEventListener {
//                event -> log.info("Received live_run_updated with data: $event")
//        })
//        channel.bind("alert_created", SubscriptionEventListener {
//                event -> log.info("Received alert with data: $event")
//        })

        while (true) {
            delay(1000)
            if (pusherHandle == null) {
                log.info("finishing processing thread")
                break
            }
        }
    }

    class CustomEventListener(val handler: DataSource2Handler, val pusher: Pusher) : PrivateChannelEventListener {
        override fun onAuthenticationFailure(message: String, e: Exception) {
            log.warn("Pusher Authentication failure due to $message", e)
        }

        override fun onEvent(event: PusherEvent?) {
            log.debug("onEvent called with $event")
            val wrapper = Gson().fromJson(event?.toJson(), PayloadWrapper::class.java)

            val rawJson = Gson().fromJson(wrapper.data, JsonObject::class.java)
            val payloadStr = rawJson.get("payload").toString()
            val payload = Gson().fromJson(payloadStr, Payload::class.java)

            GlobalScope.async {
                handler.handlePayload(payload)
            }

            // at the end of a stream we get a message saying that the results have been posted
            if (rawJson.has("event") && rawJson.get("event").toString() == "\"results\"") {
                log.info("received race results, terminating stream")
                pusher.disconnect()
                return
            }
        }

        override fun onSubscriptionSucceeded(channelName: String?) {
            log.info("Subscription succeeded")
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(DataSource2::class.java)
    }

    override suspend fun handleEvent(e: Event) {
        if (e is RaceDisconnectEvent) {
            stopped = true
            pusherHandle?.disconnect()
        }
    }

}

//fun main() {
//    val ds = DataSource2("live-timing-demo-resets-every-20-minutes")
//    var handle = ds.connect()
//    val jobId = GlobalScope.launch(newSingleThreadContext("thread-test1")) {
//        ds.stream(handle, DataSource2Handler(RaceOrder(), "test1", emptySet()))
//        println("finished")
//    }
//    while(true) {
//        Thread.sleep(1000)
//        if (jobId.isCompleted) {
//            break
//        }
//    }
//
//}
