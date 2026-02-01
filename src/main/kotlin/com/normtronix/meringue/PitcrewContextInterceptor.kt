package com.normtronix.meringue

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.kotlin.CoroutineContextServerInterceptor
import kotlinx.coroutines.asContextElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class PitcrewContext(
    val deviceIds: List<String>,
    val teamCode: String,
    val emailAddress: String,)

@Component
class PitcrewContextInterceptor : CoroutineContextServerInterceptor() {

    private val unauthenticatedMethods = setOf("ping", "auth")

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

    override fun coroutineContext(call: ServerCall<*, *>, headers: Metadata): CoroutineContext {
        pitcrewContext.remove()
        if (!unauthenticatedMethods.contains(call.methodDescriptor?.bareMethodName)) {
            val bearerToken = getBearerToken(headers)
            if (bearerToken == null) {
                log.warn("missing bearer token for method {}", call.methodDescriptor?.bareMethodName)
                return EmptyCoroutineContext
            }
            try {
                val claims = JwtHelper.decodeToken(bearerToken, jwtSecret)
                pitcrewContext.set(PitcrewContext(claims.deviceIds, claims.teamCode, claims.email))
                log.info("bearer token verification successful")
                return pitcrewContext.asContextElement()
            } catch (e: Exception) {
                log.warn("JWT verification failed: {}", e.message)
                return EmptyCoroutineContext
            }
        }
        return EmptyCoroutineContext
    }

    private fun getBearerToken(headers: Metadata?): String? {
        val authHeader: String? =
            headers?.get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER))
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null
        }
        return authHeader.substring(7)
    }

    companion object {
        val pitcrewContext: ThreadLocal<PitcrewContext> = ThreadLocal()
        val log: Logger = LoggerFactory.getLogger(PitcrewSecurityInterceptor::class.java)
    }
}
