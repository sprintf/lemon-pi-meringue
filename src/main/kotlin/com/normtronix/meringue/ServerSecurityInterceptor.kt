package com.normtronix.meringue

import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import org.springframework.security.authentication.BadCredentialsException

class ServerSecurityInterceptor: ServerInterceptor {

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>?,
        headers: Metadata?,
        next: ServerCallHandler<ReqT, RespT>?
    ): ServerCall.Listener<ReqT> {
        if (call?.methodDescriptor?.bareMethodName != "PingPong") {
            if (requestor.get() == null) {
                // there should have been auth provided, but it is missing
                throw BadCredentialsException("invalid or missing authtoken")
            }
        }
        if (next == null) {
            throw Exception("badly wired interceptors")
        }
        return next.startCall(call, headers)
    }
}