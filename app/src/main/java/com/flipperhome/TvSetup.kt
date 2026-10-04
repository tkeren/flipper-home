package com.flipperhome

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume

internal data class DiscoveredTv(val name: String,val host: String,val port: Int)

@Composable internal fun TvConnectionChip(state: TvState,click: () -> Unit) {
    Surface(onClick = click,modifier = Modifier.widthIn(max = 320.dp),color = MaterialTheme.colorScheme.surfaceContainerLow,shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(horizontal = 12.dp,vertical = 8.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if(state.connecting) CircularProgressIndicator(Modifier.size(12.dp),strokeWidth = 1.5.dp)
            else Icon(Icons.Rounded.Wifi,null,Modifier.size(14.dp),tint = if(state.connected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.message,Modifier.weight(1f,fill = false),style = MaterialTheme.typography.labelMedium,color = if(state.connected) Green else MaterialTheme.colorScheme.onSurfaceVariant,maxLines = 1,overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore,null,Modifier.size(14.dp))
        }
    }
}
internal class TvDiscovery(context: Context,private val scope: CoroutineScope,private val found: (DiscoveredTv) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var started = false
    private var worker: Job? = null
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { started = true }
        override fun onDiscoveryStopped(type: String) { started = false }
        override fun onStartDiscoveryFailed(type: String,error: Int) { started = false }
        override fun onStopDiscoveryFailed(type: String,error: Int) { started = false }
        override fun onServiceLost(info: NsdServiceInfo) { }
        override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
    }
    fun start() {
        worker = scope.launch {
            for(info in queue) {
                val result = suspendCancellableCoroutine<DiscoveredTv?> { continuation ->
                    manager.resolveService(info,object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo,error: Int) { if(continuation.isActive) continuation.resume(null) }
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val host = service.host?.hostAddress
                            if(continuation.isActive) continuation.resume(host?.let { DiscoveredTv(service.serviceName,it,service.port) })
                        }
                    })
                }
                if(result != null) found(result)
            }
        }
        manager.discoverServices("_androidtvremote2._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)
        started = true
    }
    fun close() { if(started) runCatching { manager.stopServiceDiscovery(listener) }; queue.close(); worker?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TvSetupSheet(device: TvDevice,connection: TvConnection,dismiss: () -> Unit,save: (TvDevice) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discovered by remember { mutableStateOf(emptyList<DiscoveredTv>()) }
    var host by remember { mutableStateOf(device.host) }
    var code by remember { mutableStateOf("") }
    var enteringCode by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf(device) }
    var job by remember { mutableStateOf<Job?>(null) }
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(10000); searching = false }
    fun pair(selected: TvDevice) {
        if(working) return
        candidate = selected; host = selected.host; working = true; error = ""
        job = scope.launch {
            try { connection.startPairing(selected); enteringCode = true }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "Could not reach Chromecast. Use the same Wi-Fi network." }
            finally { working = false }
        }
    }
    DisposableEffect(Unit) {
        val discovery = TvDiscovery(context,scope) { tv -> discovered = (discovered.filterNot { it.host == tv.host } + tv).sortedBy { it.name } }
        runCatching { discovery.start() }.onFailure { error = "Discovery unavailable. Enter the TV's IP address below." }
        onDispose { discovery.close(); job?.cancel(); connection.cancelPairing() }
    }
    ModalBottomSheet(onDismissRequest = dismiss,containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if(enteringCode) "Enter TV code" else "Connect Chromecast",style = MaterialTheme.typography.headlineSmall)
            Text(if(enteringCode) "Enter the six-character pairing code shown on your TV." else "Keep your phone and Chromecast on the same Wi-Fi network.",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(enteringCode) {
                OutlinedTextField(code,{ code = it.filter { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' }.take(6).uppercase() },label = { Text("TV pairing code") },singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),modifier = Modifier.fillMaxWidth(),enabled = !working)
                Button(enabled = !working && code.length == 6,onClick = {
                    working = true; error = ""
                    job = scope.launch {
                        try {
                            val paired = connection.finishPairing(code)
                            save(paired); enteringCode = false
                            connection.connect(paired); dismiss()
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { error = e.message ?: "Could not pair" }
                        finally { working = false }
                    }
                },modifier = Modifier.fillMaxWidth()) { Text("Pair") }
                TextButton(enabled = !working,onClick = { connection.cancelPairing(); enteringCode = false; code = ""; pair(candidate) }) { Text("Get a new code") }
            } else {
                if(connection.paired(device)) FilledTonalButton(enabled = !working,onClick = {
                    working = true; error = ""
                    job = scope.launch { try { connection.connect(device); dismiss() } catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message ?: "Could not connect" } finally { working = false } }
                },modifier = Modifier.fillMaxWidth()) { Text("Reconnect ${device.name}") }
                if(discovered.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(searching) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth = 2.dp)
                    Text(if(searching) "Looking for Google TV devices…" else "No devices found yet. You can connect by IP address.",style = MaterialTheme.typography.bodyMedium)
                }
                discovered.forEach { tv -> OutlinedButton(enabled = !working,onClick = { pair(device.copy(name = tv.name,host = tv.host,port = tv.port)) },modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Tv,null); Text(tv.name,Modifier.weight(1f).padding(start = 10.dp)); Icon(Icons.Rounded.ChevronRight,null)
                } }
                OutlinedTextField(host,{ host = it.trim() },label = { Text("TV IP address (optional)") },supportingText = { Text("On Google TV: Settings → Network & Internet → your network.") },singleLine = true,modifier = Modifier.fillMaxWidth(),enabled = !working)
                TextButton(enabled = !working && host.isNotBlank() && !host.contains('/') && !host.any(Char::isWhitespace),onClick = { pair(device.copy(host = host,port = 6466)) }) { Text(if(connection.paired(device)) "Pair again" else "Pair using IP address") }
            }
            if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = dismiss) { Text("Close") }
        }
    }
}

@Composable internal fun TvActionDialog(home: Home,current: RemoteButton?,preferredTv: String,dismiss: () -> Unit,save: (TvDevice,TvKey) -> Unit) {
    var tvId by remember { mutableStateOf(current?.tvId ?: preferredTv.ifBlank { home.tvDevices.firstOrNull()?.id.orEmpty() }) }
    var command by remember { mutableStateOf(TvKey.entries.firstOrNull { it.code == current?.tvKey } ?: TvKey.OK) }
    AlertDialog(onDismissRequest = dismiss,title = { Text("Google TV button") },text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(home.tvDevices.isEmpty()) Text("Add a Chromecast remote first to choose a TV.")
            else {
                home.tvDevices.forEach { tv -> FilterChip(tv.id == tvId,{ tvId = tv.id },label = { Text(tv.name) }) }
                ChoiceMenu("Command",command.label,TvKey.entries.map { it.label }) { label -> command = TvKey.entries.first { it.label == label } }
                Text("Power and volume depend on your TV's HDMI-CEC and volume settings.",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    },confirmButton = { TextButton(enabled = home.tvDevices.any { it.id == tvId },onClick = { save(home.tvDevices.first { it.id == tvId },command) }) { Text("Use command") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
