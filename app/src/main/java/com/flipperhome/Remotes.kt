package com.flipperhome

import java.util.UUID
import kotlin.math.round

enum class RemoteCategory(val label: String) { LIGHTS("Lights"), TV("TV"), CHROMECAST("Chromecast"), FAN("Fan"), CUSTOM("Blank") }
enum class ControlZone(val label: String) { MAIN("Button"), PLUS("+"), MINUS("−"), UP("Up"), LEFT("Left"), OK("OK"), RIGHT("Right"), DOWN("Down") }
enum class ControlTextSize(val label: String, val sp: Int) { SMALL("Small",14), MEDIUM("Medium",18), LARGE("Large",22) }
enum class ControlColor(val label: String) { DEFAULT("Default"), MINT("Mint"), BLUE("Blue"), LAVENDER("Lavender"), PEACH("Peach") }
enum class ControlShape(val label: String) {
    CIRCLE("Circle"), OVAL("Oval"), PILL("Pill"), ROUNDED("Rounded rectangle"), RECTANGLE("Rectangle"), SQUARE("Square"),
    UP("Triangle up"), DOWN("Triangle down"), LEFT("Triangle left"), RIGHT("Triangle right"), DIAMOND("Diamond"),
    ROCKER_HORIZONTAL("Horizontal + / −"), ROCKER_VERTICAL("Vertical + / −"), DPAD("Directional pad");
    val zones: List<ControlZone> get() = when(this) {
        ROCKER_HORIZONTAL, ROCKER_VERTICAL -> listOf(ControlZone.PLUS, ControlZone.MINUS)
        DPAD -> listOf(ControlZone.UP, ControlZone.LEFT, ControlZone.OK, ControlZone.RIGHT, ControlZone.DOWN)
        else -> listOf(ControlZone.MAIN)
    }
    val equalSides: Boolean get() = this in listOf(CIRCLE, SQUARE, DIAMOND, DPAD)
}
data class RemoteControl(
    val id: String = UUID.randomUUID().toString(), val label: String = "Button", val symbol: String = "",
    val showLabel: Boolean = true, val shape: ControlShape = ControlShape.CIRCLE,
    val x: Float = .5f, val y: Float = .5f, val width: Float = 64f, val height: Float = 64f,
    val pinned: Boolean = false, val bindings: Map<ControlZone, String> = emptyMap(),
    val routines: Map<ControlZone, String> = emptyMap(),
    val textSize: ControlTextSize = ControlTextSize.MEDIUM, val color: ControlColor = ControlColor.DEFAULT,
    val automationZones: Set<ControlZone> = emptySet(),
)

fun RemoteControl.mappingName(zone: ControlZone): String = label.ifBlank { shape.label } + if(zone == ControlZone.MAIN) "" else " · ${zone.label}"

/** Continue forwards from the current zone, wrapping once and skipping already mapped actions. */
fun Remote.nextUnmapped(current: Pair<String,ControlZone>): Pair<String,ControlZone>? {
    val targets = controls.flatMap { c -> c.shape.zones.map { c.id to it } }
    val index = targets.indexOf(current)
    return (targets.drop(index+1) + targets.take((index+1).coerceAtLeast(0))).firstOrNull { target ->
        val c = controls.first { it.id == target.first }
        target != current && c.bindings[target.second] == null && c.routines[target.second] == null && target.second !in c.automationZones
    }
}
data class Remote(
    val id: String = UUID.randomUUID().toString(), val room: String, val name: String,
    val category: RemoteCategory = RemoteCategory.CUSTOM, val kind: RemoteKind = RemoteKind.SUB_GHZ,
    val frequency: String = "433.92", val preset: RadioPreset = RadioPreset.AM650,
    val canvasHeight: Float = 480f, val snap: Boolean = true, val controls: List<RemoteControl> = emptyList(),
    val tvId: String = "",
)
enum class ShortcutKind { BUTTON, REMOTE, ROUTINE }
data class Shortcut(val kind: ShortcutKind, val id: String)

