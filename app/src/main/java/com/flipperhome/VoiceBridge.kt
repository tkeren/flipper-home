package com.flipperhome

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal data class VoiceStatus(val connected: Boolean = false, val message: String = "Voice control is off")

/** Owned by the application/connected-device service, so voice playback survives leaving the UI. */
internal class VoiceBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connected: StateFlow<Boolean>,
    private val manualBusy: () -> Boolean,
    private val transmit: suspend (RemoteButton, Long?) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build(),
) {
    val settings = VoiceSettings(context)
    val status = MutableStateFlow(VoiceStatus())
    val busy = MutableStateFlow(false)
    val activeName = MutableStateFlow<String?>(null)
    private val store = HomeStore(context)
    private var config = settings.load()
    private var connectionJob: Job? = null
    private var activeJob: Job? = null
    private var activeActionId: String? = null
    private var activeRequestId: String? = null
    private var socket: WebSocket? = null
    private var protocol: VoiceProtocol? = null
    private var revision = 0

    init { scope.launch { connected.collect { active -> if(active) resume() else stop() } } }

    fun refresh() {
        config = settings.load()
        stop()
        resume()
    }
    fun resume() {
        if(!config.enabled) { status.value = VoiceStatus(); return }
        if(!connected.value) { status.value = VoiceStatus(message = "Connect Flipper to enable voice control"); return }
        if(config.token.isBlank()) { status.value = VoiceStatus(message = "Connect your ${config.provider.label} again"); return }
        if(connectionJob?.isActive == true) return
        val owner = ++revision
        connectionJob = scope.launch {
            var retrySeconds = 2L
            while(isActive && owner == revision && connected.value && config.enabled) {
                status.value = VoiceStatus(message = "Connecting to ${config.provider.label}…")
                val failure = runCatching { session(owner) }.exceptionOrNull()
                if(failure is CancellationException) throw failure
                if(owner != revision || !connected.value) break
                if(failure is VoiceSetupException) { status.value = VoiceStatus(message = failure.message.orEmpty()); break }
                status.value = VoiceStatus(message = "${config.provider.label} disconnected · retrying in ${retrySeconds}s")
                delay(retrySeconds * 1000)
                retrySeconds = (retrySeconds * 2).coerceAtMost(60)
            }
        }
    }
    fun stop() {
        revision++
        connectionJob?.cancel(); connectionJob = null
        activeJob?.cancel()
        protocol = null
        socket?.cancel(); socket = null
        status.value = VoiceStatus(message = if(config.enabled) "Connect Flipper to enable voice control" else "Voice control is off")
    }
    fun cancelCommand() { activeJob?.cancel() }
    fun updateActions() {
        if(activeActionId != null && store.load().voiceActions.none { it.id == activeActionId }) activeJob?.cancel()
        val current = protocol ?: return
        if(current.ready) socket?.send(current.update(store.load().voiceActions, connected.value))
    }

    private suspend fun session(owner: Int) = coroutineScope {
        val messages = Channel<String>(32)
        val current = VoiceProtocol(config.bridgeId, "${Build.MODEL} · Flipper Home".take(80), config.provider)
        val token = config.token
        val ws = client.newWebSocket(Request.Builder().url(voiceSocketUrl(config.url, config.provider)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if(text.length > 256_000 || messages.trySend(text).isFailure) {
                    messages.close(IllegalStateException("Home Assistant message stream overflow")); webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { messages.close(IllegalStateException("Home Assistant connection failed")) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); messages.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        })
        socket = ws; protocol = current
        val executor = VoiceExecutor({ store.load() }, { connected.value }, manualBusy,
            maxDurationMs = if(config.provider == VoiceProvider.GOOGLE_HOME) 5000 else 60000,
            transmit = { button, duration ->
                val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipperHome:voice")
                wake.acquire(90000)
                try { withTimeout(75000) { transmit(button, duration) } }
                finally { if(wake.isHeld) wake.release() }
            })
        val handshake = launch { delay(12000); if(!current.ready) messages.close(IllegalStateException("Home Assistant connection timed out")) }
        val heartbeat = launch {
            while(isActive) { delay(15000); if(current.ready) ws.send(current.heartbeat(connected.value)) }
        }
        try {
            for(message in messages) {
                ensureActive()
                if(owner != revision) break
                val (reply, command) = current.receive(message, token, store.load().voiceActions)
                if(reply != null) check(ws.send(reply)) { "Home Assistant connection closed" }
                if(current.error.isNotBlank()) {
                    if(current.setupFailure) throw VoiceSetupException(current.error)
                    error(current.error)
                }
                if(current.ready) { handshake.cancel(); status.value = VoiceStatus(true, "Voice control connected") }
                if(command != null) {
                    if(command.cancel) { if(activeRequestId == command.id) activeJob?.cancel(); continue }
                    if(busy.value || manualBusy()) ws.send(current.result(command, VoiceResult(false, "Flipper is busy")))
                    else {
                        busy.value = true
                        activeActionId = command.actionId
                        activeRequestId = command.id
                        activeName.value = store.load().voiceActions.firstOrNull { it.id == command.actionId }?.name
                        activeJob = scope.launch {
                            val result = try { executor.execute(command) }
                            catch(e: CancellationException) { VoiceResult(false, "Command stopped") }
                            catch(e: Exception) { VoiceResult(false, "Signal could not be sent") }
                            finally { busy.value = false; activeName.value = null; activeJob = null; activeActionId = null; activeRequestId = null }
                            if(owner == revision) ws.send(current.result(command, result))
                        }
                    }
                }
            }
        } finally {
            handshake.cancel(); heartbeat.cancel(); ws.cancel(); messages.cancel()
            activeJob?.cancel()
            withContext(NonCancellable) { withTimeoutOrNull(3000) { activeJob?.join() } }
            if(protocol === current) { protocol = null; socket = null }
        }
    }
    private class VoiceSetupException(message: String) : Exception(message)
}
