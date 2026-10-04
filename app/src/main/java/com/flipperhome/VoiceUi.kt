package com.flipperhome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceSettingsSheet(bridge: VoiceBridge, dismiss: () -> Unit) {
    val initial = remember { bridge.settings.load() }
    var url by remember { mutableStateOf(initial.url) }
    var token by remember { mutableStateOf("") }
    var savedToken by remember { mutableStateOf(initial.token) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var error by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf(false) }
    val state by bridge.status.collectAsState()
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Voice control", style = MaterialTheme.typography.titleLarge)
            Text("Connect Home Assistant once, then enable voice on the Flipper buttons you choose.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { uri.openUri("https://github.com/tkeren/flipper-home/blob/main/docs/voice-control.md") }) { Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Home Assistant setup guide") }
            OutlinedTextField(url, { url = it }, label = { Text("Home Assistant HTTPS URL") }, placeholder = { Text("https://your-home.ui.nabu.casa") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text(if(savedToken.isNotBlank()) "New token (leave blank to keep saved)" else "Long-lived access token") }, supportingText = { Text("Create a token in your Home Assistant profile → Security.") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Enable voice control"); Switch(enabled, { enabled = it }) }
            Text(state.message, color = if(state.connected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Flipper must stay connected to this phone. Commands stop when you disconnect. The token is encrypted using Android Keystore.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                try { val updatedToken = token.ifBlank { savedToken }; bridge.settings.save(url, updatedToken, enabled); savedToken = updatedToken; bridge.refresh(); error = ""; token = "" }
                catch(e: Exception) { error = e.message ?: "Settings could not be saved" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save and connect") }
            if(initial.url.isNotBlank()) TextButton(onClick = { forgetting = true }) { Text("Forget Home Assistant") }
            TextButton(onClick = dismiss) { Text("Done") }
        }
    }
    if(forgetting) AlertDialog(onDismissRequest = { forgetting = false }, title = { Text("Forget Home Assistant?") }, text = { Text("Voice playback stops and the saved token is removed from this phone. Your signals remain saved.") },
        confirmButton = { TextButton(onClick = { bridge.settings.forget(); bridge.refresh(); dismiss() }) { Text("Forget") } }, dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceButtonSheet(button: RemoteButton, home: Home, bridge: VoiceBridge, dismiss: () -> Unit, save: (Home) -> Unit) {
    val room = home.rooms.firstOrNull { it.id == button.room }?.name.orEmpty()
    var name by remember(button.id) { mutableStateOf(button.voiceName.ifBlank { "$room ${button.name}".trim() }) }
    var timed by remember(button.id) { mutableStateOf(button.voiceHoldMs != null) }
    var seconds by remember(button.id) { mutableStateOf((button.voiceHoldMs?.div(1000.0) ?: 5.0).toString()) }
    var enabled by remember(button.id) { mutableStateOf(button.voiceName.isNotBlank()) }
    var error by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf(false) }
    val config = bridge.settings.load()
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up voice command", style = MaterialTheme.typography.titleLarge)
            Text("$room → ${button.device} → ${button.name}", color = Green)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Enable this button"); Switch(enabled, { enabled = it }) }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Voice action name") }, supportingText = { Text("Use a unique name, such as Bedroom lights off.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if(name.isNotBlank()) Text("Hey Google, activate ${name.trim()}", style = MaterialTheme.typography.bodyLarge)
            Text("Expose this action to Google in Home Assistant. For your own phrase, create a Google Home routine that activates it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!timed, { timed = false }, label = { Text("Tap") }); FilterChip(timed, { timed = true }, label = { Text("Timed hold") }) }
            if(timed) OutlinedTextField(seconds, { seconds = it }, label = { Text("Hold for seconds") }, supportingText = { Text("0.1–60 seconds. Useful for dimming.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            if(!config.enabled || config.token.isBlank()) TextButton(onClick = { setup = true }) { Text("Connect Home Assistant") }
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                try {
                    require(!enabled || name.isNotBlank()) { "Name the voice action" }
                    val duration = if(enabled && timed) ((seconds.toDoubleOrNull() ?: error("Enter a number of seconds")) * 1000).toLong() else null
                    val next = home.withVoice(button.id, if(enabled) name else "", duration)
                    save(next); bridge.updateActions(); dismiss()
                } catch(e: Exception) { error = e.message ?: "Voice command could not be saved" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save voice command") }
            TextButton(onClick = dismiss) { Text("Cancel") }
        }
    }
    if(setup) VoiceSettingsSheet(bridge) { setup = false }
}
