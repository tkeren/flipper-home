package com.flipperhome

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class RemoteKind(val label: String, val folder: String, val extension: String, val appName: String) {
    INFRARED("Infrared", "/ext/infrared", ".ir", "Infrared"),
    SUB_GHZ("Sub-GHz", "/ext/subghz", ".sub", "Sub-GHz");

    fun containsDirectory(path: String): Boolean = (path == folder || path.startsWith("$folder/")) &&
        path.split('/').none { it == "." || it == ".." } && !path.contains('\\') && !path.contains('\u0000') &&
        !path.contains("//")

    fun containsFile(path: String): Boolean = path.startsWith("$folder/") && containsDirectory(path) &&
        path.endsWith(extension) && path.substringAfterLast('/').length > extension.length

    companion object {
        fun forFile(path: String): RemoteKind? = entries.firstOrNull { it.containsFile(path) }
    }
}

/** Run the firmware app lifecycle, including release/exit when a send is interrupted. */
object RemoteSender {
    suspend fun transmit(
        button: RemoteButton,
        send: suspend (Int, ByteArray) -> Unit,
        awaitStarted: suspend () -> Unit,
        awaitClosed: suspend () -> Unit,
        release: Deferred<Unit>? = null,
        holdDurationMs: Long? = null,
        onPressed: () -> Unit = {},
    ) {
        val kind = requireNotNull(RemoteKind.forFile(button.path)) { "Select a saved .ir or .sub file from the Flipper" }
        if (kind == RemoteKind.INFRARED) require(button.signal.isNotBlank()) { "Choose an infrared signal" }
        else require(button.holdMs in 100..10000) { "Button press length must be between 100 and 10000 ms" }
        require(holdDurationMs == null || holdDurationMs in 100..60000) { "Hold duration must be between 0.1 and 60 seconds" }
        var launched = false
        var failure: Throwable? = null
        try {
            if(release?.isCompleted == true) return
            currentCoroutineContext().ensureActive()
            // Finish the startup acknowledgement so cancellation can reliably own app cleanup.
            withContext(NonCancellable) {
                send(16, Protocol.string(1, kind.appName) + Protocol.string(2, "RPC"))
                launched = true
            }
            awaitStarted()
            if(release?.isCompleted == true) return
            send(48, Protocol.string(1, button.path))
            // A finger released during startup must not start a late transmission.
            if(release?.isCompleted == true) return
            if (kind == RemoteKind.INFRARED && release == null && holdDurationMs == null) {
                send(75, Protocol.string(1, button.signal))
            } else {
                // Stock and Momentum both support PRESS / RELEASE; Momentum does not use tag 75 here.
                // Release is attempted even if the press acknowledgement is lost after TX started.
                var pressFailure: Throwable? = null
                try {
                    send(49, if(kind == RemoteKind.INFRARED) Protocol.string(1, button.signal) else byteArrayOf())
                    onPressed()
                    if(release != null) release.await() else delay(holdDurationMs ?: button.holdMs)
                } catch (e: Throwable) { pressFailure = e; throw e }
                finally { cleanup(pressFailure) { send(50, byteArrayOf()) } }
            }
        } catch (e: Throwable) { failure = e; throw e }
        finally {
            if (launched) cleanup(failure) {
                send(47, byteArrayOf())
                awaitClosed()
            }
        }
    }

    private suspend fun cleanup(failure: Throwable?, block: suspend () -> Unit) {
        try { withContext(NonCancellable) { block() } }
        catch (e: Throwable) { if (failure != null) failure.addSuppressed(e) else throw e }
    }
}
