package com.flipperhome

import kotlinx.coroutines.Deferred

internal class UniversalSender(
    private val flipper: suspend (RemoteButton,Deferred<Unit>?,Long?,() -> Unit) -> Unit,
    private val tv: suspend (RemoteButton,Deferred<Unit>?,Long?,() -> Unit) -> Unit,
) {
    suspend fun transmit(button: RemoteButton,release: Deferred<Unit>? = null,duration: Long? = null,started: () -> Unit = {}) {
        if(button.isTv) tv(button,release,duration,started) else flipper(button,release,duration,started)
    }
}
