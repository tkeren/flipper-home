package com.flipperhome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException

/** One attempt per app opening, cancelled when the user takes over connection selection. */
internal class ReconnectController(
    private val scope: CoroutineScope,
    private val rememberedAddress: () -> String?,
    private val available: () -> Boolean,
    private val alreadyConnectingOrConnected: () -> Boolean,
    private val attempt: suspend (String) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    private var job: Job? = null
    fun onOpen() {
        if(job?.isActive == true || alreadyConnectingOrConnected() || !available()) return
        val address = rememberedAddress()?.takeIf { it.isNotBlank() } ?: return
        job = scope.launch {
            try { attempt(address) }
            catch(e: TimeoutCancellationException) { failed(e) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { failed(e) }
        }
    }
    fun cancel() { job?.cancel(); job = null }
}
