package com.flipperhome

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun AutomationChoiceDialog(home: Home,dismiss: () -> Unit,create: () -> Unit,select: (Scene) -> Unit) {
    AlertDialog(onDismissRequest = dismiss,title = { Text("Button automation") },text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = create,modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add,null); Text("New automation",Modifier.padding(start = 8.dp)) }
            val scenes = home.scenes.filter { it.buttons.isNotEmpty() }
            if(scenes.isNotEmpty()) Text("Use existing automation",style = MaterialTheme.typography.titleSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            scenes.forEach { scene -> TextButton(onClick = { select(scene) },modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp),horizontalAlignment = Alignment.Start) {
                    Text(scene.name); Text("${scene.buttons.size} actions",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    },confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun AutomationEditor(home: Home, initial: Scene? = null, initialRemote: String? = null, initialRoom: String? = null,
    suggestedName: String = "", dismiss: () -> Unit, save: (Scene) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: suggestedName) }
    var actions by remember { mutableStateOf(initial?.steps ?: emptyList()) }
    var durationAction by remember { mutableStateOf<Int?>(null) }
    var timing by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf((initial?.delayMs ?: DEFAULT_ACTION_DELAY_MS).toString()) }
    var error by remember { mutableStateOf("") }
    val equivalent = if(initial == null && actions.isNotEmpty()) home.scenes.firstOrNull { it.steps == actions && it.delayMs == wait.toLongOrNull() } else null
    FullScreenEditor(if(initial == null) "Create automation" else "Edit automation",dismiss,footer = {
        if(equivalent != null) Text("Matches ${equivalent.name}. Use the saved automation.",Modifier.padding(bottom = 8.dp),style = MaterialTheme.typography.bodyMedium,color = Green)
        Button(enabled = (name.isNotBlank() || equivalent != null) && actions.isNotEmpty() && (wait.toLongOrNull() ?: -1) in 0..60000 && actions.all { step -> home.buttons.any { it.id == step.signalId } }, onClick = {
            try { save(equivalent ?: Scene(id = initial?.id ?: java.util.UUID.randomUUID().toString(),name = name.trim(),buttons = emptyList(),delayMs = wait.toLong()).withSteps(actions)) }
            catch(e: Exception) { error = e.message ?: "Could not save automation" }
        }, modifier = Modifier.fillMaxWidth()) { Text(if(equivalent == null) "Save automation" else "Use ${equivalent.name}") }
    }) {
        OutlinedTextField(name,{ name = it },label = { Text("Name / button text") },singleLine = true,modifier = Modifier.fillMaxWidth())
        Text("Add actions",style = MaterialTheme.typography.titleSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionPicker(home,initialRemote,initialRoom) { choice -> actions = actions + choice.steps }
        Text("Sequence · ${actions.size} actions",style = MaterialTheme.typography.titleSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(actions.isEmpty()) Text("Tap a button above to add it. Tap it again to repeat it.",fontSize = 13.sp)
        actions.forEachIndexed { index, step ->
            val b = home.buttons.firstOrNull { it.id == step.signalId }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer,shape = MaterialTheme.shapes.large) {
                Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${index+1}. ${b?.name ?: "Missing signal"}",fontWeight = FontWeight.SemiBold)
                        Text(b?.let { signal -> "${home.rooms.firstOrNull { it.id == signal.room }?.name.orEmpty()} · ${signal.device}" }.orEmpty(),style = MaterialTheme.typography.bodySmall) }
                    IconButton(enabled = index > 0,onClick = { actions = actions.toMutableList().apply { add(index-1,removeAt(index)) } }) { Icon(Icons.Rounded.ArrowUpward,"Move action ${index+1} up",Modifier.size(18.dp)) }
                    IconButton(enabled = index < actions.lastIndex,onClick = { actions = actions.toMutableList().apply { add(index+1,removeAt(index)) } }) { Icon(Icons.Rounded.ArrowDownward,"Move action ${index+1} down",Modifier.size(18.dp)) }
                    IconButton(onClick = { actions = actions.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Rounded.Close,"Remove action ${index+1}",Modifier.size(18.dp)) }
                }
                TextButton(onClick = { durationAction = index },modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Rounded.Timer,null,Modifier.size(18.dp))
                    Text(step.holdMs?.let { "Hold for ${holdSeconds(it)} seconds" } ?: "Tap · change duration",Modifier.padding(start = 8.dp))
                }
                }
            }
        }
        TextButton(onClick = { timing = !timing }) { Text("Timing · $wait ms between actions") }
        if(timing) OutlinedTextField(wait,{ wait = it.filter(Char::isDigit).take(5) },label = { Text("Delay between signals (ms)") },supportingText = { Text("0–60000 ms") },singleLine = true)
        if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
    }
    durationAction?.let { index -> actions.getOrNull(index)?.let { step ->
        key(index) { ActionDurationDialog(step.holdMs,{ durationAction = null }) { hold ->
            actions = actions.mapIndexed { i, action -> if(i == index) action.copy(holdMs = hold) else action }
            durationAction = null
        } }
    } }
}