val simpleShapes = listOf(ControlShape.CIRCLE, ControlShape.ROUNDED, ControlShape.PILL, ControlShape.UP, ControlShape.DOWN,
    ControlShape.ROCKER_HORIZONTAL, ControlShape.ROCKER_VERTICAL, ControlShape.DPAD)

fun RemoteControl.sizeIndex(): Int = (0..2).minBy { index -> val size = simpleSized(index); kotlin.math.abs(width-size.width) + kotlin.math.abs(height-size.height) }
fun RemoteControl.simpleSized(index: Int = sizeIndex(), newShape: ControlShape = shape): RemoteControl {
    val base = listOf(52f,72f,104f)[index]
    val length = listOf(112f,160f,208f)[index]
    val width = when(newShape) { ControlShape.ROCKER_HORIZONTAL -> length; ControlShape.DPAD -> listOf(156f,180f,216f)[index]; ControlShape.PILL,ControlShape.ROUNDED -> base * 1.5f; else -> base }
    val height = when(newShape) { ControlShape.ROCKER_VERTICAL -> length; ControlShape.DPAD -> width; else -> base }
    return resized(newShape,width,height).copy(pinned = false)
}

fun Home.migrateRemotes(): Home {
    var next = this
    buttons.filter { b -> remotes.none { it.id == b.remoteId } }.groupBy { it.room to it.device }.forEach { (key, group) ->
        val existing = next.remotes.firstOrNull { it.room == key.first && it.name == key.second }
        val base = existing ?: Remote(id = UUID.nameUUIDFromBytes("${key.first}\u0000${key.second}".toByteArray(Charsets.UTF_8)).toString(),
            room = key.first, name = key.second, kind = RemoteKind.forFile(group.first().path) ?: RemoteKind.SUB_GHZ,
            canvasHeight = maxOf(480f, ((group.size + 1) / 2) * 100f + 48f))
        val height = maxOf(base.canvasHeight, ((base.controls.size + group.size + 1) / 2) * 100f + 48f)
        val remote = base.copy(canvasHeight = height, controls = base.controls.map { it.copy(y = it.y * base.canvasHeight / height) })
        val controls = group.mapIndexed { i, b -> val index = remote.controls.size + i; RemoteControl(id = "legacy-${b.id}", label = b.name, shape = ControlShape.ROUNDED,
            x = if(index % 2 == 0) .28f else .72f, y = (60f + (index / 2) * 100f) / remote.canvasHeight,
            width = 112f, height = 68f, bindings = mapOf(ControlZone.MAIN to b.id)) }
        next = next.copy(remotes = next.remotes.filterNot { it.id == remote.id } + remote.copy(controls = remote.controls + controls),
            buttons = next.buttons.map { if(it.id in group.map { b -> b.id }) it.copy(remoteId = remote.id) else it })
    }
    return next
}

fun Home.withRemote(remote: Remote): Home = copy(
    remotes = if(remotes.any { it.id == remote.id }) remotes.map { if(it.id == remote.id) remote else it } else remotes + remote,
    buttons = buttons.map { if(it.remoteId == remote.id) it.copy(room = remote.room, device = remote.name) else it },
)

// Relearning keeps the command ID, so routines and Home shortcuts keep working.
fun Home.bind(remoteId: String, controlId: String, zone: ControlZone, captured: RemoteButton): Home {
    val remote = remotes.first { it.id == remoteId }
    val control = remote.controls.first { it.id == controlId }
    val previous = control.bindings[zone]?.takeUnless { id -> buttons.firstOrNull { it.id == id }?.isTv == true }
    val old = buttons.firstOrNull { it.id == previous }
    val command = captured.copy(id = previous ?: captured.id, remoteId = remoteId, room = remote.room, device = remote.name,
        voiceName = old?.voiceName.orEmpty(), voiceHoldMs = old?.voiceHoldMs,
        name = if(zone == ControlZone.MAIN) control.label.ifBlank { control.shape.label } else "${control.label.ifBlank { control.shape.label }} ${zone.label}")
    return copy(buttons = buttons.filterNot { it.id == command.id } + command).withRemote(remote.copy(controls = remote.controls.map {
        if(it.id == controlId) it.copy(bindings = it.bindings + (zone to command.id), routines = it.routines - zone,automationZones = it.automationZones - zone) else it
    }))
}

