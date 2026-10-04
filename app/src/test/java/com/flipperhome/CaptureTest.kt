package com.flipperhome

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class CaptureTest {
    private val token = "12345678-1234-1234-1234-123456789abc"
    private fun spec(kind: RemoteKind = RemoteKind.SUB_GHZ) = CaptureSpec(kind, token = token)
    private fun event(spec: CaptureSpec, type: String, detail: String = "Princeton") = "FH1\t${spec.token}\t$type\t${spec.path}\t$detail".toByteArray()
    private val rfFile = """
        Filetype: Flipper SubGhz Key File
        Version: 1
        Frequency: 433920000
        Preset: FuriHalSubGhzPresetOok650Async
        Protocol: Princeton
        Bit: 24
        Key: 00 00 00 00 00 11 22 33
        TE: 390
    """.trimIndent()

    @Test fun radioProfileReadsRealFlipperFormatAndKeepsMHzPrecision() {
        val profile = RadioProfile.fromFile(rfFile)
        assertEquals(433920000, profile.frequency)
        assertEquals("433.92", profile.mhz)
        assertEquals(profile, RadioProfile.fromMHz("433.920000", RadioPreset.AM650))
    }

    @Test fun invalidFrequencyAndCustomPresetsAreRejected() {
        for(text in listOf("NaN", "433.9200001", "999999999", "370", "0", "-433.92")) {
            try { RadioProfile.fromMHz(text, RadioPreset.AM650); fail(text) } catch(_: IllegalArgumentException) { }
            catch(_: IllegalStateException) { }
        }
        try { RadioProfile.fromFile(rfFile.replace("FuriHalSubGhzPresetOok650Async", "FuriHalSubGhzPresetCustom")); fail() }
        catch(_: IllegalStateException) { }
    }

    @Test fun commandMatchesCompanionWireContractAndOwnsItsPath() {
        val spec = spec()
        assertEquals("FH1 ARM $token RF 433920000 0", spec.command().toString(Charsets.UTF_8))
        assertEquals("/ext/subghz/FlipperHome/$token.sub", spec.path)
        assertEquals("FH1 ARM $token IR 433920000 0", spec(RemoteKind.INFRARED).command().toString(Charsets.UTF_8))
        try { CaptureSpec(RemoteKind.SUB_GHZ, token = "../../saved"); fail() } catch(_: IllegalArgumentException) { }
    }

    @Test fun eventsMustMatchTheCurrentCaptureTokenAndPath() {
        val spec = spec()
        assertEquals("CAPTURED", CaptureEvent.parse(event(spec, "CAPTURED"), spec)?.type)
        for(text in listOf("FH1\tother\tCAPTURED\t${spec.path}\tPrinceton", "FH1\t$token\tCAPTURED\t/ext/subghz/Other.sub\tPrinceton", "FH1\t$token\tUNKNOWN\t${spec.path}\tPrinceton", "FH1\t$token\tREADY", "x".repeat(513))) {
            assertNull(CaptureEvent.parse(text.toByteArray(), spec))
        }
    }

    private class Harness(val spec: CaptureSpec, val file: String) {
        val tags = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val events = Channel<ByteArray>(16)
        var ready = 0
        var fileRead = false
        var onArm: suspend () -> Unit = {}
        var onRead: suspend () -> String = { fileRead = true; file }
        var exitError: Throwable? = null
        suspend fun run() = CaptureSession.capture("/ext/apps/Tools/capture.fap", spec, events,
            send = { tag, payload ->
                tags.add(tag)
                when(tag) {
                    16 -> started.complete(Unit)
                    65 -> {
                        assertArrayEquals(spec.command(), Protocol.fields(payload).single().data)
                        onArm()
                    }
                    47 -> { exitError?.let { throw it }; closed.complete(Unit) }
                }
            }, started = started, closed = closed, readFile = { path -> assertEquals(spec.path, path); onRead() }, onReady = { ready++ })
    }

    @Test fun successfulCaptureIsReadBackAndAppExitsBeforePlayback() = runBlocking {
        val h = Harness(spec(), rfFile)
        h.onArm = {
            h.events.send("FH1\tstale\tCAPTURED\t${h.spec.path}\tPrinceton".toByteArray())
            h.events.send(event(h.spec, "READY")); h.events.send(event(h.spec, "READY"))
            h.events.send(event(h.spec, "CAPTURED"))
        }
        val result = h.run()
        assertEquals(listOf(16, 65, 47), h.tags)
        assertTrue(h.fileRead); assertEquals(1, h.ready)
        assertEquals(h.spec.path, result.button("room", "Lights", "Off").path)
        assertEquals("", result.button("room", "Lights", "Off").signal)
    }

    @Test fun savedInfraredButtonUsesTheCompanionsSignalName() = runBlocking {
        val h = Harness(spec(RemoteKind.INFRARED), "Filetype: IR signals file\nVersion: 1\nname: Captured\ntype: parsed\nprotocol: NEC\naddress: 00 00 00 00\ncommand: 01 00 00 00")
        h.onArm = { h.events.send(event(h.spec, "READY")); h.events.send(event(h.spec, "CAPTURED", "NEC")) }
        assertEquals("Captured", h.run().button("room", "TV", "Power").signal)
        assertEquals(listOf(16, 65, 47), h.tags)
    }

    @Test fun captureWithoutReadyCannotBeSaved() = runBlocking {
        val h = Harness(spec(), rfFile)
        h.onArm = { h.events.send(event(h.spec, "CAPTURED")) }
        try { h.run(); fail() } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("before")) }
        assertFalse(h.fileRead); assertEquals(listOf(16, 65, 47), h.tags)
    }

    @Test fun incompleteFileCannotBecomeARoomButton() = runBlocking {
        val h = Harness(spec(), rfFile.substringBefore("Key:"))
        h.onArm = { h.events.send(event(h.spec, "READY")); h.events.send(event(h.spec, "CAPTURED")) }
        try { h.run(); fail() } catch(e: IllegalStateException) { assertEquals("Saved signal is incomplete", e.message) }
        assertEquals(listOf(16, 65, 47), h.tags)
    }

    @Test fun cancellingAListeningCaptureStillClosesTheFlipperApp() = runBlocking {
        val h = Harness(spec(), rfFile)
        val armed = CompletableDeferred<Unit>()
        h.onArm = { h.events.send(event(h.spec, "READY")); armed.complete(Unit) }
        val job = launch { h.run() }
        armed.await(); yield(); job.cancelAndJoin()
        assertEquals(listOf(16, 65, 47), h.tags); assertTrue(h.closed.isCompleted); assertFalse(h.fileRead)
    }

    @Test fun cancellationDuringFileVerificationAlsoClosesTheApp() = runBlocking {
        val h = Harness(spec(), rfFile)
        val reading = CompletableDeferred<Unit>()
        h.onArm = { h.events.send(event(h.spec, "READY")); h.events.send(event(h.spec, "CAPTURED")) }
        h.onRead = { reading.complete(Unit); awaitCancellation() }
        val job = launch { h.run() }
        reading.await(); job.cancelAndJoin()
        assertEquals(listOf(16, 65, 47), h.tags)
    }

    @Test fun cancellingDuringStartupFinishesTheAcknowledgementAndExits() = runBlocking {
        val starting = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val tags = mutableListOf<Int>()
        val job = launch {
            CaptureSession.capture("capture.fap", spec(), Channel(1), { tag, _ ->
                tags.add(tag)
                if(tag == 16) { starting.complete(Unit); ack.await(); started.complete(Unit) }
                if(tag == 47) closed.complete(Unit)
            }, started, closed, { rfFile }, {})
        }
        starting.await(); job.cancel(); ack.complete(Unit); job.join()
        assertEquals(listOf(16, 47), tags)
    }

    @Test fun companionErrorSurvivesFailedCleanup() = runBlocking {
        val h = Harness(spec(), rfFile)
        h.onArm = { h.events.send(event(h.spec, "ERROR", "SD card write failed")) }
        h.exitError = IllegalStateException("Exit failed")
        try { h.run(); fail() } catch(e: IllegalStateException) {
            assertEquals("SD card write failed", e.message)
            assertEquals("Exit failed", e.suppressed.single().message)
        }
        assertFalse(h.fileRead)
    }

    @Test fun closingFromFlipperInterruptsWaitingImmediately() = runBlocking {
        val h = Harness(spec(), rfFile)
        h.onArm = { h.closed.complete(Unit) }
        try { h.run(); fail() } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("Capture stopped")) }
        assertEquals(listOf(16, 65), h.tags)
    }
}