internal fun holdSeconds(milliseconds: Long): String = java.math.BigDecimal.valueOf(milliseconds,3).stripTrailingZeros().toPlainString()
internal fun parseHoldSeconds(text: String): Long? = try {
    text.trim().toBigDecimalOrNull()?.movePointRight(3)?.longValueExact()?.takeIf { it in 100..60000 }
} catch(_: ArithmeticException) { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActionDurationDialog(initial: Long?, dismiss: () -> Unit, save: (Long?) -> Unit) {
    var holding by remember { mutableStateOf(initial != null) }
    var seconds by remember { mutableStateOf(holdSeconds(initial ?: 5000)) }
    val duration = parseHoldSeconds(seconds)
    AlertDialog(onDismissRequest = dismiss,title = { Text("Action duration") },text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!holding,{ holding = false },label = { Text("Tap") })
                FilterChip(holding,{ holding = true },label = { Text("Hold") })
            }
            if(holding) OutlinedTextField(seconds,{ seconds = it },label = { Text("Seconds") },singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = duration == null,supportingText = { Text("0.1–60 seconds. Starts when the signal begins sending.") })
            else Text("Uses the signal's normal tap duration.")
        }
    },confirmButton = { TextButton(enabled = !holding || duration != null,onClick = { save(if(holding) duration else null) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable private fun ActionPicker(home: Home, initialRemote: String?, initialRoom: String?, select: (ActionChoice) -> Unit) {
    var roomId by remember { mutableStateOf(home.remotes.firstOrNull { it.id == initialRemote }?.room ?: initialRoom ?: home.rooms.firstOrNull()?.id) }
    var remoteId by remember { mutableStateOf(initialRemote) }
    var level by remember { mutableIntStateOf(if(initialRemote == null) 1 else 2) }
    val room = home.rooms.firstOrNull { it.id == roomId }
    val remote = home.remotes.firstOrNull { it.id == remoteId }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { level = 0 }) { Text("Rooms") }
                if(level >= 1) { Text("›"); TextButton(onClick = { level = 1 }) { Text(room?.name.orEmpty()) } }
            }
            if(level == 2) Row(verticalAlignment = Alignment.CenterVertically) { Text("›",Modifier.padding(horizontal = 12.dp)); Text(remote?.name.orEmpty(),fontWeight = FontWeight.Bold) }
            when(level) {
                0 -> home.rooms.forEach { r -> TextButton(onClick = { roomId = r.id; remoteId = null; level = 1 },modifier = Modifier.fillMaxWidth()) { Text(r.name,Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight,null) } }
                1 -> {
                    val remotes = home.remotes.filter { it.room == roomId }
                    remotes.forEach { r -> TextButton(onClick = { remoteId = r.id; level = 2 },modifier = Modifier.fillMaxWidth()) { Text(r.name,Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight,null) } }
                    if(remotes.isEmpty()) Text(if(home.rooms.isEmpty()) "Add a room and learn a signal first." else "No remotes in this room yet.",Modifier.padding(12.dp),fontSize = 13.sp)
                }
                2 -> {
                    val choices = home.actionChoices(remoteId.orEmpty())
                    choices.forEach { c -> TextButton(onClick = { select(c) },modifier = Modifier.fillMaxWidth()) {
                        Icon(if(c.automation) Icons.Rounded.AutoAwesome else Icons.Rounded.Add,null,Modifier.size(18.dp))
                        Text(c.title,Modifier.weight(1f).padding(start = 10.dp))
                        if(c.automation) Text("${c.signals.size} actions",fontSize = 11.sp)
                    } }
                    if(choices.isEmpty()) Text("Learn some signals on this remote first.",Modifier.padding(12.dp),fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable internal fun FullScreenEditor(title: String, dismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    Dialog(onDismissRequest = dismiss,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(),color = Paper) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
                .padding(bottom = navigationBottom).consumeWindowInsets(PaddingValues(bottom = navigationBottom)).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp,vertical = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                    Text(title,Modifier.weight(1f),fontSize = 24.sp,fontWeight = FontWeight.Bold)
                    IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close,"Close editor") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(14.dp),content = body)
                Column(Modifier.fillMaxWidth().padding(20.dp),content = footer)
            }
        }
    }
}
