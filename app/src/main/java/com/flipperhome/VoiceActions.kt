package com.flipperhome

import kotlinx.coroutines.CancellationException
import java.net.URI

data class VoiceAction(val id: String, val name: String, val room: String)
data class VoiceRequest(val id: String, val actionId: String, val issuedAtMs: Long)
data class VoiceResult(val success: Boolean, val message: String = "")

fun Home.withVoice(id: String, name: String, duration: Long?): Home {
    val button = buttons.firstOrNull { it.id == id } ?: error("This signal was removed")
    require(!button.isTv && RemoteKind.forFile(button.path) != null) { "Voice control is available for learned Flipper signals" }
    val trimmed = name.trim()
    require(trimmed.length <= 80 && trimmed.none { it.isISOControl() }) { "Use a name of up to 80 characters" }
    require(trimmed.isBlank() || buttons.none { it.id != id && it.voiceName.equals(trimmed, true) }) { "Another voice command already uses this name" }
    require(duration == null || duration in 100..60000) { "Hold duration must be between 0.1 and 60 seconds" }
    return copy(buttons = buttons.map { if(it.id == id) it.copy(voiceName = trimmed, voiceHoldMs = duration.takeIf { trimmed.isNotBlank() }) else it })
}

val Home.voiceActions: List<VoiceAction> get() = buttons.filter {
    !it.isTv && it.voiceName.isNotBlank() && RemoteKind.forFile(it.path) != null
}.map { VoiceAction(it.id, it.voiceName, rooms.firstOrNull { room -> room.id == it.room }?.name.orEmpty()) }

/** Only names and IDs leave the phone. Flipper paths, RF data and durations stay local. */
internal class VoiceExecutor(
    private val home: () -> Home,
    private val connected: () -> Boolean,
    private val unavailable: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transmit: suspend (RemoteButton, Long?) -> Unit,
) {
    private val seen = LinkedHashSet<String>()
    suspend fun execute(request: VoiceRequest): VoiceResult {
        if(!request.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return VoiceResult(false, "Invalid request")
        if(!seen.add(request.id)) return VoiceResult(false, "Duplicate request")
        if(seen.size > 256) seen.remove(seen.first())
        val now = clock()
        if(request.issuedAtMs !in (now-15000)..(now+5000)) return VoiceResult(false, "Voice request expired")
        if(!connected()) return VoiceResult(false, "Flipper is disconnected")
        if(unavailable()) return VoiceResult(false, "Flipper is busy")
        val button = home().buttons.firstOrNull { it.id == request.actionId && it.voiceName.isNotBlank() && !it.isTv }
            ?: return VoiceResult(false, "Voice control is disabled for this button")
        if(RemoteKind.forFile(button.path) == null || (button.voiceHoldMs != null && button.voiceHoldMs !in 100..60000))
            return VoiceResult(false, "Check this signal in Flipper Home")
        return try { transmit(button, button.voiceHoldMs); VoiceResult(true) }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { VoiceResult(false, e.message ?: "Signal could not be sent") }
    }
}

internal fun homeAssistantSocketUrl(value: String): String {
    val uri = try { URI(value.trim().trimEnd('/')) } catch(e: Exception) { error("Enter your Home Assistant URL") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Use your HTTPS Home Assistant URL"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "Check the Home Assistant port" }
    return URI("wss", null, uri.host, uri.port, uri.path.trimEnd('/') + "/api/websocket", null, null).toString()
}
