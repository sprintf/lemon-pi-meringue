package com.normtronix.meringue

import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.kotlin.CoroutineContextServerInterceptor
import kotlinx.coroutines.asContextElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Component
class ContextInterceptor() : CoroutineContextServerInterceptor() {

    constructor(trackMetaDataLoader: TrackMetaDataLoader) : this() {
        this.trackMetaData = trackMetaDataLoader
    }

    @Autowired
    lateinit var trackMetaData: TrackMetaDataLoader

    override fun coroutineContext(call: ServerCall<*, *>, headers: Metadata): CoroutineContext {
        // wipe out any thread local associated with the requestor
        requestor.remove()
        val authHeader: String? =
            headers[Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)]
        log.debug("got auth : $authHeader")
        val remoteIpAddress = call.attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) as InetSocketAddress?
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return EmptyCoroutineContext
        }
        try {
            val decoded = String(Base64.getDecoder().decode(authHeader.substring(6)))
            val requestDetails = buildContext(
                decoded,
                trackMetaData,
                remoteIpAddress?.address.toString()
            )
            if (requestDetails != null) {
                requestor.set(requestDetails)
                return requestor.asContextElement()
            }
        } catch (e: Exception) {
            log.warn("unexpected exception", e)
        }
        log.info("Auth decoding failed for '$authHeader'")
        return EmptyCoroutineContext
    }

    fun buildContext(authToken: String,
                     trackMetaDataLoader: TrackMetaDataLoader,
                     remoteIpAddr: String): RequestDetails? {
        log.debug("processing $authToken coming from $remoteIpAddr")
        if (!authToken.contains(':')) {
            return null
        }
        if (!authToken.contains('/')) {
            return null
        }
        val segments = authToken.split(":")
        val firstSegment = segments[0].split("/")
        if (firstSegment.size != 2) {
            return null
        }
        if (!trackMetaDataLoader.isValidTrackCode(firstSegment[0])) {
            return null
        }
        return RequestDetails(firstSegment[0], firstSegment[1], segments[1], remoteIpAddr)
    }


    companion object {
        var requestor: ThreadLocal<RequestDetails> = ThreadLocal()

        val log: Logger = LoggerFactory.getLogger(ContextInterceptor::class.java)
    }
}

open class RequestDetails(
    val trackCode: String,
    val carNum: String,
    val key: String,
    val remoteIpAddr: String
)
