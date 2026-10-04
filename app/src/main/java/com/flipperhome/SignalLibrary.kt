package com.flipperhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SignalLibrary(home: Home, initialRemote: String?, link: FlipperLink, busy: Boolean, snackbar: SnackbarHostState,
    back: () -> Unit, save: (Home) -> Unit, test: (RemoteButton) -> Unit,
) {
    var roomId by remember { mutableStateOf(home.remotes.firstOrNull { it.id == initialRemote }?.room) }
    var remoteId by remember { mutableStateOf(initialRemote) }
    var edit by remember { mutableStateOf<String?>(null) }
    val connected by link.connected.collectAsState()
    BackHandler { back() }
    Scaffold(containerColor = Paper,snackbarHost = { SnackbarHost(snackbar) },topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack,"Back from saved signals") }
            Text("Saved signals",fontSize = 26.sp,fontWeight = FontWeight.Bold)
        }
    }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),contentPadding = PaddingValues(bottom = 24.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(roomId == null,{ roomId = null; remoteId = null },label = { Text("All rooms") }) }
            items(home.rooms,key = { it.id }) { r -> FilterChip(roomId == r.id,{ roomId = r.id; remoteId = null },label = { Text(r.name) }) }
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(remoteId == null,{ remoteId = null },label = { Text("All remotes") })
            home.remotes.filter { roomId == null || it.room == roomId }.forEach { r -> FilterChip(remoteId == r.id,{ remoteId = r.id },label = { Text(r.name) }) }
        } }
        val signals = home.buttons.filter { !it.isTv && (roomId == null || it.room == roomId) && (remoteId == null || it.remoteId == remoteId) }
        if(signals.isEmpty()) item { Text("No saved signals here yet.") }
        items(signals,key = { it.id }) { b -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(b.name,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.Medium)
                Text("${b.device} · ${RemoteKind.forFile(b.path)?.label.orEmpty()}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row { FilledTonalButton(enabled = connected && !busy,onClick = { test(b) }) { Text("Test") }
                    TextButton(enabled = !busy,onClick = { edit = b.id }) { Text("Edit signal") } }
            }
        } }
    } }
    home.buttons.firstOrNull { it.id == edit }?.let { b -> SignalEditor(b,home,link,{ edit = null },
        { updated -> save(home.replaceSignal(updated)); edit = null }, { save(home.deleteSignal(b.id)); edit = null }, { r -> save(home.withRemote(r)) }) }
}

@Composable private fun SignalEditor(signal: RemoteButton, home: Home, link: FlipperLink, dismiss: () -> Unit,
    save: (RemoteButton) -> Unit, remove: () -> Unit, settings: (Remote) -> Unit,
) {
    var name by remember { mutableStateOf(signal.name) }
    var path by remember { mutableStateOf(signal.path) }
    var irName by remember { mutableStateOf(signal.signal) }
    var duration by remember { mutableStateOf(signal.holdMs.toString()) }
    var details by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val kind = RemoteKind.forFile(path)
    val remote = home.remotes.firstOrNull { it.id == signal.remoteId }
    AlertDialog(onDismissRequest = dismiss,title = { Text("Edit ${signal.name}") },text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{ name = it },label = { Text("Signal name") },singleLine = true)
        TextButton(enabled = remote != null && name.isNotBlank(),onClick = { learning = true }) { Text("Relearn / replace capture") }
        TextButton(onClick = { details = !details }) { Text("Signal details") }
        if(details) {
            OutlinedTextField(path,{ path = it },label = { Text("Saved Flipper file") },singleLine = true)
            if(kind == RemoteKind.INFRARED) OutlinedTextField(irName,{ irName = it },label = { Text("Signal name inside .ir file") },singleLine = true)
            if(kind == RemoteKind.SUB_GHZ) OutlinedTextField(duration,{ duration = it.filter(Char::isDigit).take(5) },label = { Text("Tap duration (ms)") },supportingText = { Text("100–10000 ms. Holding still sends until release.") },singleLine = true)
        }
        TextButton(onClick = { deleting = true }) { Text("Delete signal",color = MaterialTheme.colorScheme.error) }
        if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
    } },confirmButton = { TextButton(enabled = name.isNotBlank(),onClick = {
        try { save(signal.copy(name = name.trim(),path = path.trim(),signal = irName.trim(),holdMs = duration.toLongOrNull() ?: 0)) }
        catch(e: Exception) { error = e.message ?: "Could not save signal" }
    }) { Text("Save changes") } },dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if(deleting) {
        val controls = home.remotes.sumOf { r -> r.controls.count { signal.id in it.bindings.values } }
        val automations = home.scenes.count { signal.id in it.buttons }
        AlertDialog(onDismissRequest = { deleting = false },title = { Text("Delete this signal?") },text = { Text("Used by $controls controls and $automations automations. Those signal links and sequence steps will be removed. The file stays on Flipper.") },
            confirmButton = { TextButton(onClick = { try { remove() } catch(e: Exception) { error = e.message ?: "Could not delete"; deleting = false } }) { Text("Delete signal") } },dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    }
    if(learning && remote != null) LearnButtonDialog(remote.copy(kind = RemoteKind.forFile(signal.path) ?: remote.kind),RemoteControl(label = name,bindings = mapOf(ControlZone.MAIN to signal.id)),ControlZone.MAIN,home.buttons,link,
        { learning = false },settings) { captured ->
        save(captured.copy(id = signal.id,remoteId = signal.remoteId,name = name.trim(),room = signal.room,device = signal.device,holdMs = duration.toLongOrNull() ?: signal.holdMs))
    }
}