fun RemoteControl.resized(newShape: ControlShape = shape, w: Float = width, h: Float = height): RemoteControl {
    val minW = when(newShape) { ControlShape.DPAD -> 156f; ControlShape.ROCKER_HORIZONTAL -> 112f; else -> 48f }
    val minH = when(newShape) { ControlShape.DPAD -> 156f; ControlShape.ROCKER_VERTICAL -> 112f; else -> 48f }
    val width = w.coerceIn(minW, 240f)
    val height = if(newShape.equalSides) width.coerceAtLeast(minH) else h.coerceIn(minH, 240f)
    return copy(shape = newShape, width = if(newShape.equalSides) height else width, height = height)
}

fun RemoteControl.positioned(nx: Float, ny: Float, canvasWidth: Float, canvasHeight: Float, snap: Boolean): RemoteControl {
    val marginX = (width / 2 + 6) / canvasWidth.coerceAtLeast(width + 12)
    val marginY = (height / 2 + if(showLabel) 26 else 6) / canvasHeight.coerceAtLeast(height + 52)
    fun point(value: Float, extent: Float) = if(snap) round(value * extent / 8) * 8 / extent else value
    return copy(x = point(nx, canvasWidth).coerceIn(marginX, 1-marginX), y = point(ny, canvasHeight).coerceIn(marginY, 1-marginY))
}

fun remoteTemplate(room: String, name: String, category: RemoteCategory): Remote {
    fun key(label: String, symbol: String, x: Float, y: Float, shape: ControlShape = ControlShape.CIRCLE, w: Float = 64f, h: Float = w) =
        RemoteControl(label = label, symbol = symbol, x = x, y = y, shape = shape, width = w, height = h)
    val controls = when(category) {
        RemoteCategory.LIGHTS -> listOf(key("On", "I", .28f, .15f), key("Off", "O", .72f, .15f),
            key("Brightness", "", .5f, .4f, ControlShape.ROCKER_HORIZONTAL, 160f, 64f), key("Mode", "", .5f, .68f, ControlShape.PILL, 112f, 56f))
        RemoteCategory.TV -> listOf(key("Power", "⏻", .25f, .1f), key("Mute", "M", .75f, .1f),
            key("Volume", "", .22f, .36f, ControlShape.ROCKER_VERTICAL, 56f, 112f), key("Channel", "", .78f, .36f, ControlShape.ROCKER_VERTICAL, 56f, 112f),
            key("Navigation", "", .5f, .68f, ControlShape.DPAD, 156f), key("Input", "", .5f, .92f, ControlShape.PILL, 100f, 48f))
        RemoteCategory.CHROMECAST -> listOf(key("Navigation","",.5f,.25f,ControlShape.DPAD,180f),
            key("Back","",.25f,.54f,w = 88f),key("Home","",.75f,.54f,w = 88f),key("Play / pause","▶ / Ⅱ",.5f,.70f,ControlShape.PILL,144f,56f),
            key("Volume","",.5f,.90f,ControlShape.ROCKER_HORIZONTAL,160f,64f),key("Power","⏻",.25f,1.06f,w = 72f),key("Mute","",.75f,1.06f,w = 72f))
        RemoteCategory.FAN -> listOf(key("Power", "⏻", .5f, .15f), key("Speed", "", .5f, .43f, ControlShape.ROCKER_HORIZONTAL, 160f, 64f),
            key("Oscillate", "↔", .28f, .72f), key("Timer", "◷", .72f, .72f))
        RemoteCategory.CUSTOM -> emptyList()
    }
    val height = if(category == RemoteCategory.CHROMECAST) 560f else 480f
    return Remote(room = room, name = name, category = category, kind = if(category in listOf(RemoteCategory.TV,RemoteCategory.CHROMECAST)) RemoteKind.INFRARED else RemoteKind.SUB_GHZ,
        canvasHeight = height,controls = if(category == RemoteCategory.CHROMECAST) controls.map { it.copy(y = it.y*480/height) } else controls)
}
