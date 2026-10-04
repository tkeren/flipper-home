package com.flipperhome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RemotePage(remote: Remote, home: Home, link: FlipperLink, busy: Boolean, snackbar: SnackbarHostState,
    back: () -> Unit, save: (Remote) -> Unit, bind: (String, ControlZone, RemoteButton) -> Unit,
    send: (RemoteButton) -> Unit, hold: (RemoteButton, Deferred<Unit>) -> Job?, heldStatus: String?, pin: (Shortcut) -> Unit, delete: () -> Unit,
    manageSignals: () -> Unit, saveAutomation: (Remote,String,ControlZone,Scene) -> Remote, runAutomation: (Scene) -> Unit,
    stopAutomation: (() -> Unit)? = null,
    openConnection: () -> Unit = {},
    tv: TvConnection,openTv: (String) -> Unit,saveTvAction: (Remote,String,ControlZone,TvDevice,TvKey) -> Remote,
) {
    var draft by remember(remote.id) { mutableStateOf(remote) }
    var editing by remember(remote.id) { mutableStateOf(false) }
    var selected by remember(remote.id) { mutableStateOf(remote.controls.firstOrNull()?.id) }
    var learn by remember { mutableStateOf<Pair<String,ControlZone>?>(null) }
    var discard by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Pair<String,ControlZone>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var automation by remember { mutableStateOf<Pair<String,ControlZone>?>(null) }
    var chooseAutomation by remember { mutableStateOf<Pair<String,ControlZone>?>(null) }
    var newAutomation by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(false) }
    var tvAction by remember { mutableStateOf<Pair<String,ControlZone>?>(null) }
    var newTvButton by remember { mutableStateOf<String?>(null) }
    val connected by link.connected.collectAsState()
    val connecting by link.connecting.collectAsState()
    val tvStates by tv.states.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(remote) { if(!editing) draft = remote }
    fun report(e: Exception) { scope.launch { snackbar.showSnackbar(e.message ?: "Could not save") } }
    fun commit(after: () -> Unit = {}) { try { save(draft); editing = false; after() } catch(e: Exception) { report(e) } }
    fun leave() { if(editing && draft != remote) discard = true else back() }
    BackHandler { leave() }
    val current = if(editing) draft else remote
    val actionIds = current.controls.flatMap { c -> c.bindings.values + c.routines.values.flatMap { id -> home.scenes.firstOrNull { it.id == id }?.buttons.orEmpty() } }
    val tvIds = (listOf(current.tvId) + actionIds.mapNotNull { id -> home.buttons.firstOrNull { it.id == id && it.isTv }?.tvId }).filter { it.isNotBlank() }.distinct()
    val control = current.controls.firstOrNull { it.id == selected }
    fun change(c: RemoteControl) { draft = draft.copy(controls = draft.controls.map { if(it.id == c.id) c else it }) }
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave() }) { Icon(Icons.Rounded.ArrowBack, "Back to rooms") }
                Column(Modifier.weight(1f)) { Text(current.name,style = MaterialTheme.typography.titleLarge,fontWeight = FontWeight.SemiBold); Text(home.rooms.firstOrNull { it.id == current.room }?.name.orEmpty(),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if(editing) TextButton(onClick = { commit() }) { Text("Save") }
                else TextButton(onClick = { draft = remote; editing = true }) { Text("Edit") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert,"Remote options") }
                    DropdownMenu(menu,{ menu = false }) {
                        DropdownMenuItem(text = { Text("Saved signals") },onClick = { menu = false; manageSignals() })
                        DropdownMenuItem(text = { Text("Favorite buttons") },onClick = { menu = false; favorites = true })
                        DropdownMenuItem(text = { Text(if(Shortcut(ShortcutKind.REMOTE,remote.id) in home.shortcuts) "Remove from Favorites" else "Add to Favorites") },onClick = { menu = false; pin(Shortcut(ShortcutKind.REMOTE,remote.id)) })
                        DropdownMenuItem(text = { Text("Remote settings") },onClick = { menu = false; settings = true })
                        DropdownMenuItem(text = { Text("Connect Flipper") },onClick = { menu = false; openConnection() })
                        tvIds.forEach { id -> DropdownMenuItem(text = { Text("Connect ${home.tvDevices.firstOrNull { it.id == id }?.name ?: "Chromecast"}") },onClick = { menu = false; openTv(id) }) }
                    }
                }
            }
            if(!editing) FlowRow(Modifier.padding(start = 20.dp,top = 8.dp,bottom = 12.dp,end = 16.dp),horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tvIds.forEach { id -> TvConnectionChip(tvStates[id] ?: TvState(message = if(home.tvDevices.firstOrNull { it.id == id }?.let { tv.paired(it) } == true) "Connect Chromecast" else "Pair Chromecast")) { openTv(id) } }
                if(tvIds.isEmpty() || actionIds.any { id -> home.buttons.firstOrNull { it.id == id }?.isTv == false }) ConnectionChip(connected,connecting,openConnection)
            }
            if(editing) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Drag to arrange · tap to edit",Modifier.weight(1f),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { draft = remote; editing = false }) { Text("Cancel") }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteCanvas(current, editing, selected, { c,zone -> !busy && (
                home.buttons.firstOrNull { it.id == c.bindings[zone] }?.let { home.canUse(it,connected) || it.isTv }
                    ?: home.scenes.firstOrNull { it.id == c.routines[zone] }?.let { home.canUse(it,connected) } ?: true)
            }, { if(!editing && !busy) { draft = remote; editing = true } }, { selected = it }, { selected = it; customizing = true }, { change(it) }, { c, zone, release ->
                home.buttons.firstOrNull { it.id == c.bindings[zone] }?.let { hold(it,release) }
            }) { c, zone ->
                val sceneId = c.routines[zone]
                if(sceneId != null || zone in c.automationZones) {
                    val scene = home.scenes.firstOrNull { it.id == sceneId }
                    if(scene == null || scene.buttons.isEmpty()) { draft = remote; selected = c.id; editing = true; chooseAutomation = c.id to zone }
                    else runAutomation(scene)
                } else {
                val button = home.buttons.firstOrNull { it.id == c.bindings[zone] }
                if(button == null) learn = c.id to zone else send(button)
                }
            }
            if(editing) {
                Row(Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { adding = true }, Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Text("Add button") }
                }

            }
        }
        PlaybackOverlay(heldStatus,stopAutomation)
        }
    }
                if(editing && customizing && control != null) ModalBottomSheet(onDismissRequest = { customizing = false },containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()).imePadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Edit button",style = MaterialTheme.typography.titleLarge); Text(control.mappingName(ControlZone.MAIN),color = Green,style = MaterialTheme.typography.bodyMedium) }; TextButton(onClick = { customizing = false }) { Text("Done") } }
                        val shapes = if(control.routines.isNotEmpty() || control.automationZones.isNotEmpty()) simpleShapes.filter { it.zones.size == 1 } else simpleShapes
                        ChoiceMenu("Shape", control.shape.label, shapes.map { it.label }) { label -> change(control.simpleSized(newShape = shapes.first { it.label == label })) }
                        OutlinedTextField(control.label, { change(control.copy(label = it, symbol = "")) }, label = { Text("Text (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Button size",style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Small", "Medium", "Large").forEachIndexed { i, label -> FilterChip(control.sizeIndex() == i, { change(control.simpleSized(i)) }, label = { Text(label) }) }
                        }
                        Text("Text size",style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ControlTextSize.entries.forEach { size -> FilterChip(control.textSize == size,{ change(control.copy(textSize = size)) },label = { Text(size.label) }) }
                        }
                        Text("Color",style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ControlColor.entries.forEach { choice ->
                                val (background,foreground) = controlColors(choice)
                                FilterChip(control.color == choice,{ change(control.copy(color = choice)) },label = { Text(choice.label) },
                                    leadingIcon = { Box(Modifier.size(16.dp).background(background,CircleShape).border(1.dp,foreground.copy(alpha = .5f),CircleShape)) })
                            }
                        }
                        control.shape.zones.forEach { zone ->
                            val button = home.buttons.firstOrNull { it.id == control.bindings[zone] }
                            val routine = home.scenes.firstOrNull { it.id == control.routines[zone] }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if(zone == ControlZone.MAIN) control.label else zone.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                if(routine != null) TextButton(onClick = { customizing = false; automation = control.id to zone }) { Text("Edit automation") }
                                else if(zone in control.automationZones) TextButton(onClick = { customizing = false; chooseAutomation = control.id to zone }) { Text("Choose automation") }
                                else if(button?.isTv == true) TextButton(enabled = !busy,onClick = { customizing = false; tvAction = control.id to zone }) { Text("${TvKey.entries.firstOrNull { it.code == button.tvKey }?.label ?: "TV command"} · Wi-Fi") }
                                else TextButton(enabled = !busy, onClick = { commit { learn = control.id to zone } }) { Text(if(button == null) "Learn with Flipper" else "Relearn") }
                                IconButton(onClick = { if(routine == null) existing = control.id to zone else { customizing = false; chooseAutomation = control.id to zone } }) { Icon(Icons.Rounded.Link, "Use a saved signal or automation for ${control.mappingName(zone)}") }
                            }
                            if(routine == null && zone !in control.automationZones) TextButton(onClick = { customizing = false; chooseAutomation = control.id to zone }) { Text("Use an automation") }
                            if(home.tvDevices.isNotEmpty()) TextButton(enabled = !busy,onClick = { customizing = false; tvAction = control.id to zone }) { Text("Choose Google TV command") }
                            if(button?.isTv == true) TextButton(enabled = !busy,onClick = { commit { learn = control.id to zone } }) { Text("Replace with Flipper signal") }
                        }
                        TextButton(onClick = { draft = draft.copy(controls = draft.controls.filterNot { it.id == control.id }); selected = draft.controls.firstOrNull()?.id; customizing = false }) { Text("Remove from layout") }
                    }
                }
    if(discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard layout changes?") }, confirmButton = { TextButton(onClick = back) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    if(adding) AlertDialog(onDismissRequest = { adding = false },title = { Text("Add a button") },text = { Column {
        Button(onClick = { val c = RemoteControl(label = "Button ${draft.controls.size+1}",y = .45f); draft = draft.copy(controls = draft.controls + c); selected = c.id; customizing = true; adding = false },modifier = Modifier.fillMaxWidth()) { Text("Flipper signal") }
        if(home.tvDevices.isNotEmpty()) OutlinedButton(onClick = { val c = RemoteControl(label = "TV button",y = .45f); draft = draft.copy(controls = draft.controls + c); selected = c.id; newTvButton = c.id; tvAction = c.id to ControlZone.MAIN; adding = false },modifier = Modifier.fillMaxWidth()) { Text("Google TV command") }
        OutlinedButton(onClick = { val c = RemoteControl(label = "Automation",shape = ControlShape.ROUNDED,width = 112f,height = 72f,y = .45f,automationZones = setOf(ControlZone.MAIN)); draft = draft.copy(controls = draft.controls + c); selected = c.id; newAutomation = c.id; chooseAutomation = c.id to ControlZone.MAIN; adding = false },modifier = Modifier.fillMaxWidth()) { Text("Automation") }
    } },confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    if(favorites) ModalBottomSheet(onDismissRequest = { favorites = false },containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Favorite buttons",style = MaterialTheme.typography.titleLarge)
            val learned = current.controls.flatMap { c -> c.shape.zones.mapNotNull { zone -> home.buttons.firstOrNull { it.id == c.bindings[zone] } } }.distinctBy { it.id }
            val routines = current.controls.flatMap { it.routines.values }.distinct().mapNotNull { id -> home.scenes.firstOrNull { it.id == id } }
            if(learned.isEmpty() && routines.isEmpty()) Text("Learn a button first.",Modifier.padding(vertical = 20.dp),color = MaterialTheme.colorScheme.onSurfaceVariant)
            learned.forEach { b -> Row(verticalAlignment = Alignment.CenterVertically) { Text(b.name,Modifier.weight(1f)); PinButton(Shortcut(ShortcutKind.BUTTON,b.id) in home.shortcuts) { pin(Shortcut(ShortcutKind.BUTTON,b.id)) } } }
            routines.forEach { scene -> Row(verticalAlignment = Alignment.CenterVertically) { Text(scene.name,Modifier.weight(1f)); PinButton(Shortcut(ShortcutKind.ROUTINE,scene.id) in home.shortcuts) { pin(Shortcut(ShortcutKind.ROUTINE,scene.id)) } } }
        }
    }
    chooseAutomation?.let { target -> AutomationChoiceDialog(home,{
        if(newAutomation == target.first) draft = draft.copy(controls = draft.controls.filterNot { it.id == target.first })
        newAutomation = null; chooseAutomation = null
    },{ chooseAutomation = null; automation = target }) { scene ->
        try {
            draft = saveAutomation(draft,target.first,target.second,scene)
            selected = target.first; editing = true; customizing = true; newAutomation = null; chooseAutomation = null
        } catch(e: Exception) { report(e) }
    } }
    tvAction?.let { target -> TvActionDialog(home,home.buttons.firstOrNull { it.id == draft.controls.first { c -> c.id == target.first }.bindings[target.second] },current.tvId,{
        if(newTvButton == target.first) { draft = draft.copy(controls = draft.controls.filterNot { it.id == target.first }); selected = draft.controls.firstOrNull()?.id }
        customizing = newTvButton == null; newTvButton = null; tvAction = null
    }) { device,key ->
        try {
            val c = draft.controls.first { it.id == target.first }
            val layout = if(target.second == ControlZone.MAIN && c.label == "TV button") draft.copy(controls = draft.controls.map { if(it.id == c.id) it.copy(label = key.label) else it }) else draft
            draft = saveTvAction(layout,target.first,target.second,device,key); selected = target.first; editing = true; tvAction = null; newTvButton = null; customizing = true
        } catch(e: Exception) { report(e) }
    } }
    automation?.let { target ->
        val c = draft.controls.first { it.id == target.first }
        AutomationEditor(home,initial = home.scenes.firstOrNull { it.id == c.routines[target.second] },initialRemote = remote.id,
            suggestedName = if(newAutomation == c.id) "" else c.label,dismiss = {
                if(newAutomation == c.id) draft = draft.copy(controls = draft.controls.filterNot { it.id == c.id })
                newAutomation = null; automation = null
            }) { scene ->
            draft = saveAutomation(draft,c.id,target.second,scene)
            selected = c.id; editing = true; customizing = true; newAutomation = null; automation = null
        }
    }
    if(settings) RemoteSettingsDialog(draft, home.rooms, { settings = false }, { deleting = true; settings = false }) { changed ->
        if(editing) { draft = changed; settings = false } else try { save(changed); draft = changed; settings = false } catch(e: Exception) { report(e) }
    }
    if(deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${remote.name}?") }, text = { Text("Remove this remote and its buttons from the app. Its actions will be removed from routines. Signal files stay on Flipper.") },
        confirmButton = { TextButton(onClick = { try { delete() } catch(e: Exception) { report(e) } }) { Text("Delete remote") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    existing?.let { target -> AlertDialog(onDismissRequest = { existing = null }, title = { Text("Use a saved action") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if(home.scenes.any { it.buttons.isNotEmpty() }) {
                Text("Automations",style = MaterialTheme.typography.titleSmall)
                home.scenes.filter { it.buttons.isNotEmpty() }.forEach { scene -> TextButton(onClick = {
                    val c = draft.controls.first { it.id == target.first }
                    change(c.copy(routines = c.routines + (target.second to scene.id),bindings = c.bindings - target.second,automationZones = c.automationZones + target.second,
                        label = if(target.second == ControlZone.MAIN) scene.name else c.label)); existing = null
                }) { Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(18.dp)); Text(scene.name,Modifier.padding(start = 8.dp)) } }
                HorizontalDivider(); Text("Buttons",style = MaterialTheme.typography.titleSmall)
            }
            home.buttons.forEach { b -> TextButton(onClick = {
                val c = draft.controls.first { it.id == target.first }; change(c.copy(bindings = c.bindings + (target.second to b.id),routines = c.routines - target.second,automationZones = c.automationZones - target.second)); existing = null
            }) { Text("${b.device} · ${b.name}${if(b.isTv) " · Wi-Fi" else ""}") } }
            if(home.buttons.isEmpty()) Text("Learn a command first.")
        }
    }, confirmButton = { TextButton(onClick = { existing = null }) { Text("Close") } }) }
    learn?.let { target ->
        val c = remote.controls.firstOrNull { it.id == target.first }
        val next = remote.nextUnmapped(target)
        val nextName = next?.let { (id,zone) -> remote.controls.first { it.id == id }.mappingName(zone) }
        if(c != null) key(target) { LearnButtonDialog(remote, c, target.second, home.buttons, link, { learn = null }, { changed -> save(changed) },
            if(next == null) null else { captured -> bind(target.first,target.second,captured); selected = next.first; learn = next },nextName,openConnection) { captured -> bind(target.first,target.second,captured); learn = null } }
    }
}

