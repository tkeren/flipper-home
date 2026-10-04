package com.flipperhome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RemoteSenderTest {
    private fun light(path: String = "/ext/subghz/LivingRoom_On.sub", holdMs: Long = 100) =
        RemoteButton(room = "room", device = "Lights", name = "On", path = path, signal = "", holdMs = holdMs)

    @Test fun savedFileTypesAreInferredWithoutChangingExistingButtons() {
        assertEquals(RemoteKind.INFRARED, RemoteKind.forFile("/ext/infrared/TV.ir"))
        assertEquals(RemoteKind.SUB_GHZ, RemoteKind.forFile("/ext/subghz/Lights/On.sub"))
        assertEquals(500L, RemoteButton(room = "room", device = "TV", name = "Power", path = "/ext/infrared/TV.ir", signal = "Power").holdMs)
    }

    @Test fun browserAndSenderRejectEscapingPathsAndWrongFileTypes() {
        for (path in listOf("/ext/subghz/../infrared/TV.sub", "/ext/subghzBackup/On.sub", "/ext/subghz/On.ir", "/ext/infrared/On.sub", "/ext/subghz//On.sub", "/ext/subghz/./On.sub", "/ext/subghz/.sub", "/ext/subghz/On\\Off.sub")) {
            assertNull(path, RemoteKind.forFile(path))
        }
        assertTrue(RemoteKind.SUB_GHZ.containsDirectory("/ext/subghz/Lights"))
        assertFalse(RemoteKind.SUB_GHZ.containsDirectory("/ext/subghz/../nfc"))
    }

    @Test fun subGhzWaitsForStartupThenLoadsPressesReleasesAndExits() = runBlocking {
        val events = mutableListOf<String>()
        RemoteSender.transmit(light(), send = { tag, bytes ->
            events.add("rpc:$tag")
            if (tag == 16) {
                assertEquals("Sub-GHz", Protocol.fields(bytes).first { it.tag == 1 }.data.toString(Charsets.UTF_8))
                assertEquals("RPC", Protocol.fields(bytes).first { it.tag == 2 }.data.toString(Charsets.UTF_8))
            }
            if (tag == 48) assertEquals("/ext/subghz/LivingRoom_On.sub", Protocol.fields(bytes).single().data.toString(Charsets.UTF_8))
            if (tag in listOf(49, 50, 47)) assertTrue(bytes.isEmpty())
        }, awaitStarted = { events.add("started") }, awaitClosed = { events.add("closed") })
        assertEquals(listOf("rpc:16", "started", "rpc:48", "rpc:49", "rpc:50", "rpc:47", "closed"), events)
    }

    @Test fun infraredStillUsesTheNamedSignalCommand() = runBlocking {
        val tags = mutableListOf<Int>()
        val tv = light("/ext/infrared/TV.ir").copy(signal = "Power")
        RemoteSender.transmit(tv, { tag, bytes ->
            tags.add(tag)
            if (tag == 16) assertEquals("Infrared", Protocol.fields(bytes).first { it.tag == 1 }.data.toString(Charsets.UTF_8))
            if (tag == 75) assertEquals("Power", Protocol.fields(bytes).single().data.toString(Charsets.UTF_8))
        }, {}, {})
        assertEquals(listOf(16, 48, 75, 47), tags)
    }

    @Test fun cancellingDuringTransmissionStillReleasesAndExits() = runBlocking {
        val tags = mutableListOf<Int>(); val pressed = CompletableDeferred<Unit>()
        val job = launch {
            RemoteSender.transmit(light(holdMs = 10000), { tag, _ ->
                tags.add(tag)
                if (tag == 49) pressed.complete(Unit)
            }, {}, {})
        }
        pressed.await()
        job.cancelAndJoin()
        assertEquals(listOf(16, 48, 49, 50, 47), tags)
    }

    @Test fun lostPressAcknowledgementStillAttemptsReleaseAndExit() = runBlocking {
        val tags = mutableListOf<Int>(); val original = IllegalStateException("Press timed out")
        try {
            RemoteSender.transmit(light(), { tag, _ -> tags.add(tag); if (tag == 49) throw original }, {}, {})
            fail("Expected send failure")
        } catch (e: IllegalStateException) { assertSame(original, e) }
        assertEquals(listOf(16, 48, 49, 50, 47), tags)
    }

    @Test fun failedLoadExitsWithoutStartingTransmission() = runBlocking {
        val tags = mutableListOf<Int>()
        try {
            RemoteSender.transmit(light(), { tag, _ -> tags.add(tag); if (tag == 48) error("Cannot parse file") }, {}, {})
            fail("Expected file load failure")
        } catch (e: IllegalStateException) { assertEquals("Cannot parse file", e.message) }
        assertEquals(listOf(16, 48, 47), tags)
    }

    @Test fun cleanupErrorsDoNotHideTheOriginalSendFailure() = runBlocking {
        val original = IllegalStateException("Press failed")
        val tags = mutableListOf<Int>()
        try {
            RemoteSender.transmit(light(), { tag, _ ->
                tags.add(tag)
                if (tag == 49) throw original
                if (tag in listOf(50, 47)) error("Cleanup failed $tag")
            }, {}, {})
            fail("Expected send failure")
        } catch (e: IllegalStateException) {
            assertSame(original, e)
            assertEquals(2, e.suppressed.size)
        }
        assertEquals(listOf(16, 48, 49, 50, 47), tags)
    }

    @Test fun invalidDurationIsRejectedBeforeOpeningAnApp() = runBlocking {
        val tags = mutableListOf<Int>()
        try {
            RemoteSender.transmit(light(holdMs = 0), { tag, _ -> tags.add(tag) }, {}, {})
            fail("Expected invalid duration")
        } catch (_: IllegalArgumentException) { assertTrue(tags.isEmpty()) }
    }

    @Test fun heldSubGhzContinuesPastTapDurationUntilFingerRelease() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>(); val pressed = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light(), { tag, _ -> tags.add(tag) }, {}, {}, release) { pressed.complete(Unit) } }
        pressed.await()
        delay(150) // Normal tap duration is 100 ms for this fixture.
        assertTrue(job.isActive); assertEquals(listOf(16,48,49),tags)
        release.complete(Unit); job.join()
        assertEquals(listOf(16,48,49,50,47),tags)
    }

    @Test fun heldInfraredUsesNamedPressAndExplicitRelease() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>(); val pressed = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light("/ext/infrared/TV.ir").copy(signal = "VolumeUp"), { tag, bytes ->
            tags.add(tag)
            if(tag == 49) assertEquals("VolumeUp",Protocol.fields(bytes).single().data.toString(Charsets.UTF_8))
        }, {}, {}, release) { pressed.complete(Unit) } }
        pressed.await(); assertEquals(listOf(16,48,49),tags)
        release.complete(Unit); job.join()
        assertEquals(listOf(16,48,49,50,47),tags)
    }

    @Test fun fingerReleasedBeforeStartupDoesNotSendAnything() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>().apply { complete(Unit) }
        RemoteSender.transmit(light(), { tag, _ -> tags.add(tag) }, {}, {}, release)
        assertTrue(tags.isEmpty())
    }

    @Test fun fingerReleasedWhileStartingDoesNotSendALateCommand() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>(); val ready = CompletableDeferred<Unit>(); val starting = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light(), { tag, _ -> tags.add(tag) }, { starting.complete(Unit); ready.await() }, {}, release) }
        starting.await(); release.complete(Unit); ready.complete(Unit); job.join()
        assertEquals(listOf(16,47),tags)
    }

    @Test fun fingerReleasedDuringFileLoadExitsWithoutPressing() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>()
        RemoteSender.transmit(light(), { tag, _ -> tags.add(tag); if(tag == 48) release.complete(Unit) }, {}, {}, release)
        assertEquals(listOf(16,48,47),tags)
    }

    @Test fun interruptedHoldReleasesAndExitsWithoutWaitingForFinger() = runBlocking {
        val tags = mutableListOf<Int>(); val release = CompletableDeferred<Unit>(); val pressed = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light(), { tag, _ -> tags.add(tag) }, {}, {}, release) { pressed.complete(Unit) } }
        pressed.await(); job.cancelAndJoin()
        assertEquals(listOf(16,48,49,50,47),tags)
        assertFalse(release.isCompleted)
    }

    @Test fun cancellingDuringStartupStillFinishesAcknowledgementAndClosesApp() = runBlocking {
        val tags = mutableListOf<Int>(); val starting = CompletableDeferred<Unit>(); val ack = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light(), { tag, _ -> tags.add(tag); if(tag == 16) { starting.complete(Unit); ack.await() } }, { yield() }, {}) }
        starting.await(); job.cancel(); ack.complete(Unit); job.join()
        assertEquals(listOf(16,47),tags)
    }
    @Test fun timedHoldsStartAfterPressAndUseReleaseForBothRadioTypes() = runBlocking {
        for(button in listOf(light(),light("/ext/infrared/TV.ir").copy(signal = "Dim"))) {
            val tags = mutableListOf<Int>(); var pressedAt = 0L; var releasedAt = 0L
            RemoteSender.transmit(button,{ tag, bytes ->
                tags.add(tag)
                if(tag == 49) {
                    pressedAt = System.nanoTime()
                    if(button.path.endsWith(".ir")) assertEquals("Dim",Protocol.fields(bytes).single().data.toString(Charsets.UTF_8))
                }
                if(tag == 50) releasedAt = System.nanoTime()
            },{ delay(150) },{},holdDurationMs = 200)
            assertTrue("Hold must last beyond the saved 100 ms tap",releasedAt-pressedAt >= 200_000_000)
            assertEquals(listOf(16,48,49,50,47),tags)
            assertEquals(100L,button.holdMs)
        }
    }
    @Test fun cancellingATimedHoldReleasesAndExitsBeforeItsTimerCompletes() = runBlocking {
        val tags = mutableListOf<Int>(); val pressed = CompletableDeferred<Unit>()
        val job = launch { RemoteSender.transmit(light(),{ tag, _ -> tags.add(tag) },{},{},holdDurationMs = 60000,onPressed = { pressed.complete(Unit) }) }
        pressed.await(); job.cancelAndJoin()
        assertEquals(listOf(16,48,49,50,47),tags)
    }
    @Test fun invalidTimedHoldNeverOpensTheFirmwareApp() = runBlocking {
        for(duration in listOf(0L,99L,60001L)) {
            val tags = mutableListOf<Int>()
            try { RemoteSender.transmit(light(),{ tag, _ -> tags.add(tag) },{},{},holdDurationMs = duration); fail("Expected invalid duration") }
            catch(_: IllegalArgumentException) { assertTrue(tags.isEmpty()) }
        }
    }
}
