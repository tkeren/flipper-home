package com.flipperhome

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

/** One outstanding GATT operation and one outstanding RPC command at a time. */
@SuppressLint("MissingPermission")
class FlipperLink(private val context: Context, private val scope: CoroutineScope) {
    val status = MutableStateFlow("Disconnected")
    val connected = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    data class VerifiedDevice(val address: String, val name: String)
    val verifiedDevice = MutableStateFlow<VerifiedDevice?>(null)
    val screen = MutableStateFlow(ByteArray(0))
    val screenStatus = MutableStateFlow("Open live screen to view your Flipper")
    data class NearbyDevice(val device: BluetoothDevice, val name: String, val address: String, val nearby: Boolean, val connectable: Boolean? = null, val flipperCandidate: Boolean = false)
    val discovered = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val scanStatus = MutableStateFlow("")
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeout: Job? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var transportReady = false
    private var connectionTimeout: Job? = null
    private var connectionJob: Job? = null
    private var setupJob: Job? = null
    private var connectionPhase = "Disconnected"
    private var rx: BluetoothGattCharacteristic? = null
    private val incoming = Protocol.StreamDecoder()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var negotiatedMtu = 23
    private var firstScreenFrame: CompletableDeferred<Unit>? = null
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<List<List<Protocol.Field>>>>()
    private val responses = ConcurrentHashMap<Int, MutableList<List<Protocol.Field>>>()
    private val commands = Mutex()
    private val operations = Mutex()
    private var completion: CompletableDeferred<Int>? = null
    @Volatile private var credits = 0
    private var appStarted: CompletableDeferred<Unit>? = null
    private var appClosed: CompletableDeferred<Unit>? = null
    private var captureEvents: Channel<ByteArray>? = null
    private val creditChanged = Channel<Unit>(Channel.CONFLATED)
    private val service = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    private fun uuid(n: String) = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e${n}fe0000")
    private val txUuid = uuid("61")
    private val rxUuid = uuid("62")
    private val flowUuid = uuid("63")
    private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun pairedDevices(): List<BluetoothDevice> = context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList() ?: emptyList()
    fun startDiscovery() {
        stopDiscovery()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled) { "Turn on Bluetooth on your phone first" }
        discovered.value = adapter.bondedDevices.map { NearbyDevice(it, it.name ?: "Paired device", it.address, false, flipperCandidate = FlipperAdvertisement.isCandidate(it.name, it.address, emptyList())) }
        val activeScanner = requireNotNull(adapter.bluetoothLeScanner) { "Bluetooth LE scanning unavailable" }
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val owner = this
                scope.launch {
                    if (scanCallback !== owner) return@launch
                    try {
                        val device = result.device
                        val previous = discovered.value.firstOrNull { it.address == device.address }
                        val knownName = result.scanRecord?.deviceName ?: device.name ?: previous?.name?.takeUnless { it == "Unnamed BLE device" }
                        val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                        val candidate = FlipperAdvertisement.isCandidate(knownName, device.address, services) || previous?.flipperCandidate == true
                        val name = knownName ?: if (candidate) "Possible Flipper (name unavailable)" else "Unnamed BLE device"
                        val entry = NearbyDevice(device, name, device.address, true, result.isConnectable, candidate)
                        if (candidate && previous?.nearby != true) Log.i("FlipperHomeBLE", "Flipper candidate found: named=${knownName != null} connectable=${result.isConnectable} services=$services")
                        discovered.value = (discovered.value.filter { it.address != entry.address } + entry)
                            .sortedWith(compareByDescending<NearbyDevice> { it.flipperCandidate }.thenByDescending { it.nearby }.thenBy { it.name })
                    } catch (_: SecurityException) { stopDiscovery(); scanStatus.value = "Nearby devices permission was revoked" }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) {
                val owner = this
                scope.launch { if (scanCallback === owner) { stopDiscovery(); scanStatus.value = "Bluetooth scan failed ($errorCode). Wait a moment and try again." } }
            }
        }
        scanner = activeScanner; scanCallback = callback; scanning.value = true
        scanStatus.value = "Looking for nearby BLE devices…"
        try { activeScanner.startScan(null, ScanSettings.Builder().setLegacy(false).setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback) }
        catch (e: Exception) { stopDiscovery(); throw e }
        scanTimeout = scope.launch {
            delay(12000)
            stopDiscovery()
            val nearby = discovered.value.count { it.nearby }
            val candidates = discovered.value.count { it.nearby && it.flipperCandidate }
            Log.i("FlipperHomeBLE", "Scan finished: nearby=$nearby flipperCandidates=$candidates")
            scanStatus.value = if (candidates > 0) "Scan finished. Select a nearby Flipper below." else "No Flipper detected in $nearby nearby BLE devices. Saved pairings alone do not confirm that Flipper is available."
        }
    }
    fun stopDiscovery() {
        scanTimeout?.cancel(); scanTimeout = null
        val callback = scanCallback; scanCallback = null
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        scanner = null; scanning.value = false
    }
    fun connect(entry: NearbyDevice) {
        val device = entry.device
        check(entry.nearby) { "This is only a saved pairing. Scan again and wait until Flipper is detected nearby." }
        check(entry.connectable != false) { "Flipper is advertising but is not accepting a connection. Disconnect it from other apps, then scan again." }
        disconnect()
        check(context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) { "Turn on Bluetooth on your phone first" }
        connecting.value = true
        connectionJob = scope.launch {
            try {
                phase("Opening Bluetooth connection to ${device.name ?: "Flipper"}")
                val autoConnect = device.bondState == BluetoothDevice.BOND_BONDED
                Log.i("FlipperHomeBLE", "Selected nearby=${entry.nearby} connectable=${entry.connectable} bond=${device.bondState} transport=${device.type} autoConnect=$autoConnect")
                // Keep callbacks and coroutine state on the same thread, preserving packet order.
                gatt = requireNotNull(device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, callbackHandler)) { "Android could not open the Bluetooth connection" }
                connectionTimeout = scope.launch {
                    delay(30000)
                    if (!connected.value) fail("Timed out during $connectionPhase. Keep Flipper nearby and disconnect it from other apps.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message ?: "Pairing or connection failed") }
        }
    }
    private fun phase(message: String) {
        connectionPhase = message
        status.value = "$message…"
        Log.i("FlipperHomeBLE", message)
    }
    private fun fail(message: String) {
        Log.w("FlipperHomeBLE", message)
        disconnect()
        status.value = message
    }
    /** Android owns PIN entry; this app never stores or logs the pairing code. */
    private suspend fun ensurePaired(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            phase("Using saved Android pairing")
            return
        }
        phase("Pairing: enter the code shown on Flipper in Android's pairing prompt")
        val paired = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (changed.address != device.address) return
                // Read Android's current state instead of trusting extras from the broadcast.
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> paired.complete(Unit)
                    BluetoothDevice.BOND_NONE -> paired.completeExceptionally(IllegalStateException("Pairing was cancelled or failed. Select Flipper again and enter its displayed code."))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> paired.complete(Unit)
                BluetoothDevice.BOND_NONE -> check(device.createBond()) { "Android could not start pairing. Close other Flipper apps and try again." }
            }
            try { withTimeout(90000) { paired.await() } }
            catch (_: TimeoutCancellationException) { error("Pairing timed out. Check Android's pairing notification and enter the code shown on Flipper.") }
        } finally { context.unregisterReceiver(receiver) }
    }
    fun disconnect() {
        stopDiscovery()
        connectionJob?.cancel(); connectionJob = null
        setupJob?.cancel(); setupJob = null
        connectionTimeout?.cancel(); connectionTimeout = null
        connected.value = false
        connecting.value = false
        transportReady = false
        val oldGatt = gatt; gatt = null; rx = null
        runCatching { oldGatt?.disconnect() }; runCatching { oldGatt?.close() }
        incoming.reset(); screen.value = byteArrayOf(); credits = 0; negotiatedMtu = 23
        creditChanged.trySend(Unit)
        firstScreenFrame?.completeExceptionally(IllegalStateException("Disconnected while waiting for the live screen"))
        screenStatus.value = "Connect your Flipper to view its screen"
        completion?.completeExceptionally(IllegalStateException("Disconnected"))
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Disconnected")) }
        pending.clear(); responses.clear(); status.value = "Disconnected"
        appStarted?.completeExceptionally(IllegalStateException("Disconnected"))
        appClosed?.completeExceptionally(IllegalStateException("Disconnected"))
        captureEvents?.close(IllegalStateException("Disconnected during capture"))
    }
    private suspend fun operation(start: () -> Boolean) = operations.withLock {
        val done = CompletableDeferred<Int>(); completion = done
        try {
            check(start()) { "Bluetooth operation rejected during $connectionPhase" }
            val code = withTimeout(10000) { done.await() }
            check(code == BluetoothGatt.GATT_SUCCESS) { "Bluetooth operation failed ($code) during $connectionPhase" }
        }
        finally { completion = null }
    }
    @Suppress("DEPRECATION")
    private suspend fun subscribe(g: BluetoothGatt, char: BluetoothGattCharacteristic, indicate: Boolean) {
        check(g.setCharacteristicNotification(char, true))
        val descriptor = requireNotNull(char.getDescriptor(ccc)) { "Missing Bluetooth subscription descriptor" }
        descriptor.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        operation { g.writeDescriptor(descriptor) }
    }
    private fun receive(char: BluetoothGattCharacteristic, value: ByteArray) {
        if (char.uuid == flowUuid && value.size == 4) {
            credits = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 255) }
            creditChanged.trySend(Unit)
        }
        if (char.uuid != txUuid) return
        try {
            for (fields in incoming.append(value)) {
                val pixels = Protocol.screenPixels(fields)
                if (pixels != null) {
                    screen.value = pixels
                    screenStatus.value = "Live screen"
                    if (firstScreenFrame?.isCompleted == false) Log.i("FlipperHomeBLE", "First screen frame received: ${pixels.size} bytes, MTU=$negotiatedMtu")
                    firstScreenFrame?.complete(Unit)
                    // Screen messages are unsolicited events, not command responses.
                    continue
                }
                val appEvent = fields.firstOrNull { it.tag == 58 }
                if(appEvent != null) {
                    val event = appEvent
                    if (Protocol.fields(event.data).firstOrNull { it.tag == 1 }?.number == 1L) appStarted?.complete(Unit)
                    else appClosed?.complete(Unit)
                    continue
                }
                val exchange = fields.firstOrNull { it.tag == 65 }
                if(exchange != null) {
                    Protocol.fields(exchange.data).firstOrNull { it.tag == 1 }?.let { captureEvents?.trySend(it.data) }
                    continue
                }
                val wireId = fields.firstOrNull { it.tag == 1 }?.number ?: 0L
                if (wireId !in 1L..Int.MAX_VALUE.toLong()) continue
                val id = wireId.toInt()
                val deferred = pending[id] ?: continue
                val code = fields.firstOrNull { it.tag == 2 }?.number ?: 0L
                if (code != 0L) deferred.completeExceptionally(FlipperRpcException(code))
                else {
                    responses.getOrPut(id) { mutableListOf() }.add(fields)
                    if (fields.firstOrNull { it.tag == 3 }?.number != 1L) deferred.complete(responses[id]!!.toList())
                }
            }
        } catch (e: Exception) {
            Log.w("FlipperHomeBLE", "RPC decode failed: packetBytes=${value.size} bufferedBytes=${incoming.bufferedBytes} MTU=$negotiatedMtu", e)
            fail("Invalid Flipper response: ${e.message ?: "Could not decode RPC data"}")
        }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            scope.launch {
                if (g !== gatt) return@launch
                Log.i("FlipperHomeBLE", "GATT status=$code state=$state phase=$connectionPhase")
                if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    val message = when (code) {
                        147 -> "Bluetooth connection timed out (147) during $connectionPhase. Keep Flipper nearby and disconnect it from other apps."
                        5, 15 -> "Bluetooth authentication failed ($code). The saved pairing may be invalid; pair again in Android."
                        else -> "Connection closed ($code) during $connectionPhase"
                    }
                    fail(message)
                } else if (state == BluetoothProfile.STATE_CONNECTED) {
                    // Match the official app: establish the BLE link, bond, then discover services.
                    connectionTimeout?.cancel(); connectionTimeout = null
                    connectionJob = scope.launch {
                        try {
                            ensurePaired(g.device)
                            if (g !== gatt) return@launch
                            phase("Negotiating Bluetooth packet size")
                            operation { g.requestMtu(512) }
                            check(negotiatedMtu >= 23) { "Invalid Bluetooth MTU: $negotiatedMtu" }
                            phase("Discovering Flipper services")
                            connectionTimeout = scope.launch {
                                delay(30000)
                                if (g === gatt && !connected.value) fail("Timed out during $connectionPhase")
                            }
                            if (!g.discoverServices()) fail("Android could not start service discovery")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (g === gatt) fail(e.message ?: "Pairing failed") }
                    }
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, code: Int) {
            if (g !== gatt) return
            if (code == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            Log.i("FlipperHomeBLE", "MTU negotiated: $mtu status=$code")
            completion?.complete(code)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            if (g !== gatt) return
            setupJob = scope.launch {
                try {
                    if (g !== gatt) return@launch
                    check(code == BluetoothGatt.GATT_SUCCESS)
                    phase("Setting up authenticated Flipper RPC")
                    val svc = requireNotNull(g.getService(service)) { "This device has no Flipper RPC service" }
                    rx = requireNotNull(svc.getCharacteristic(rxUuid))
                    val flow = requireNotNull(svc.getCharacteristic(flowUuid))
                    subscribe(g, requireNotNull(svc.getCharacteristic(txUuid)), true)
                    subscribe(g, flow, false)
                    operation { g.readCharacteristic(flow) }
                    transportReady = true
                    phase("Verifying Flipper RPC")
                    request(5, byteArrayOf()) // Ping verifies the RPC session before reporting ready.
                    connected.value = true
                    connecting.value = false
                    verifiedDevice.value = VerifiedDevice(g.device.address,g.device.name ?: "Flipper Zero")
                    connectionTimeout?.cancel(); connectionTimeout = null
                    status.value = "Connected · ${g.device.name ?: "Flipper Zero"}"
                    screenStatus.value = "Open live screen to view your Flipper"
                } catch (_: TimeoutCancellationException) { if (g === gatt) fail("Timed out during $connectionPhase. Check Flipper's screen and Android's pairing notification.") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (g === gatt) fail(e.message ?: "Connection failed") }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, code: Int) { if (g === gatt) completion?.complete(code) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) { if (g === gatt) completion?.complete(code) }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) {
            if (g === gatt) { receive(c, c.value ?: byteArrayOf()); completion?.complete(code) }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, code: Int) {
            if (g === gatt) { receive(c, value); completion?.complete(code) }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if (g === gatt) receive(c, c.value ?: byteArrayOf()) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { if (g === gatt) receive(c, value) }
    }
    @Suppress("DEPRECATION")
    suspend fun request(field: Int, payload: ByteArray, timeoutMs: Long = 15000): List<List<Protocol.Field>> = commands.withLock {
        check(transportReady) { "Connect your Flipper first" }
        val id = ids.incrementAndGet(); val result = CompletableDeferred<List<List<Protocol.Field>>>()
        pending[id] = result
        try {
            withTimeout(timeoutMs) {
                val packet = Protocol.request(id, field, payload)
                // A partially written length-prefixed frame would corrupt the next RPC.
                withContext(NonCancellable) {
                    try {
                        withTimeout(timeoutMs) {
                            var offset = 0
                            while (offset < packet.size) {
                                while (credits <= 0) { check(transportReady) { "Disconnected" }; creditChanged.receive() }
                                val count = minOf(negotiatedMtu - 3, 486, credits, packet.size - offset)
                                val char = requireNotNull(rx); val g = requireNotNull(gatt)
                                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                char.value = packet.copyOfRange(offset, offset + count)
                                credits -= count
                                operation { g.writeCharacteristic(char) }
                                offset += count
                            }
                        }
                    } catch(e: Exception) {
                        if(transportReady) fail("Bluetooth write interrupted. Reconnect your Flipper and try again.")
                        throw e
                    }
                }
                result.await()
            }
        } finally { pending.remove(id); responses.remove(id) }
    }
    private val actions = Mutex()
    suspend fun readSignalFile(path: String): String {
        require(RemoteKind.forFile(path) != null) { "Choose a saved remote signal" }
        return readFile(path).toString(Charsets.UTF_8)
    }
    private suspend fun readFile(path: String): ByteArray = request(9, Protocol.string(1, path)).flatMap { fields ->
        fields.filter { it.tag == 10 }.flatMap { response ->
            Protocol.fields(response.data).filter { it.tag == 1 }.flatMap { file -> Protocol.fields(file.data).filter { it.tag == 4 }.map { it.data } }
        }
    }.fold(byteArrayOf()) { acc, bytes -> acc + bytes }

    /** Content-addressed companion: verify the upload before launching; leave existing files alone. */
    private suspend fun ensureCaptureApp(progress: (String) -> Unit): String {
        progress("Checking capture companion…")
        val asset = context.assets.open("flipper_home_capture.fap").use { it.readBytes() }
        val md5 = MessageDigest.getInstance("MD5").digest(asset).joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = "/ext/apps/Tools/flipper_home_capture_$md5.fap"
        suspend fun checksum(file: String): String = request(14, Protocol.string(1, file)).flatMap { it }.first { it.tag == 15 }.let { Protocol.fields(it.data).first { field -> field.tag == 1 }.data.toString(Charsets.UTF_8) }
        try { check(checksum(path) == md5) { "Capture companion checksum mismatch" }; return path }
        catch(e: FlipperRpcException) { if(e.code != 7L) throw e }
        for(folder in listOf("/ext/apps", "/ext/apps/Tools")) {
            try { request(13, Protocol.string(1, folder)) }
            catch(e: FlipperRpcException) { if(e.code != 6L) throw e }
        }
        progress("Installing capture companion on Flipper…")
        val temporary = "/ext/apps/Tools/flipper_home_capture_upload.fap"
        request(11, Protocol.string(1, temporary) + Protocol.bytes(2, Protocol.bytes(4, asset)), 90000)
        check(checksum(temporary) == md5) { "Capture app upload was incomplete. Try again." }
        request(30, Protocol.string(1, temporary) + Protocol.string(2, path))
        return path
    }
    suspend fun capture(spec: CaptureSpec, progress: (String) -> Unit): CapturedSignal = actions.withLock {
        check(connected.value) { "Connect your Flipper first" }
        val path = ensureCaptureApp(progress)
        val events = Channel<ByteArray>(16); captureEvents = events
        val started = CompletableDeferred<Unit>(); appStarted = started
        val closed = CompletableDeferred<Unit>(); appClosed = closed
        try {
            progress("Starting receiver…")
            CaptureSession.capture(path, spec, events,
                send = { field, payload -> request(field, payload); Unit }, started = started, closed = closed,
                readFile = { readSignalFile(it) }, onReady = { progress("Press the button on your original remote") })
        } finally { captureEvents = null; events.close(); appStarted = null; appClosed = null }
    }
    suspend fun transmit(button: RemoteButton, release: Deferred<Unit>? = null, holdDurationMs: Long? = null, onPressed: () -> Unit = {}) = actions.withLock {
        transmitLocked(button, release, holdDurationMs, onPressed)
    }
    /** Voice requests fail promptly during capture/playback rather than being queued. */
    suspend fun voiceTransmit(button: RemoteButton, duration: Long?) {
        check(actions.tryLock()) { "Flipper is busy" }
        try { transmitLocked(button, null, duration) } finally { actions.unlock() }
    }
    private suspend fun transmitLocked(button: RemoteButton, release: Deferred<Unit>?, holdDurationMs: Long?, onPressed: () -> Unit = {}) {
        check(connected.value) { "Connect your Flipper first" }
        val began = android.os.SystemClock.elapsedRealtime()
        val started = CompletableDeferred<Unit>(); appStarted = started
        val closed = CompletableDeferred<Unit>(); appClosed = closed
        try {
            RemoteSender.transmit(button,
                send = { field, payload -> request(field,payload); Unit },
                awaitStarted = { withTimeout(5000) { started.await() } },
                awaitClosed = { withTimeout(5000) { closed.await() } },
                release = release,
                holdDurationMs = holdDurationMs,
                onPressed = {
                    Log.i("FlipperHomeBLE","Remote press acknowledged after ${android.os.SystemClock.elapsedRealtime()-began} ms")
                    onPressed()
                },
            )
        } finally { appStarted = null; appClosed = null }
    }
    suspend fun startScreen() = actions.withLock {
        check(connected.value) { "Connect your Flipper first" }
        val frame = CompletableDeferred<Unit>(); firstScreenFrame = frame
        screen.value = byteArrayOf(); screenStatus.value = "Waiting for Flipper's screen…"
        try {
            request(20, byteArrayOf())
            try { withTimeout(10000) { frame.await() } }
            catch (_: TimeoutCancellationException) { error("Flipper accepted the screen request but no image arrived. Try opening the live screen again.") }
        } catch (e: Exception) {
            if (transportReady) withContext(NonCancellable) { runCatching { request(21, byteArrayOf()) } }
            screen.value = byteArrayOf()
            screenStatus.value = if (connected.value) "Screen unavailable. Open live screen to retry." else "Connect your Flipper to view its screen"
            throw e
        } finally { firstScreenFrame = null }
    }
    suspend fun stopScreen() = actions.withLock {
        request(21, byteArrayOf())
        screen.value = byteArrayOf(); screenStatus.value = "Live screen stopped"
    }
    suspend fun input(key: Int) = actions.withLock {
        require(key in 0..5) { "Unknown Flipper button" }
        // Firmware requires complementary events; a SHORT without PRESS is discarded.
        request(23, Protocol.number(1, key) + Protocol.number(2, 0))
        try { request(23, Protocol.number(1, key) + Protocol.number(2, 2)) }
        finally { withContext(NonCancellable) { request(23, Protocol.number(1, key) + Protocol.number(2, 1)) } }
    }
    data class FileEntry(val name: String, val directory: Boolean)
    suspend fun listFiles(path: String, kind: RemoteKind = RemoteKind.INFRARED): List<FileEntry> {
        require(kind.containsDirectory(path)) { "Choose a folder under ${kind.folder}" }
        return request(7, Protocol.string(1, path)).flatMap { fields ->
            fields.filter { it.tag == 8 }.flatMap { response -> Protocol.fields(response.data).filter { it.tag == 1 }.mapNotNull { file ->
                val values = Protocol.fields(file.data)
                val name = values.firstOrNull { it.tag == 2 }?.data?.toString(Charsets.UTF_8) ?: return@mapNotNull null
                val directory = values.firstOrNull { it.tag == 1 }?.number == 1L
                if ((directory || name.endsWith(kind.extension)) && name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000') && name != "." && name != "..") FileEntry(name, directory) else null
            } }
        }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name })
    }
    suspend fun signals(path: String): List<String> {
        require(RemoteKind.INFRARED.containsFile(path))
        val replies = request(9, Protocol.string(1, path))
        val data = replies.flatMap { fields -> fields.filter { it.tag == 10 }.flatMap { response ->
            Protocol.fields(response.data).filter { it.tag == 1 }.flatMap { file -> Protocol.fields(file.data).filter { it.tag == 4 }.map { it.data } }
        } }.fold(byteArrayOf()) { acc, bytes -> acc + bytes }.toString(Charsets.UTF_8)
        require(data.contains("Filetype: IR signals file")) { "Not a Flipper infrared remote" }
        return data.lineSequence().filter { it.startsWith("name:") }.map { it.substringAfter(":").trim() }.filter { it.isNotEmpty() }.toList()
    }
}
