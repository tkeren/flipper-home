package com.flipperhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable internal fun HomeApp(store: HomeStore, connection: FlipperConnection, appearance: Appearance,
    changeAppearance: (Appearance) -> Unit, permission: ((Boolean) -> Unit) -> Unit,
) {
    val link = connection.link
    val context = LocalContext.current.applicationContext
    val tv = remember { TvConnection(context) }
    var home by remember { mutableStateOf(store.load()) }
    var tab by remember { mutableIntStateOf(0) }
    var roomId by remember { mutableStateOf<String?>(null) }
    var remoteId by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf("") }
    var connectionOpen by remember { mutableStateOf(false) }
    var editRoom by remember { mutableStateOf<Room?>(null) }
    var removeRoom by remember { mutableStateOf<Room?>(null) }
    var editShortcuts by remember { mutableStateOf(false) }
    var manualBusyUi by remember { mutableStateOf(false) }
    val voice = connection.voice
    val voiceBusy by voice.busy.collectAsState()
    val voiceName by voice.activeName.collectAsState()
    val busy = manualBusyUi || voiceBusy
    fun setBusy(value: Boolean) { manualBusyUi = value; connection.manualBusy.value = value }
    var voiceOpen by remember { mutableStateOf(false) }
    var heldJob by remember { mutableStateOf<Job?>(null) }
    var heldStatus by remember { mutableStateOf<String?>(null) }
    var sequenceJob by remember { mutableStateOf<Job?>(null) }
    var signalsOpen by remember { mutableStateOf(false) }
    var signalsRemote by remember { mutableStateOf<String?>(null) }
    var editScene by remember { mutableStateOf<Scene?>(null) }
    var tvSetup by remember { mutableStateOf<String?>(null) }
    var heldUsesFlipper by remember { mutableStateOf(false) }
    var sequenceUsesFlipper by remember { mutableStateOf(false) }
    val connected by link.connected.collectAsState()
    val connecting by link.connecting.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) {
                val hold = heldJob; val sequence = sequenceJob
                hold?.cancel(); sequence?.cancel()
                scope.launch { withTimeoutOrNull(3000) { hold?.join(); sequence?.join() }; if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) tv.disconnectAll() }
            }
            if(event == Lifecycle.Event.ON_START) home.tvDevices.firstOrNull { it.id == home.remotes.firstOrNull { r -> r.id == remoteId }?.tvId }?.let { device ->
                if(tv.paired(device)) scope.launch { runCatching { tv.connect(device) } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); heldJob?.cancel(); sequenceJob?.cancel(); tv.close() }
    }
    LaunchedEffect(connected) { if(!connected) { if(heldUsesFlipper) heldJob?.cancel(); if(sequenceUsesFlipper) sequenceJob?.cancel() } }
    val sender = UniversalSender({ button,release,duration,started -> link.transmit(button,release,duration,started) },
        { button,release,duration,started -> tv.transmit(home.tvDevices.firstOrNull { it.id == button.tvId } ?: error("Choose a Chromecast first"),button.tvKey,release,duration,started) })
    fun update(next: Home) { store.save(next); home = next; voice.updateActions() }
    fun safely(next: Home) { try { update(next) } catch(e: Exception) { scope.launch { snackbar.showSnackbar(e.message ?: "Save failed") } } }
    fun action(block: suspend () -> Unit): Job? {
        if(busy) return null
        setBusy(true)
        return scope.launch {
            val failure = try { block(); null } catch(e: CancellationException) { throw e } catch(e: Exception) { e.message ?: "Action failed" } finally { setBusy(false) }
            if(failure != null) snackbar.showSnackbar(failure)
        }
    }
    fun pin(shortcut: Shortcut) { safely(home.copy(shortcuts = if(shortcut in home.shortcuts) home.shortcuts - shortcut else home.shortcuts + shortcut)) }
    fun send(button: RemoteButton) {
        if(button.isTv && !home.canUse(button,connected)) { tvSetup = button.tvId; return }
        action { sender.transmit(button) }
    }
    fun hold(button: RemoteButton, release: Deferred<Unit>): Job? {
        if(busy) return null
        if(!home.canUse(button,connected)) { if(button.isTv) tvSetup = button.tvId; return null }
        heldUsesFlipper = !button.isTv
        setBusy(true); heldStatus = "Starting ${button.name}…"
        return scope.launch {
            val failure = try { sender.transmit(button,release,started = { heldStatus = "Sending ${button.name}" }); null }
            catch(e: CancellationException) { throw e } catch(e: Exception) { e.message ?: "Send failed" }
            finally { setBusy(false); heldStatus = null; heldJob = null; heldUsesFlipper = false }
            if(failure != null) snackbar.showSnackbar(failure)
        }.also { heldJob = it }
    }
    fun run(scene: Scene) {
        if(busy) return
        sequenceUsesFlipper = scene.buttons.any { id -> home.buttons.firstOrNull { it.id == id }?.isTv == false }
        sequenceJob = action { try { RoutineRunner.run(scene,home.buttons) { button, duration -> sender.transmit(button,duration = duration) } } finally { sequenceJob = null; sequenceUsesFlipper = false } }
    }
    fun openConnection() {
        connectionOpen = true
        if(!connected && !connecting) permission { granted ->
            if(granted) try { connection.findDevices() } catch(e: Exception) { scope.launch { snackbar.showSnackbar(e.message ?: "Scan failed") } }
        }
    }
    fun openRemote(remote: Remote) { remoteId = remote.id }
    fun openSignals(remote: String? = null) { signalsRemote = remote; signalsOpen = true }
    if(connectionOpen) ConnectionSheet(connection,{ connectionOpen = false },permission)
    if(voiceOpen) VoiceSettingsSheet(voice) { voiceOpen = false }
    home.tvDevices.firstOrNull { it.id == tvSetup }?.let { device -> TvSetupSheet(device,tv,{ tvSetup = null }) { updated ->
        update(home.copy(tvDevices = home.tvDevices.map { if(it.id == updated.id) updated else it }))
    } }
    LaunchedEffect(remoteId) {
        home.tvDevices.firstOrNull { it.id == home.remotes.firstOrNull { r -> r.id == remoteId }?.tvId }?.let { device ->
            if(tv.paired(device)) runCatching { tv.connect(device) }
        }
    }

    if(signalsOpen) {
        SignalLibrary(home,signalsRemote,link,busy,snackbar,{ signalsOpen = false },::update,::send)
        return
    }
    val remote = home.remotes.firstOrNull { it.id == remoteId }
    if(remote != null) {
        RemotePage(remote,home,link,busy,snackbar,{ remoteId = null },{ update(home.withRemote(it)) },
            { control, zone, button -> update(home.bind(remote.id,control,zone,button)) },::send,::hold,heldStatus ?: voiceName?.let { "Voice · $it" },::pin,{
                val removed = home.buttons.filter { it.remoteId == remote.id }.map { it.id }
                val cleaned = removed.fold(home) { current, id -> current.deleteSignal(id) }
                update(cleaned.copy(remotes = cleaned.remotes.filterNot { it.id == remote.id },shortcuts = cleaned.shortcuts.filterNot { it == Shortcut(ShortcutKind.REMOTE,remote.id) }))
                remoteId = null
            },manageSignals = { openSignals(remote.id) },saveAutomation = { layout,control,zone,scene ->
                val next = home.withAutomation(layout,control,zone,scene); update(next); next.remotes.first { it.id == layout.id }
            },
            runAutomation = ::run,stopAutomation = if(voiceBusy) ({ voice.cancelCommand() }) else if(sequenceJob?.isActive == true) ({ sequenceJob?.cancel() }) else null,
            openConnection = ::openConnection,tv = tv,openTv = { tvSetup = it },saveTvAction = { layout,control,zone,device,key ->
                val next = home.bindTv(layout,control,zone,device,key); update(next); next.remotes.first { it.id == layout.id }
            },voice = voice,saveVoice = ::update,openVoice = { voiceOpen = true },stopLabel = if(voiceBusy) "Stop command" else "Stop automation")
        return
    }
    val room = home.rooms.firstOrNull { it.id == roomId }
    BackHandler(room != null && tab == 0) { roomId = null }
    Scaffold(containerColor = Paper,snackbarHost = { SnackbarHost(snackbar) },topBar = {
        AppHeader(title = if(tab == 0) room?.name ?: "Rooms" else if(tab == 1) "Favorites" else "Automations",
            back = if(tab == 0 && room != null) ({ roomId = null }) else null,connected = connected,connecting = connecting,connect = ::openConnection,
            add = if(tab == 0) ({ if(room == null) { editRoom = null; dialog = "room" } else dialog = "remote" })
                else if(tab == 2) ({ editScene = null; dialog = "scene" }) else null,
            addLabel = if(tab == 2) "Add automation" else if(room == null) "Add room" else "Add remote",signals = { openSignals() },
            appearance = { dialog = "appearance" },rename = if(room != null && tab == 0) ({ editRoom = room; dialog = "room" }) else null,
            delete = if(room != null && tab == 0) ({ removeRoom = room }) else null,
            importSignal = if(room != null && tab == 0) ({ dialog = "button" }) else null,voice = { voiceOpen = true })
    },bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.background,tonalElevation = 0.dp) {
            listOf("Rooms" to Icons.Rounded.MeetingRoom,"Favorites" to Icons.Rounded.StarBorder,"Automations" to Icons.Rounded.AutoAwesome).forEachIndexed { index,pair ->
                NavigationBarItem(selected = tab == index,onClick = { tab = index; roomId = null },icon = { Icon(pair.second,null) },label = { Text(pair.first) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp),contentPadding = PaddingValues(top = 12.dp,bottom = 88.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when(tab) {
                0 -> if(room == null) {
                    if(home.rooms.isEmpty()) item { EmptyState(Icons.Rounded.MeetingRoom,"No rooms yet","Add a room to organize your remotes.","Add room") { editRoom = null; dialog = "room" } }
                    items(home.rooms,key = { it.id }) { r -> RoomRow(r,home.remotes.count { it.room == r.id },{ roomId = r.id },
                        { editRoom = r; dialog = "room" },{ removeRoom = r }) }
                } else {
                    val remotes = home.remotes.filter { it.room == room.id }
                    if(remotes.isEmpty()) item { EmptyState(Icons.Rounded.SettingsRemote,"No remotes","Add your first remote to ${room.name}.","Add remote") { dialog = "remote" } }
                    items(remotes,key = { it.id }) { r -> RemoteRow(r,Shortcut(ShortcutKind.REMOTE,r.id) in home.shortcuts,{ openRemote(r) }) { pin(Shortcut(ShortcutKind.REMOTE,r.id)) } }
                }
                1 -> {
                    if(home.shortcuts.isEmpty()) item { EmptyState(Icons.Rounded.StarBorder,"No favorites","Star a remote, button or automation to keep it here.") }
                    else item { Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) { TextButton(onClick = { editShortcuts = !editShortcuts }) { Text(if(editShortcuts) "Done" else "Arrange") } } }
                    items(home.shortcuts,key = { "${it.kind}:${it.id}" }) { shortcut ->
                        val button = home.buttons.firstOrNull { it.id == shortcut.id }; val r = home.remotes.firstOrNull { it.id == shortcut.id }; val scene = home.scenes.firstOrNull { it.id == shortcut.id }
                        val title = when(shortcut.kind) { ShortcutKind.BUTTON -> button?.name; ShortcutKind.REMOTE -> r?.name; ShortcutKind.ROUTINE -> scene?.name }
                        val detail = when(shortcut.kind) { ShortcutKind.BUTTON -> button?.device; ShortcutKind.REMOTE -> home.rooms.firstOrNull { it.id == r?.room }?.name; ShortcutKind.ROUTINE -> "${scene?.buttons?.size ?: 0} actions" }
                        val available = when(shortcut.kind) { ShortcutKind.REMOTE -> true; ShortcutKind.BUTTON -> button?.let { home.canUse(it,connected) || it.isTv } == true; ShortcutKind.ROUTINE -> scene?.let { home.canUse(it,connected) } == true }
                        if(title != null) FavoriteRow(title,detail.orEmpty(),shortcut.kind,!busy && available,
                            { when(shortcut.kind) { ShortcutKind.BUTTON -> button?.let(::send); ShortcutKind.REMOTE -> r?.let(::openRemote); ShortcutKind.ROUTINE -> scene?.let(::run) } },
                            if(shortcut.kind == ShortcutKind.BUTTON && button != null) ({ release -> hold(button,release) }) else null,
                            if(editShortcuts) ({ pin(shortcut) }) else null,
                            if(editShortcuts && home.shortcuts.indexOf(shortcut) > 0) ({ val list = home.shortcuts.toMutableList(); val index = list.indexOf(shortcut); list.add(index-1,list.removeAt(index)); safely(home.copy(shortcuts = list)) }) else null)
                    }
                }
                2 -> {
                    if(home.scenes.isEmpty()) item { EmptyState(Icons.Rounded.AutoAwesome,"No automations","Combine buttons into a sequence.","Add automation") { editScene = null; dialog = "scene" } }
                    items(home.scenes,key = { it.id }) { scene -> AutomationRow(scene,!busy && home.canUse(scene,connected),Shortcut(ShortcutKind.ROUTINE,scene.id) in home.shortcuts,
                        { run(scene) },{ editScene = scene; dialog = "scene" },{ pin(Shortcut(ShortcutKind.ROUTINE,scene.id)) },{ editScene = scene; dialog = "deleteScene" }) }
                }
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter))
        PlaybackOverlay(heldStatus ?: voiceName?.let { "Voice · $it" },if(voiceBusy) ({ voice.cancelCommand() }) else if(sequenceJob?.isActive == true) ({ sequenceJob?.cancel(); Unit }) else null,if(voiceBusy) "Stop command" else "Stop automation")
        }
    }
    when(dialog) {
        "room" -> RoomNameDialog(editRoom,{ dialog = "" }) { name ->
            val existing = editRoom
            val next = if(existing == null) home.addRoom(name) else home.renameRoom(existing.id,name)
            update(next); if(existing == null) roomId = next.rooms.last().id; dialog = ""
        }
        "remote" -> room?.let { CreateRemoteDialog(it.id,{ dialog = "" }) { r ->
            update(if(r.category == RemoteCategory.CHROMECAST) home.withChromecast(r) else home.withRemote(r)); dialog = ""; openRemote(r)
            if(r.category == RemoteCategory.CHROMECAST) tvSetup = r.id
        } }
        "button" -> room?.let { ButtonDialog(it.id,link,{ dialog = "" }) { update(home.copy(buttons = home.buttons + it).migrateRemotes()); dialog = "" } }
        "scene" -> AutomationEditor(home,initial = editScene,initialRoom = roomId,dismiss = { dialog = "" }) { scene -> update(home.replaceRoutine(scene)); dialog = "" }
        "deleteScene" -> editScene?.let { scene -> AlertDialog(onDismissRequest = { dialog = "" },title = { Text("Delete ${scene.name}?") },
            confirmButton = { TextButton(onClick = { safely(home.deleteRoutine(scene.id)); dialog = "" }) { Text("Delete",color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }) }
        "appearance" -> AppearanceDialog(appearance,{ dialog = "" }) { changeAppearance(it); dialog = "" }
    }
    removeRoom?.let { r -> DeleteRoomDialog(home,r,{ removeRoom = null }) { target ->
        update(home.deleteRoom(r.id,target)); if(roomId == r.id) roomId = null; removeRoom = null
    } }
}

@Composable internal fun PinButton(pinned: Boolean,click: () -> Unit) {
    IconButton(onClick = click) { Icon(if(pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,if(pinned) "Remove from Favorites" else "Add to Favorites",tint = if(pinned) Green else MaterialTheme.colorScheme.onSurfaceVariant,modifier = Modifier.size(21.dp)) }
}
