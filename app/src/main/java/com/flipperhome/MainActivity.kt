package com.flipperhome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val connection get() = (application as FlipperHomeApplication).connection
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val bluetoothPermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val callback = permissionResult; permissionResult = null
        val granted = bluetoothPermissions.all { name -> checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED }
        if(granted) allowConnectionNotification()
        callback?.invoke(granted)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun allowConnectionNotification() {
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("connection",MODE_PRIVATE)
            if(!preferences.getBoolean("notificationAsked",false)) {
                preferences.edit().putBoolean("notificationAsked",true).apply()
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preferences = remember { getSharedPreferences("appearance",MODE_PRIVATE) }
            var appearance by remember { mutableStateOf(runCatching { Appearance.valueOf(preferences.getString("mode","DARK")!!) }.getOrDefault(Appearance.DARK)) }
            FlipperTheme(appearance) {
            HomeApp(HomeStore(this), connection,appearance,{ mode -> appearance = mode; preferences.edit().putString("mode",mode.name).apply() }) { result ->
                if (bluetoothPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { allowConnectionNotification(); result(true) }
                else { permissionResult = result; permission.launch(bluetoothPermissions) }
            }
        } }
    }
    override fun onStart() { super.onStart(); connection.onOpen() }
    override fun onStop() { connection.onHidden(); super.onStop() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonDialog(room: String, link: FlipperLink, dismiss: () -> Unit, save: (RemoteButton) -> Unit) {
    var device by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RemoteKind.INFRARED) }
    var hold by remember { mutableStateOf("500") }; var showDuration by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("/ext/infrared/") }; var signal by remember { mutableStateOf("") }
    var signals by remember { mutableStateOf(emptyList<String>()) }; var error by remember { mutableStateOf("") }; var fetching by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("/ext/infrared") }; var files by remember { mutableStateOf(emptyList<FlipperLink.FileEntry>()) }
    var browsing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val connected by link.connected.collectAsState()
    fun browse(next: String) {
        fetching = true; error = ""
        scope.launch { try { files = link.listFiles(next, kind); folder = next; browsing = true; if (files.isEmpty()) error = "No ${kind.label} files in this folder" } catch (e: Exception) { error = e.message ?: "Read failed" } finally { fetching = false } }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add a remote button") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteKind.entries.forEach { option -> FilterChip(selected = kind == option, enabled = !fetching, onClick = {
                    kind = option; path = "${kind.folder}/"; folder = kind.folder; browsing = false
                    files = emptyList(); signals = emptyList(); signal = ""; error = ""
                }, label = { Text(option.label) }) }
            } }
            item { OutlinedTextField(device, { device = it }, label = { Text(if (kind == RemoteKind.SUB_GHZ) "Device · e.g. Living room lights" else "Device · e.g. Living room TV") }, singleLine = true) }
            item { OutlinedTextField(name, { name = it }, label = { Text("Button · e.g. Power") }, singleLine = true) }
            item { OutlinedButton(enabled = connected && !fetching, onClick = { browse(kind.folder) }) { Text(if (kind == RemoteKind.SUB_GHZ) "Browse saved Sub-GHz signals" else "Browse Flipper remotes") } }
            if (browsing) {
                item { Text(folder, fontSize = 12.sp); if (folder != kind.folder) TextButton(enabled = !fetching, onClick = { browse(folder.substringBeforeLast('/')) }) { Text("↑ Parent folder") } }
                items(files) { file -> TextButton(enabled = !fetching, onClick = {
                    if (file.directory) browse("$folder/${file.name}") else {
                        path = "$folder/${file.name}"; signals = emptyList(); signal = ""; browsing = false
                        if (kind == RemoteKind.SUB_GHZ && name.isBlank()) name = file.name.removeSuffix(kind.extension).replace('_', ' ')
                    }
                }) { Text(if (file.directory) "Folder · ${file.name}" else file.name) } }
            }
            item { OutlinedTextField(path, { path = it; signals = emptyList(); signal = "" }, enabled = !fetching, label = { Text("Saved Flipper ${kind.extension} file path") }, singleLine = true) }
            if (kind == RemoteKind.INFRARED) {
            item { OutlinedButton(enabled = connected && !fetching && kind.containsFile(path), onClick = {
                fetching = true; error = ""
                scope.launch { try { signals = link.signals(path); if (signals.isEmpty()) error = "No saved signals found" } catch (e: Exception) { error = e.message ?: "Read failed" } finally { fetching = false } }
            }) { Text(if (fetching) "Reading…" else "Fetch saved signals") } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { signals.forEach { s -> FilterChip(signal == s, { signal = s; if (name.isBlank()) name = s }, label = { Text(s) }) } } }
            } else {
                item { Text("Each saved Sub-GHz file is one remote command.", color = Green, fontSize = 12.sp) }
                item { TextButton(onClick = { showDuration = !showDuration }) { Text(if (showDuration) "Hide button press length" else "Adjust button press length") } }
                if (showDuration) item { OutlinedTextField(hold, { hold = it.filter(Char::isDigit).take(5) }, label = { Text("Button press length (ms)") }, supportingText = { Text("100–10000 ms. Default: 500 ms.") }, singleLine = true) }
            }
            item { if (!connected) Text("Connect your Flipper to read its saved signals."); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !fetching && device.isNotBlank() && name.isNotBlank() && kind.containsFile(path) &&
        (if (kind == RemoteKind.INFRARED) signal in signals else (hold.toLongOrNull() ?: 0) in 100..10000), onClick = {
        save(RemoteButton(room = room, device = device.trim(), name = name.trim(), path = path, signal = signal, holdMs = hold.toLongOrNull() ?: 500))
    }) { Text("Save button") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

