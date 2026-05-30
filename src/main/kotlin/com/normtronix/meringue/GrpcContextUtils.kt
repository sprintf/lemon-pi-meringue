package com.normtronix.meringue

import io.grpc.Context

inline fun <T> withForkedGrpcContext(block: () -> T): T {
    val forkedCtx = Context.current().fork()
    val previousCtx = forkedCtx.attach()
    return try {
        block()
    } finally {
        forkedCtx.detach(previousCtx)
    }
}
