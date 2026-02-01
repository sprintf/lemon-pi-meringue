package com.normtronix.meringue

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AdminSecurityInterceptor : ServerInterceptor {

    private val unauthenticatedMethods = setOf("auth", "ping")

    @Autowired
    lateinit var authService: AuthService

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        if (!unauthenticatedMethods.contains(call.methodDescriptor?.bareMethodName)) {
            val bearerToken = getBearerToken(headers)
            if (!authService.isTokenValid(bearerToken)) {
                call.close(Status.UNAUTHENTICATED.withDescription("invalid or missing auth token"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }
        }
        return next.startCall(call, headers)
    }

    private fun getBearerToken(headers: Metadata?): String? {
        val authHeader: String? =
            headers?.get(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER))
        log.debug("auth header : $authHeader")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null
        }
        return authHeader.substring(7)
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(AdminSecurityInterceptor::class.java)
    }
}