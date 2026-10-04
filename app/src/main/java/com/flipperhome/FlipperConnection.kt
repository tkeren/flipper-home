package com.flipperhome

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class FlipperHomeApplication : Application() {
    internal val connection by lazy { FlipperConnection(this) }
}

/** Application-owned transport survives Back, activity recreation and app switching. */
internal class FlipperConnection(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val link = FlipperLink(context.applicationContext,scope)
    private val prefs = context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    private var keepingAlive = false
    private var starting = false
    private var scanGeneration = 0
    private val reconnect = ReconnectController(scope,
        rememberedAddress = { if(prefs.getBoolean("autoReconnect",true)) prefs.getString("address",null) else null },
        available = ::canConnect,
        alreadyConnectingOrConnected = { link.connected.value || link.connecting.value },
        attempt = { address ->
            // Start while the activity is visible; Android restricts background service launches.
            val owner = ++scanGeneration
            starting = true
            try {
                keepAlive()
                link.status.value = "Reconnecting to your Flipper…"
                link.startDiscovery()
                val entry = withTimeout(12500) {
                    link.discovered.first { devices -> devices.any { it.address.equals(address,true) && it.nearby && it.connectable != false } }
                        .first { it.address.equals(address,true) && it.nearby && it.connectable != false }
                }
                link.stopDiscovery()
                link.connect(entry)
            } finally { if(owner == scanGeneration) { starting = false; link.stopDiscovery(); stopIfIdle() } }
        },
        failed = { error ->
            link.status.value = if(error is TimeoutCancellationException) "Saved Flipper isn't nearby. Bring it closer, then reopen or tap Find Flipper."
                else "Couldn't reconnect: ${error.message ?: "Try Find Flipper"}"
            Log.i("FlipperHomeBLE","Automatic reconnection did not complete: ${error.javaClass.simpleName}")
            stopIfIdle()
        },
    )
    init {
        scope.launch { link.verifiedDevice.collect { device ->
            if(device != null) prefs.edit().putString("address",device.address).putString("name",device.name).apply()
        } }
        scope.launch { combine(link.connected,link.connecting,link.scanning) { connected,connecting,scanning -> connected || connecting || scanning }
            .collect { active -> if(!active) stopIfIdle() } }
    }
    private fun canConnect(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED
        } && runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)

    private fun keepAlive() {
        ContextCompat.startForegroundService(context,Intent(context,FlipperConnectionService::class.java))
        keepingAlive = true
    }
    private fun stopIfIdle() {
        if(!starting && !link.connected.value && !link.connecting.value && !link.scanning.value && keepingAlive) {
            context.stopService(Intent(context,FlipperConnectionService::class.java))
            keepingAlive = false
        }
    }
    fun onOpen() {
        if(link.connected.value || link.connecting.value) {
            if(canConnect()) runCatching { keepAlive() }.onFailure { Log.w("FlipperHomeBLE","Could not keep connection service active",it) }
        } else reconnect.onOpen()
    }
    fun findDevices() { ++scanGeneration; starting = false; reconnect.cancel(); link.startDiscovery(); stopIfIdle() }
    fun connect(entry: FlipperLink.NearbyDevice) {
        ++scanGeneration; reconnect.cancel()
        check(canConnect()) { "Enable Bluetooth and allow Nearby devices first" }
        starting = true
        try {
            keepAlive()
            prefs.edit().putBoolean("autoReconnect",true).apply()
            link.connect(entry)
        } finally { starting = false; stopIfIdle() }
    }
    fun disconnect() {
        prefs.edit().putBoolean("autoReconnect",false).apply()
        ++scanGeneration; starting = false; reconnect.cancel(); link.disconnect(); stopIfIdle()
    }
    fun onHidden() { ++scanGeneration; starting = false; reconnect.cancel(); link.stopDiscovery(); stopIfIdle() }
}