@Composable
private fun RemoteCanvas(remote: Remote, editing: Boolean, selected: String?, enabled: (RemoteControl,ControlZone) -> Boolean,
    edit: () -> Unit, select: (String) -> Unit, customize: (String) -> Unit, move: (RemoteControl) -> Unit,
    hold: (RemoteControl,ControlZone,Deferred<Unit>) -> Job?, tap: (RemoteControl,ControlZone) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val beginEdit by rememberUpdatedState(edit)
    BoxWithConstraints(Modifier.fillMaxWidth().height(remote.canvasHeight.dp).background(colors.surface,RoundedCornerShape(24.dp)).border(1.dp,colors.outlineVariant.copy(alpha = .6f),RoundedCornerShape(24.dp))
        .pointerInput(remote.id,editing) { if(!editing) detectTapGestures(onLongPress = { beginEdit() }) }) {
        val canvasWidth = maxWidth.value
        if(editing && remote.snap) Canvas(Modifier.fillMaxSize()) {
            val step = 16.dp.toPx()
            var x = step
            while(x < size.width) { var y = step; while(y < size.height) { drawCircle(colors.outlineVariant,1.dp.toPx(),Offset(x,y)); y += step }; x += step }
        }
        remote.controls.forEach { raw -> key(raw.id) {
            val c = raw.positioned(raw.x,raw.y,canvasWidth,remote.canvasHeight,false)
            val latest by rememberUpdatedState(c)
            val mover by rememberUpdatedState(move)
            val selector by rememberUpdatedState(select)
            val pixelWidth = with(density) { canvasWidth.dp.toPx() }
            val pixelHeight = with(density) { remote.canvasHeight.dp.toPx() }
            Column(Modifier.offset { IntOffset(with(density) { (c.x*canvasWidth-c.width/2).dp.toPx() }.roundToInt(), with(density) { (c.y*remote.canvasHeight-c.height/2).dp.toPx() }.roundToInt()) }
                .width(c.width.dp)
                .then(if(editing) Modifier.pointerInput(c.id,c.pinned,remote.snap) {
                    var dragX = 0f; var dragY = 0f
                    detectDragGestures(onDragStart = { selector(c.id); dragX = latest.x; dragY = latest.y }) { change, delta ->
                        change.consume()
                        if(!latest.pinned) { dragX += delta.x/pixelWidth; dragY += delta.y/pixelHeight
                            mover(latest.positioned(dragX,dragY,canvasWidth,remote.canvasHeight,remote.snap)) }
                    }
                }.clickable { customize(c.id) } else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                val palette = controlColors(c.color)
                val color = palette.first
                val textColor = palette.second
                Box(Modifier.size(c.width.dp,c.height.dp)) {
                    @Composable fun content(zone: ControlZone, symbol: String, modifier: Modifier, shape: Shape = CircleShape) {
                        val canPress = enabled(c,zone)
                        val automation = c.routines[zone] != null || zone in c.automationZones
                        val mapped = c.bindings[zone] != null || c.routines[zone] != null
                        val label = if(zone == ControlZone.MAIN) c.label else "${c.label} ${zone.label}"
                        val highlighted = editing && selected == c.id
                        Box(modifier.clip(shape).background(color,shape).border(if(highlighted) 2.dp else 1.dp,if(highlighted) colors.primary else colors.outlineVariant.copy(alpha = if(mapped) 1f else .5f),shape)
                            .then(if(editing) Modifier else if(automation) Modifier.clickable(enabled = !mapped || canPress,role = Role.Button) { tap(c,zone) }
                                else if(mapped) Modifier.remotePress("${c.id}:$zone",canPress,{ tap(c,zone) }, { release -> hold(c,zone,release) })
                                else Modifier.clickable(role = Role.Button, onClickLabel = "Learn $label") { tap(c,zone) })
                            .semantics { contentDescription = "$label${if(automation) ", automation" else if(!mapped) ", unlearned" else ""}" }, contentAlignment = Alignment.Center) {
                            val inset = if(c.shape.zones.size > 1) PaddingValues(5.dp) else when(c.shape) {
                                ControlShape.UP -> PaddingValues(start = (c.width*.24f).dp,end = (c.width*.24f).dp,top = (c.height*.40f).dp,bottom = (c.height*.12f).dp)
                                ControlShape.DOWN -> PaddingValues(start = (c.width*.24f).dp,end = (c.width*.24f).dp,top = (c.height*.12f).dp,bottom = (c.height*.40f).dp)
                                ControlShape.LEFT -> PaddingValues(start = (c.width*.40f).dp,end = (c.width*.12f).dp,top = (c.height*.24f).dp,bottom = (c.height*.24f).dp)
                                ControlShape.RIGHT -> PaddingValues(start = (c.width*.12f).dp,end = (c.width*.40f).dp,top = (c.height*.24f).dp,bottom = (c.height*.24f).dp)
                                ControlShape.CIRCLE,ControlShape.OVAL,ControlShape.DIAMOND -> PaddingValues(horizontal = (c.width*.17f).dp,vertical = (c.height*.17f).dp)
                                else -> PaddingValues(6.dp)
                            }
                            BoxWithConstraints(Modifier.fillMaxSize().padding(inset),contentAlignment = Alignment.Center) {
                                val fontHeight = with(density) { (c.textSize.sp*1.15f).sp.toDp() }
                                // Keep the marker within the shape's safe center; tiny shapes prioritize text.
                                val showIcon = automation && maxHeight >= fontHeight + 14.dp
                                val lines = if(maxHeight >= fontHeight*2 + (if(showIcon) 14.dp else 0.dp)) 2 else 1
                                Column(horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if(showIcon) Icon(Icons.Rounded.AutoAwesome,null,Modifier.size(12.dp),tint = textColor)
                                    val contentColor = textColor.copy(alpha = if(editing || canPress || !mapped) 1f else .65f)
                                    if(symbol == "⏻") Icon(Icons.Rounded.PowerSettingsNew,null,Modifier.size((c.textSize.sp + 8).dp),tint = contentColor)
                                    else Text(symbol.ifBlank { if(c.showLabel) c.label else "" },color = contentColor,
                                        fontSize = c.textSize.sp.sp,lineHeight = (c.textSize.sp*1.15f).sp,fontWeight = FontWeight.SemiBold,maxLines = lines,overflow = TextOverflow.Ellipsis,textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    when(c.shape) {
                        ControlShape.ROCKER_HORIZONTAL -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            content(ControlZone.PLUS,"+",Modifier.weight(1f).fillMaxHeight(),RoundedCornerShape(topStart = 50.dp,bottomStart = 50.dp,topEnd = 8.dp,bottomEnd = 8.dp))
                            content(ControlZone.MINUS,"−",Modifier.weight(1f).fillMaxHeight(),RoundedCornerShape(topStart = 8.dp,bottomStart = 8.dp,topEnd = 50.dp,bottomEnd = 50.dp))
                        }
                        ControlShape.ROCKER_VERTICAL -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            content(ControlZone.PLUS,"+",Modifier.weight(1f).fillMaxWidth(),RoundedCornerShape(topStart = 50.dp,topEnd = 50.dp,bottomStart = 8.dp,bottomEnd = 8.dp))
                            content(ControlZone.MINUS,"−",Modifier.weight(1f).fillMaxWidth(),RoundedCornerShape(topStart = 8.dp,topEnd = 8.dp,bottomStart = 50.dp,bottomEnd = 50.dp))
                        }
                        ControlShape.DPAD -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.weight(1f)) { Spacer(Modifier.weight(1f)); content(ControlZone.UP,"↑",Modifier.weight(1f).fillMaxHeight()); Spacer(Modifier.weight(1f)) }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { content(ControlZone.LEFT,"←",Modifier.weight(1f).fillMaxHeight()); content(ControlZone.OK,"OK",Modifier.weight(1f).fillMaxHeight()); content(ControlZone.RIGHT,"→",Modifier.weight(1f).fillMaxHeight()) }
                            Row(Modifier.weight(1f)) { Spacer(Modifier.weight(1f)); content(ControlZone.DOWN,"↓",Modifier.weight(1f).fillMaxHeight()); Spacer(Modifier.weight(1f)) }
                        }
                        else -> content(ControlZone.MAIN,c.symbol,Modifier.fillMaxSize(),controlShape(c.shape))
                    }
                }
                if(c.showLabel && c.label.isNotBlank() && c.shape.zones.size > 1) Text(c.label, fontSize = c.textSize.sp.sp, color = Ink, maxLines = 1,overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if(editing && c.pinned) Icon(Icons.Rounded.Lock,null,Modifier.size(12.dp),tint = Green)
            }
        } }
        if(remote.controls.isEmpty()) Text("Tap Edit to add your first button", Modifier.align(Alignment.Center), color = Green)
    }
}

internal fun controlShape(shape: ControlShape): Shape = when(shape) {
    ControlShape.CIRCLE, ControlShape.OVAL -> CircleShape
    ControlShape.PILL -> RoundedCornerShape(50)
    ControlShape.ROUNDED -> RoundedCornerShape(18.dp)
    ControlShape.RECTANGLE, ControlShape.SQUARE -> RoundedCornerShape(4.dp)
    else -> GenericShape { size, _ ->
        when(shape) {
            ControlShape.UP -> { moveTo(size.width/2,0f); lineTo(size.width,size.height); lineTo(0f,size.height) }
            ControlShape.DOWN -> { moveTo(0f,0f); lineTo(size.width,0f); lineTo(size.width/2,size.height) }
            ControlShape.LEFT -> { moveTo(0f,size.height/2); lineTo(size.width,0f); lineTo(size.width,size.height) }
            ControlShape.RIGHT -> { moveTo(0f,0f); lineTo(size.width,size.height/2); lineTo(0f,size.height) }
            else -> { moveTo(size.width/2,0f); lineTo(size.width,size.height/2); lineTo(size.width/2,size.height); lineTo(0f,size.height/2) }
        }; close()
    }
}

@Composable internal fun ChoiceMenu(label: String, value: String, options: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $value", Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore,null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); expanded = false }) } }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RemoteSettingsDialog(remote: Remote, rooms: List<Room>, dismiss: () -> Unit, remove: () -> Unit, save: (Remote) -> Unit) {
    var draft by remember { mutableStateOf(remote) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Remote settings") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true)
        ChoiceMenu("Room",rooms.first { it.id == draft.room }.name,rooms.map { it.name }) { label -> draft = draft.copy(room = rooms.first { it.name == label }.id) }
        TextButton(onClick = remove) { Text("Delete remote",color = MaterialTheme.colorScheme.error) }
        if(error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(enabled = draft.name.isNotBlank(), onClick = { try { save(draft.copy(name = draft.name.trim())) } catch(e: Exception) { error = e.message ?: "Check settings" } }) { Text("Save settings") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
