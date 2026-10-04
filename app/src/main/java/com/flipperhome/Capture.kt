package com.flipperhome

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import java.math.BigDecimal
import java.util.UUID

enum class RadioPreset(val label: String, val fileName: String) {
    AM650("AM650", "FuriHalSubGhzPresetOok650Async"),
    AM270("AM270", "FuriHalSubGhzPresetOok270Async"),
    FM238("FM238", "FuriHalSubGhzPreset2FSKDev238Async"),
    FM476("FM476", "FuriHalSubGhzPreset2FSKDev476Async")
}

data class RadioProfile(val frequency: Int = 433920000, val preset: RadioPreset = RadioPreset.AM650) {
    val mhz: String get() = BigDecimal(frequency).movePointLeft(6).stripTrailingZeros().toPlainString()
    companion object {
        fun validFrequency(hz: Int) = hz in 281000000..361000000 || hz in 378000000..481000000 || hz in 749000000..962000000
        fun fromMHz(text: String, preset: RadioPreset): RadioProfile {
            val hz = try { BigDecimal(text.trim()).movePointRight(6).intValueExact() }
            catch (_: Exception) { error("Enter a frequency in MHz, such as 433.92") }
            require(validFrequency(hz)) { "Choose 281–361, 378–481, or 749–962 MHz" }
            return RadioProfile(hz, preset)
        }
        fun fromFile(text: String): RadioProfile {
            require(text.contains("Filetype: Flipper SubGhz Key File")) { "Choose a decoded Sub-GHz signal" }
            fun field(name: String) = text.lineSequence().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
            val hz = requireNotNull(field("Frequency")?.toIntOrNull()) { "Signal has no frequency" }
            require(validFrequency(hz)) { "Signal uses an unsupported frequency" }
            val preset = RadioPreset.entries.firstOrNull { it.fileName == field("Preset") }
                ?: error("This signal uses a custom preset. Choose a supported preset manually.")
            return RadioProfile(hz, preset)
        }
    }
}

data class CaptureSpec(val kind: RemoteKind, val profile: RadioProfile = RadioProfile(), val token: String = UUID.randomUUID().toString()) {
    init {
        require(token.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        require(RadioProfile.validFrequency(profile.frequency))
    }
    val path: String get() = "${kind.folder}/FlipperHome/$token${kind.extension}"
    fun command() = "FH1 ARM $token ${if (kind == RemoteKind.INFRARED) "IR" else "RF"} ${profile.frequency} ${profile.preset.ordinal}".toByteArray(Charsets.UTF_8)
}

data class CapturedSignal(val path: String, val protocol: String, val kind: RemoteKind) {
    fun button(room: String, device: String, name: String) = RemoteButton(room = room, device = device.trim(), name = name.trim(), path = path, signal = if (kind == RemoteKind.INFRARED) "Captured" else "")
}

internal data class CaptureEvent(val type: String, val detail: String) {
    companion object {
        fun parse(bytes: ByteArray, spec: CaptureSpec): CaptureEvent? {
            if(bytes.size > 512) return null
            val fields = bytes.toString(Charsets.UTF_8).split('\t')
            if(fields.size != 5 || fields[0] != "FH1" || fields[1] != spec.token || fields[3] != spec.path) return null
            if(fields[2] !in setOf("READY", "CAPTURED", "ERROR")) return null
            return CaptureEvent(fields[2], fields[4])
        }
    }
}

/** Keeps the capture app alive only while learning. Playback uses the existing tested apps. */
internal object CaptureSession {
    suspend fun capture(
        appPath: String, spec: CaptureSpec, events: ReceiveChannel<ByteArray>,
        send: suspend (Int, ByteArray) -> Unit, started: Deferred<Unit>, closed: Deferred<Unit>,
        readFile: suspend (String) -> String, onReady: () -> Unit,
    ): CapturedSignal {
        var launched = false
        var failure: Throwable? = null
        try {
            // Finish startup acknowledgement even if Cancel is tapped, so cleanup owns the app.
            withContext(NonCancellable) {
                withTimeout(15000) { send(16, Protocol.string(1, appPath) + Protocol.string(2, "RPC")) }
                launched = true
            }
            withTimeout(10000) { started.await() }
            send(65, Protocol.bytes(1, spec.command()))
            val result = withTimeout(40000) {
                var ready = false
                while(true) {
                    val raw = select<ByteArray> {
                        events.onReceive { it }
                        closed.onAwait { error("Capture stopped on Flipper. Try again from its home screen.") }
                    }
                    val event = CaptureEvent.parse(raw, spec) ?: continue
                    when(event.type) {
                        "READY" -> { if(!ready) onReady(); ready = true }
                        "ERROR" -> error(event.detail.ifBlank { "Flipper could not save this signal. Check its SD card." })
                        "CAPTURED" -> {
                            check(ready) { "Capture arrived before the receiver was ready" }
                            return@withTimeout CapturedSignal(spec.path, event.detail, spec.kind)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("No capture")
            }
            val text = readFile(result.path)
            if(spec.kind == RemoteKind.SUB_GHZ) {
                val profile = RadioProfile.fromFile(text)
                check(kotlin.math.abs(profile.frequency.toLong() - spec.profile.frequency) < 10000 && profile.preset == spec.profile.preset) { "Saved signal has unexpected radio settings" }
                check(text.lineSequence().any { it.startsWith("Protocol:") } && text.lineSequence().any { it.startsWith("Key:") }) { "Saved signal is incomplete" }
            } else check(text.contains("Filetype: IR signals file") && text.lineSequence().any { it.trim() == "name: Captured" } && text.contains("type:")) { "Saved infrared signal is incomplete" }
            return result
        } catch(e: TimeoutCancellationException) {
            val error = IllegalStateException("No signal captured. Check the frequency and press the remote again.", e)
            failure = error; throw error
        } catch(e: Throwable) { failure = e; throw e }
        finally {
            if(launched && !closed.isCompleted) withContext(NonCancellable) {
                try {
                    withTimeout(10000) { send(47, byteArrayOf()); closed.await() }
                } catch(e: Throwable) { if(failure != null) failure.addSuppressed(e) else throw e }
            }
        }
    }
}

class FlipperRpcException(val code: Long) : IllegalStateException(
    when(code) {
        5L -> "Flipper's SD card is not ready"
        16L -> "Flipper could not start the capture app. It was built for Momentum mntm-dev[18-08-2026], API 87.1."
        17L -> "Another app is open on Flipper. Return to its home screen and try again."
        else -> "Flipper RPC error $code"
    }
)
