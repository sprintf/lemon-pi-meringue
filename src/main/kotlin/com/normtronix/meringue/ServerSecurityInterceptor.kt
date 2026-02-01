package com.normtronix.meringue

import com.normtronix.meringue.ContextInterceptor.Companion.requestor
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

class ServerSecurityInterceptor : ServerInterceptor {

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        if (call.methodDescriptor?.bareMethodName != "PingPong") {
            if (requestor.get() == null) {
                call.close(Status.UNAUTHENTICATED.withDescription("invalid or missing auth token"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }
        }
        return next.startCall(call, headers)
    }
}