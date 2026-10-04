package com.flipperhome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable
internal fun LearnButtonDialog(remote: Remote, control: RemoteControl, zone: ControlZone, buttons: List<RemoteButton>, link: FlipperLink,
    dismiss: () -> Unit, settings: (Remote) -> Unit, next: ((RemoteButton) -> Unit)? = null, nextName: String? = null, connect: (() -> Unit)? = null, save: (RemoteButton) -> Unit,
) {
    val name = control.mappingName(zone)
    var kind by remember { mutableStateOf(remote.kind) }
    var frequency by remember { mutableStateOf(remote.frequency) }
    var preset by remember { mutableStateOf(remote.preset) }
    var captured by remember { mutableStateOf<CapturedSignal?>(null) }
    var working by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val connected by link.connected.collectAsState()
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    fun run(block: suspend () -> Unit) {
        if(working) return
        working = true; error = ""
        job = scope.launch {
            try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { error = e.message ?: "Capture failed" } finally { working = false; job = null }
        }
    }
    val source = remember { buttons.firstOrNull { it.remoteId == remote.id && RemoteKind.forFile(it.path) == RemoteKind.SUB_GHZ } }
    LaunchedEffect(connected) {
        if(connected && source != null && kind == RemoteKind.SUB_GHZ) run {
            progress = "Reading saved radio settings…"
            val profile = RadioProfile.fromFile(link.readSignalFile(source.path))
            frequency = profile.mhz; preset = profile.preset; progress = ""
        }
    }
    Dialog(onDismissRequest = { job?.cancel(); dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = navigationBottom).consumeWindowInsets(PaddingValues(bottom = navigationBottom)).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) { Text("Map button", Modifier.weight(1f), fontSize = 23.sp); TextButton(onClick = { job?.cancel(); dismiss() }) { Text("Close") } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val controlIndex = remote.controls.indexOfFirst { it.id == control.id }
                    Text(remote.name + if(controlIndex >= 0) " · button ${controlIndex+1} of ${remote.controls.size}" else "",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Mapping now",style = MaterialTheme.typography.labelLarge,color = Green)
                            Text(name,style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
                            if(controlIndex >= 0) MappingPreview(remote,control.id)
                        }
                    }
                    if(captured == null && !working) {
                        ChoiceMenu("Signal",kind.label,RemoteKind.entries.map { it.label }) { label -> kind = RemoteKind.entries.first { it.label == label } }
                        if(kind == RemoteKind.SUB_GHZ) {
                            OutlinedTextField(frequency, { frequency = it }, enabled = !working, label = { Text("Frequency (MHz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(enabled = !working, onClick = { advanced = !advanced }) { Text("Radio options") }
                            if(advanced) ChoiceMenu("Preset",preset.label,RadioPreset.entries.map { it.label }) { label -> preset = RadioPreset.entries.first { it.label == label } }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if(captured != null) "Captured for $name" else if(working) progress else "Tap Capture, then press $name on the original remote.", style = MaterialTheme.typography.titleMedium)
                        if(captured != null) Text(captured!!.protocol)
                        else if(kind == RemoteKind.INFRARED) Text("Point the remote at Flipper’s infrared receiver.")
                        if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } }
                    if(captured != null) {
                        Button(enabled = connected && !working, onClick = { run { link.transmit(captured!!.button(remote.room,remote.name,name)); progress = "Command sent. Check your device." } }, modifier = Modifier.fillMaxWidth()) { Text("Test button") }
                        if(progress.isNotBlank()) Text(progress)
                        TextButton(enabled = !working, onClick = { captured = null; progress = "" }) { Text("Capture again") }
                    }
                    if(!connected) {
                        if(connect != null) OutlinedButton(onClick = connect,modifier = Modifier.fillMaxWidth()) { Text("Connect Flipper") }
                        else Text("Connect Flipper first, then come back to this button.",color = MaterialTheme.colorScheme.error)
                    }
                    if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    if(working) OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel capture") }
                    else if(captured != null) {
                        if(next != null) {
                            Text("Next: ${nextName ?: "unmapped button"}",Modifier.padding(bottom = 8.dp),style = MaterialTheme.typography.bodyMedium,color = Green)
                            Button(onClick = { try { next(captured!!.button(remote.room,remote.name,name)) } catch(e: Exception) { error = e.message ?: "Could not save" } }, modifier = Modifier.fillMaxWidth()) { Text("Save & map next") }
                        }
                        Button(onClick = { try { save(captured!!.button(remote.room,remote.name,name)) } catch(e: Exception) { error = e.message ?: "Could not save" } }, modifier = Modifier.fillMaxWidth()) { Text("Save $name") }
                    }
                    else Button(enabled = connected, onClick = { run {
                        val profile = if(kind == RemoteKind.SUB_GHZ) RadioProfile.fromMHz(frequency,preset) else RadioProfile()
                        settings(remote.copy(kind = kind,frequency = frequency,preset = preset))
                        captured = link.capture(CaptureSpec(kind,profile)) { progress = it }; progress = ""
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Capture") }
                }
            }
        }
    }
}

/** A compact map of the actual layout keeps similarly named mapping targets recognizable. */
@Composable private fun MappingPreview(remote: Remote,target: String) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().height(160.dp),contentAlignment = Alignment.Center) {
        val originalWidth = maxWidth.value
        val scale = minOf(160f/remote.canvasHeight,1f)
        Canvas(Modifier.width((originalWidth*scale).dp).height((remote.canvasHeight*scale).dp)
            .background(colors.surface,RoundedCornerShape(12.dp)).border(1.dp,colors.outlineVariant,RoundedCornerShape(12.dp))) {
            remote.controls.forEach { raw ->
                val c = raw.positioned(raw.x,raw.y,originalWidth,remote.canvasHeight,false)
                val w = with(density) { (c.width*scale).dp.toPx() }
                val h = with(density) { (c.height*scale).dp.toPx() }
                val shape = controlShape(c.shape).createOutline(Size(w,h),direction,density)
                translate(c.x*size.width-w/2,c.y*size.height-h/2) {
                    drawOutline(shape,if(c.id == target) colors.primary else colors.surfaceContainerHigh)
                    drawOutline(shape,if(c.id == target) colors.onPrimaryContainer else colors.outline,style = Stroke(if(c.id == target) 2.dp.toPx() else 1.dp.toPx()))
                }
            }
        }
    }
}
