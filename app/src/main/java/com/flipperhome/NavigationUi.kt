package com.flipperhome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

@Composable internal fun BoxScope.PlaybackOverlay(status: String?,stopAutomation: (() -> Unit)?) {
    if(status == null && stopAutomation == null) return
    // Playback feedback is a sibling of the scroll content, so showing it cannot move controls.
    Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,color = MaterialTheme.colorScheme.surfaceContainerHigh,shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 16.dp,end = 8.dp,top = 4.dp,bottom = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status ?: "Automation running",Modifier.weight(1f),style = MaterialTheme.typography.bodySmall,color = Green)
            if(stopAutomation != null) TextButton(onClick = stopAutomation) { Text("Stop automation") }
        }
    }
}

@Composable internal fun ConnectionChip(connected: Boolean,connecting: Boolean,click: () -> Unit) {
    Surface(onClick = click,color = MaterialTheme.colorScheme.surfaceContainerLow,shape = CircleShape) {
        Row(Modifier.padding(horizontal = 12.dp,vertical = 8.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if(connecting) CircularProgressIndicator(Modifier.size(12.dp),strokeWidth = 1.5.dp)
            else Icon(if(connected) Icons.Rounded.Circle else Icons.Rounded.Bluetooth, null,Modifier.size(12.dp),tint = if(connected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(connected) "Connected" else if(connecting) "Connecting…" else "Disconnected",style = MaterialTheme.typography.labelMedium,color = if(connected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Rounded.ExpandMore,null,Modifier.size(14.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun AppHeader(title: String,back: (() -> Unit)?,connected: Boolean,connecting: Boolean,connect: () -> Unit,
    add: (() -> Unit)?,addLabel: String,signals: () -> Unit,appearance: () -> Unit,rename: (() -> Unit)?,delete: (() -> Unit)?,importSignal: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp),verticalAlignment = Alignment.CenterVertically) {
            if(back != null) IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack,"All rooms") }
            Text(title,Modifier.weight(1f).padding(start = if(back == null) 8.dp else 0.dp),style = MaterialTheme.typography.headlineMedium,fontWeight = FontWeight.SemiBold,maxLines = 1)
            if(add != null) IconButton(onClick = add) { Icon(Icons.Rounded.Add,addLabel) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert,"More options") }
                DropdownMenu(menu,{ menu = false }) {
                    if(rename != null) DropdownMenuItem(text = { Text("Rename room") },onClick = { menu = false; rename() },leadingIcon = { Icon(Icons.Rounded.Edit,null) })
                    if(importSignal != null) DropdownMenuItem(text = { Text("Import signal") },onClick = { menu = false; importSignal() },leadingIcon = { Icon(Icons.Rounded.FileDownload,null) })
                    DropdownMenuItem(text = { Text("Saved signals") },onClick = { menu = false; signals() },leadingIcon = { Icon(Icons.Rounded.GraphicEq,null) })
                    DropdownMenuItem(text = { Text("Appearance") },onClick = { menu = false; appearance() },leadingIcon = { Icon(Icons.Rounded.DarkMode,null) })
                    if(delete != null) { HorizontalDivider(); DropdownMenuItem(text = { Text("Delete room",color = MaterialTheme.colorScheme.error) },onClick = { menu = false; delete() },leadingIcon = { Icon(Icons.Rounded.DeleteOutline,null) }) }
                }
            }
        }
        Box(Modifier.padding(start = 8.dp,top = 8.dp,bottom = 12.dp)) { ConnectionChip(connected,connecting,connect) }
    }
}

@Composable private fun RowIcon(icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,shape = MaterialTheme.shapes.medium) {
        Icon(icon,null,Modifier.padding(13.dp).size(22.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun RoomRow(room: Room,remotes: Int,open: () -> Unit,rename: () -> Unit,delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = open,colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp,top = 14.dp,bottom = 14.dp,end = 6.dp),verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.MeetingRoom)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(room.name,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.Medium)
                Text("$remotes ${if(remotes == 1) "remote" else "remotes"}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert,"Room options: ${room.name}",tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu,{ menu = false }) {
                    DropdownMenuItem(text = { Text("Rename room") },onClick = { menu = false; rename() })
                    DropdownMenuItem(text = { Text("Delete room",color = MaterialTheme.colorScheme.error) },onClick = { menu = false; delete() })
                }
            }
        }
    }
}
@Composable internal fun RemoteRow(remote: Remote,pinned: Boolean,open: () -> Unit,pin: () -> Unit) {
    Card(onClick = open,colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp,top = 14.dp,bottom = 14.dp,end = 6.dp),verticalAlignment = Alignment.CenterVertically) {
            RowIcon(when(remote.category) { RemoteCategory.LIGHTS -> Icons.Rounded.Lightbulb; RemoteCategory.TV,RemoteCategory.CHROMECAST -> Icons.Rounded.Tv; RemoteCategory.FAN -> Icons.Rounded.Air; else -> Icons.Rounded.SettingsRemote })
            Column(Modifier.weight(1f).padding(horizontal = 14.dp),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(remote.name,style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.Medium)
                Text("${remote.controls.size} ${if(remote.controls.size == 1) "button" else "buttons"}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PinButton(pinned,pin)
            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp).padding(end = 4.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun FavoriteRow(title: String,detail: String,kind: ShortcutKind,enabled: Boolean,click: () -> Unit,
    hold: ((Deferred<Unit>) -> Job?)?,remove: (() -> Unit)?,moveUp: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = kind == ShortcutKind.REMOTE || enabled,onClick = click).padding(end = 8.dp)) {
                Text(title,style = MaterialTheme.typography.titleMedium)
                Text(detail,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(remove != null) {
                if(moveUp != null) IconButton(onClick = moveUp) { Icon(Icons.Rounded.ArrowUpward,"Move favorite up") }
                IconButton(onClick = remove) { Icon(Icons.Rounded.Close,"Remove favorite") }
            } else if(hold != null) Surface(Modifier.remotePress("favorite:$title",enabled,click,hold),color = MaterialTheme.colorScheme.secondaryContainer,shape = CircleShape) {
                Box(Modifier.size(48.dp),contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow,"Send $title",tint = if(enabled) Green else MaterialTheme.colorScheme.onSurfaceVariant) }
            } else IconButton(enabled = kind == ShortcutKind.REMOTE || enabled,onClick = click) { Icon(if(kind == ShortcutKind.REMOTE) Icons.Rounded.ChevronRight else Icons.Rounded.PlayArrow,"Open $title",tint = Green) }
        }
    }
}
@Composable internal fun AutomationRow(scene: Scene,enabled: Boolean,pinned: Boolean,run: () -> Unit,edit: () -> Unit,pin: () -> Unit,delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp,top = 12.dp,bottom = 12.dp,end = 4.dp),verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = edit)) {
                Text(scene.name,style = MaterialTheme.typography.titleMedium)
                Text(if(scene.buttons.isEmpty()) "Empty sequence" else "${scene.buttons.size} ${if(scene.buttons.size == 1) "action" else "actions"}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = enabled,onClick = run) { Icon(Icons.Rounded.PlayArrow,"Run ${scene.name}",tint = if(enabled) Green else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert,"Automation options: ${scene.name}") }
                DropdownMenu(menu,{ menu = false }) {
                    DropdownMenuItem(text = { Text("Edit automation") },onClick = { menu = false; edit() })
                    DropdownMenuItem(text = { Text(if(pinned) "Remove from Favorites" else "Add to Favorites") },onClick = { menu = false; pin() })
                    DropdownMenuItem(text = { Text("Delete",color = MaterialTheme.colorScheme.error) },onClick = { menu = false; delete() })
                }
            }
        }
    }
}
@Composable internal fun EmptyState(icon: ImageVector,title: String,description: String,action: String? = null,click: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp,horizontal = 12.dp),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(34.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title,style = MaterialTheme.typography.titleLarge)
        Text(description,style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(action != null) FilledTonalButton(onClick = click) { Icon(Icons.Rounded.Add,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionSheet(connection: FlipperConnection,dismiss: () -> Unit,permission: ((Boolean) -> Unit) -> Unit) {
    val link = connection.link
    val connected by link.connected.collectAsState(); val connecting by link.connecting.collectAsState()
    val devices by link.discovered.collectAsState(); val scanning by link.scanning.collectAsState(); val scanStatus by link.scanStatus.collectAsState(); val status by link.status.collectAsState()
    var others by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    DisposableEffect(link) { onDispose { link.stopDiscovery() } }
    fun scan() { permission { granted -> if(granted) try { connection.findDevices(); error = "" } catch(e: Exception) { error = e.message ?: "Scan failed" } else error = "Allow Nearby devices to connect." } }
    ModalBottomSheet(onDismissRequest = dismiss,containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Flipper",style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
            when {
                connected -> {
                    Text("Connected",color = Green)
                    Text(link.verifiedDevice.value?.name ?: "Flipper Zero",style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { connection.disconnect(); dismiss() },modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                    TextButton(onClick = { connection.disconnect(); scan() }) { Text("Switch Flipper") }
                }
                connecting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Connecting…",style = MaterialTheme.typography.titleMedium)
                    Text("Confirm pairing on Flipper if asked.",color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { connection.disconnect(); dismiss() },modifier = Modifier.fillMaxWidth()) { Text("Cancel connection") }
                }
                else -> {
                    if(scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(scanning) "Looking for your Flipper…" else "Nearby devices",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val visible = devices.filter { it.nearby && it.connectable != false && (it.flipperCandidate || others) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(visible,key = { it.address }) { entry ->
                            ListItem(headlineContent = { Text(entry.name) },leadingContent = { Icon(Icons.Rounded.Bluetooth,null) },trailingContent = { Icon(Icons.Rounded.ChevronRight,null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),modifier = Modifier.clickable {
                                    try { connection.connect(entry); error = "" } catch(e: Exception) { error = e.message ?: "Couldn't connect" }
                                })
                        }
                        if(visible.isEmpty() && !scanning) item { Text("No Flipper found. Keep it nearby and close other Flipper apps.",Modifier.padding(vertical = 12.dp),style = MaterialTheme.typography.bodyMedium) }
                    }
                    if(!scanning && status != "Disconnected" && !status.startsWith("Reconnecting")) Text(status,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
                    OutlinedButton(enabled = !scanning,onClick = ::scan,modifier = Modifier.fillMaxWidth()) { Text("Scan again") }
                    TextButton(onClick = { others = !others }) { Text(if(others) "Show Flippers only" else "Other Bluetooth devices") }
                }
            }
        }
    }
}

@Composable internal fun RoomNameDialog(room: Room?,dismiss: () -> Unit,save: (String) -> Unit) {
    var name by remember { mutableStateOf(room?.name ?: "") }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,title = { Text(if(room == null) "Add room" else "Rename room") },text = {
        Column { OutlinedTextField(name,{ name = it; error = "" },label = { Text("Room name") },singleLine = true); if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error) }
    },confirmButton = { TextButton(enabled = name.isNotBlank(),onClick = { try { save(name) } catch(e: Exception) { error = e.message ?: "Couldn't save room" } }) { Text(if(room == null) "Add" else "Save") } },dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable internal fun DeleteRoomDialog(home: Home,room: Room,dismiss: () -> Unit,remove: (String?) -> Unit) {
    val destinations = home.rooms.filterNot { it.id == room.id }
    val remotes = home.remotes.count { it.room == room.id }; val signals = home.buttons.count { it.room == room.id }
    val occupied = remotes > 0 || signals > 0
    var deleteContents by remember { mutableStateOf(destinations.isEmpty()) }
    var destination by remember { mutableStateOf(destinations.firstOrNull()?.id) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,title = { Text("Delete ${room.name}?") },text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(occupied) {
                if(!deleteContents) {
                    Text("Move its remotes and signals to:")
                    ChoiceMenu("Room",destinations.firstOrNull { it.id == destination }?.name.orEmpty(),destinations.map { it.name }) { label -> destination = destinations.first { it.name == label }.id }
                } else Text("This removes $remotes ${if(remotes == 1) "remote" else "remotes"} and $signals ${if(signals == 1) "saved signal" else "saved signals"} from the app. Their automation steps will also be removed. Files stay on Flipper.")
                if(destinations.isNotEmpty()) TextButton(onClick = { deleteContents = !deleteContents }) { Text(if(deleteContents) "Move contents instead" else "Delete contents instead",color = if(deleteContents) Green else MaterialTheme.colorScheme.error) }
            } else Text("This room is empty.")
            if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
        }
    },confirmButton = { TextButton(onClick = { try { remove(if(occupied && !deleteContents) destination else null) } catch(e: Exception) { error = e.message ?: "Couldn't delete room" } }) {
        Text(if(occupied && !deleteContents) "Move & delete" else "Delete",color = MaterialTheme.colorScheme.error)
    } },dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable internal fun AppearanceDialog(current: Appearance,dismiss: () -> Unit,select: (Appearance) -> Unit) {
    AlertDialog(onDismissRequest = dismiss,title = { Text("Appearance") },text = {
        Column { Appearance.entries.forEach { mode -> Row(Modifier.fillMaxWidth().clickable { select(mode) },verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == current,{ select(mode) }); Text(mode.label)
        } } }
    },confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CreateRemoteDialog(room: String,dismiss: () -> Unit,create: (Remote) -> Unit) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf(RemoteCategory.LIGHTS) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,title = { Text("Add remote") },text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{ name = it },label = { Text("Remote name") },singleLine = true)
            Text("Layout",style = MaterialTheme.typography.labelLarge,color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteCategory.entries.forEach { option -> FilterChip(category == option,{ category = option; if(name.isBlank() && option == RemoteCategory.CHROMECAST) name = option.label },label = { Text(option.label) }) } }
            if(category == RemoteCategory.CHROMECAST) Text("Pair with Google TV over Wi-Fi. Add Flipper buttons to the same layout.",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
        }
    },confirmButton = { TextButton(enabled = name.isNotBlank(),onClick = { try { create(remoteTemplate(room,name.trim(),category)) } catch(e: Exception) { error = e.message ?: "Couldn't add remote" } }) { Text("Add") } },dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
