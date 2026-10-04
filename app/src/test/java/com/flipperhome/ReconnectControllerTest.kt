package com.flipperhome

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReconnectControllerTest {
    @Test fun repeatedOpenEventsStartOnlyOnePendingAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val attempts = mutableListOf<String>()
        val controller = ReconnectController(this,{ "last-verified-device" },{ true },{ false },{ address -> attempts.add(address); started.complete(Unit); finish.await() },{ fail(it.message) })
        controller.onOpen(); controller.onOpen(); started.await()
        assertEquals(listOf("last-verified-device"),attempts)
        finish.complete(Unit)
        Unit
    }
    @Test fun openingWhileConnectedOrConnectingDoesNotReplaceAnExistingLink() = runBlocking {
        var connected = true; var connecting = false; var attempts = 0
        val controller = ReconnectController(this,{ "device" },{ true },{ connected || connecting },{ attempts++ },{ fail(it.message) })
        controller.onOpen(); connected = false; connecting = true; controller.onOpen(); yield()
        assertEquals(0,attempts)
    }
    @Test fun missingTargetDeniedPermissionOrBluetoothOffDoesNotAttemptConnection() = runBlocking {
        var target: String? = null; var available = true; var attempts = 0
        val controller = ReconnectController(this,{ target },{ available },{ false },{ attempts++ },{ fail(it.message) })
        controller.onOpen(); target = "device"; available = false; controller.onOpen(); yield()
        assertEquals(0,attempts)
    }
    @Test fun manualDisconnectCancelsAttemptAndSuppressesReconnectionOnNextOpen() = runBlocking {
        var target: String? = "device"; var attempts = 0; var failures = 0
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val controller = ReconnectController(this,{ target },{ true },{ false },{
            attempts++; started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        },{ failures++ })
        controller.onOpen(); started.await(); target = null; controller.cancel(); cancelled.await(); controller.onOpen(); yield()
        assertEquals(1,attempts); assertEquals(0,failures)
    }
    @Test fun aFailedAttemptCanRetryOnNextOpenWithoutBackgroundRetryLoops() = runBlocking {
        var attempts = 0; val failures = mutableListOf<Exception>()
        val controller = ReconnectController(this,{ "device" },{ true },{ false },{ attempts++; error("Not nearby") },{ failures.add(it) })
        controller.onOpen(); yield(); yield()
        assertEquals(1,attempts); assertEquals(1,failures.size)
        controller.onOpen(); yield()
        assertEquals(2,attempts)
    }
    @Test fun discoveryTimeoutIsReportedButExplicitCancellationIsNotAnError() = runBlocking {
        val failure = CompletableDeferred<Exception>()
        val controller = ReconnectController(this,{ "device" },{ true },{ false },{ withTimeout(1) { awaitCancellation() } },{ failure.complete(it) })
        controller.onOpen()
        assertTrue(failure.await() is TimeoutCancellationException)
    }
    @Test fun manuallySelectingANewDeviceCancelsTheOldAttempt() = runBlocking {
        var target = "old-device"; val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val attempts = mutableListOf<String>()
        val controller = ReconnectController(this,{ target },{ true },{ false },{ address ->
            attempts.add(address)
            if(address == "old-device") { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        },{ fail(it.message) })
        controller.onOpen(); started.await(); controller.cancel(); cancelled.await(); target = "new-verified-device"; controller.onOpen(); yield()
        assertEquals(listOf("old-device","new-verified-device"),attempts)
    }
}
