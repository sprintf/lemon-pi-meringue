package com.normtronix.meringue

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.kotlin.CoroutineContextServerInterceptor
import kotlinx.coroutines.asContextElement
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class PitcrewContext(val deviceIds: List<String>, val teamCode: String)

@Component
class PitcrewContextInterceptor : CoroutineContextServerInterceptor() {

    private val unauthenticatedMethods = setOf("ping", "auth")

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

    override fun coroutineContext(call: ServerCall<*, *>, headers: Metadata): CoroutineContext {
        pitcrewContext.remove()
        if (!unauthenticatedMethods.contains(call.methodDescriptor?.bareMethodName)) {
            log.info("bearer token = {} ", getBearerToken(headers))
            val bearerToken = getBearerToken(headers)
                ?: throw BadCredentialsException("missing auth token")
            try {
                val verifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build()
                val decoded = verifier.verify(bearerToken)
                val deviceIds = decoded.getClaim("deviceIds").asList(String::class.java)
                val teamCode = decoded.getClaim("teamCode").asString()
                pitcrewContext.set(PitcrewContext(deviceIds, teamCode))
                log.info("bearer token verification successful")
                return pitcrewContext.asContextElement()
            } catch (e: Exception) {
                log.warn("JWT verification failed", e)
                throw BadCredentialsException("invalid auth token")
            }
        }
        pitcrewContext.remove()
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
