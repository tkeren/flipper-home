package com.flipperhome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapDeviceDialog(
    room: String, roomName: String, initialDevice: String, existing: List<RemoteButton>, link: FlipperLink,
    dismiss: () -> Unit, save: (RemoteButton) -> Unit,
) {
    val source = remember { existing.firstOrNull { it.device == initialDevice && RemoteKind.forFile(it.path) == RemoteKind.SUB_GHZ }
        ?: existing.firstOrNull { RemoteKind.forFile(it.path) == RemoteKind.SUB_GHZ } }
    var device by remember { mutableStateOf(initialDevice) }
    var kind by remember { mutableStateOf(existing.firstOrNull { it.device == initialDevice }?.let { RemoteKind.forFile(it.path) } ?: if(source != null) RemoteKind.SUB_GHZ else RemoteKind.INFRARED) }
    var frequency by remember { mutableStateOf("433.92") }
    var preset by remember { mutableStateOf(RadioPreset.AM650) }
    var profileHint by remember { mutableStateOf("Use the frequency of your original remote.") }
    var triedAutoProfile by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("On") }
    var saved by remember { mutableStateOf(emptyList<String>()) }
    var captured by remember { mutableStateOf<CapturedSignal?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val connected by link.connected.collectAsState()
    // Fullscreen Dialog windows can report zero navigation-bar insets on Samsung.
    // Read these from the activity before entering the dialog's composition.
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val suggestions = if(kind == RemoteKind.SUB_GHZ) listOf("On", "Off", "Brighter", "Dimmer", "Mode")
        else listOf("Power", "Volume +", "Volume −", "Mute", "Input", "Channel +", "Channel −")
    fun run(block: suspend () -> Unit) {
        if(working) return
        working = true; error = ""
        job = scope.launch {
            try { block() }
            catch(e: CancellationException) { progress = "Capture stopped"; throw e }
            catch(e: Exception) { error = e.message ?: "Action failed"; progress = "" }
            finally { working = false; job = null }
        }
    }
    fun useSavedFrequency() { source?.let { button -> run {
        progress = "Reading radio settings…"
        val profile = RadioProfile.fromFile(link.readSignalFile(button.path))
        frequency = profile.mhz; preset = profile.preset
        profileHint = "Copied from ${button.device} · ${button.name}"
        progress = ""
    } } }
    LaunchedEffect(connected) {
        if(source != null && connected && !triedAutoProfile) { triedAutoProfile = true; useSavedFrequency() }
    }
    fun saveButton() {
        val signal = captured ?: return
        try {
            save(signal.button(room, device, name))
            saved = saved + name.trim(); captured = null
            name = suggestions.firstOrNull { it !in saved } ?: ""
            progress = "Saved. Ready for the next button."; error = ""
        } catch(e: Exception) { error = e.message ?: "Could not save button" }
    }
    Dialog(onDismissRequest = { job?.cancel(); dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = navigationBottom)
                .consumeWindowInsets(PaddingValues(bottom = navigationBottom)).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { job?.cancel(); dismiss() }) { Icon(Icons.Rounded.Close, "Close mapping") }
                    Column(Modifier.weight(1f)) {
                        Text(if(setup) "Map a device" else device, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("$roomName · ${if(saved.isEmpty()) "Learn buttons from your phone" else "${saved.size} buttons saved"}", fontSize = 12.sp)
                    }
                    if(!setup) TextButton(onClick = { job?.cancel(); dismiss() }) { Text("Done") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(setup) {
                        Text("Set it up once.", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Choose the device, then capture its buttons one after another.")
                        OutlinedTextField(device, { device = it }, enabled = !working, label = { Text("Device name") }, placeholder = { Text("Living room lights") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteKind.entries.forEach { option -> FilterChip(selected = kind == option, enabled = !working, onClick = { kind = option }, label = { Text(option.label) }) }
                        }
                        if(kind == RemoteKind.SUB_GHZ) {
                            OutlinedTextField(frequency, { frequency = it }, enabled = !working, label = { Text("Frequency (MHz)") }, supportingText = { Text(profileHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RadioPreset.entries.forEach { option -> FilterChip(selected = preset == option, enabled = !working, onClick = { preset = option }, label = { Text(option.label) }) }
                            }
                            if(source != null) OutlinedButton(enabled = connected && !working, onClick = { useSavedFrequency() }) { Text("Use saved light's radio settings") }
                            Text("Captures decoded fixed-code remotes, including Princeton lights.", fontSize = 12.sp)
                        } else Text("Point the original remote at Flipper's infrared receiver.")
                    } else {
                        Text(if(captured == null) "${saved.size + 1}. Learn a button" else "Button captured", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(name, { name = it }, enabled = !working, label = { Text("Button name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { label -> FilterChip(selected = name == label, enabled = !working && captured == null, onClick = { name = label }, label = { Text(label) }) }
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if(captured == null) Icons.Rounded.SettingsRemote else Icons.Rounded.CheckCircle, null, Modifier.size(40.dp))
                                Text(if(captured != null) "${captured!!.protocol} · ready to test" else if(working) progress else "Tap Capture, then press $name on the original remote.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(if(kind == RemoteKind.SUB_GHZ) "$frequency MHz · ${preset.label}" else "Keep the remote aimed at Flipper")
                                if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if(captured != null) {
                            Button(enabled = connected && !working, onClick = { run {
                                progress = "Testing…"
                                link.transmit(captured!!.button(room, device, name))
                                progress = "Command sent. Check that your device responded."
                            } }, modifier = Modifier.fillMaxWidth()) { Text("Test button") }
                            if(progress.isNotBlank()) Text(progress)
                            TextButton(enabled = !working, onClick = { captured = null; progress = "" }) { Text("Retry capture") }
                        }
                        if(saved.isNotEmpty()) {
                            Text("Saved to $roomName", fontWeight = FontWeight.SemiBold)
                            Text(saved.joinToString(" · "))
                        }
                        TextButton(enabled = !working && captured == null, onClick = { setup = true }) { Text("Device settings") }
                    }
                    if(setup && working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) }
                    if(!connected) Text("Connect your Flipper in the Flipper tab first.", color = MaterialTheme.colorScheme.error)
                    if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(setup) Button(enabled = device.isNotBlank() && connected && !working, onClick = {
                        try {
                            if(kind == RemoteKind.SUB_GHZ) RadioProfile.fromMHz(frequency, preset)
                            device = device.trim(); setup = false
                            name = if(kind == RemoteKind.SUB_GHZ) "On" else "Power"; error = ""
                        } catch(e: Exception) { error = e.message ?: "Check radio settings" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Start mapping") }
                    else if(working) OutlinedButton(onClick = { job?.cancel(); progress = "Stopping…" }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    else if(captured != null) Button(enabled = name.isNotBlank(), onClick = { saveButton() }, modifier = Modifier.fillMaxWidth()) { Text("Save & next button") }
                    else Button(enabled = connected && name.isNotBlank(), onClick = { run {
                        val profile = if(kind == RemoteKind.SUB_GHZ) RadioProfile.fromMHz(frequency, preset) else RadioProfile()
                        captured = link.capture(CaptureSpec(kind, profile)) { progress = it }
                        progress = "Try the button, then save it."
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Capture ${name.ifBlank { "button" }}") }
                }
            }
        }
    }
}
