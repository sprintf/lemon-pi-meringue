package com.normtronix.meringue

import com.normtronix.meringue.PitcrewContextInterceptor.Companion.pitcrewContext
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.springframework.stereotype.Component


@Component
class PitcrewSecurityInterceptor : ServerInterceptor {

    private val unauthenticatedMethods = setOf("ping", "auth")

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        if (!unauthenticatedMethods.contains(call.methodDescriptor?.bareMethodName)) {
            if (pitcrewContext.get() == null) {
                call.close(Status.UNAUTHENTICATED.withDescription("invalid or missing auth token"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }
        }
        return next.startCall(call, headers)
    }

}
