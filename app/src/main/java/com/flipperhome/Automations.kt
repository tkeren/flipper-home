package com.flipperhome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

fun Home.withAutomation(remote: Remote, controlId: String, zone: ControlZone, scene: Scene): Home {
    require(scene.name.isNotBlank() && scene.buttons.isNotEmpty()) { "Name the automation and add an action" }
    scene.validateTiming()
    require(scene.buttons.all { id -> buttons.any { it.id == id } }) { "One of the selected signals is missing" }
    require(remote.controls.any { it.id == controlId && zone in it.shape.zones }) { "Choose a button on this remote" }
    // A new wrapper with identical ordered actions/timing can bind to the existing routine.
    // Explicit edits retain their ID so other buttons pointing to that routine still update.
    val canonical = if(scenes.any { it.id == scene.id }) scene
        else scenes.firstOrNull { it.steps == scene.steps && it.delayMs == scene.delayMs } ?: scene
    val mapped = remote.copy(controls = remote.controls.map { c ->
        if(c.id == controlId) c.copy(routines = c.routines + (zone to canonical.id), bindings = c.bindings - zone,automationZones = c.automationZones + zone,
            label = if(zone == ControlZone.MAIN) scene.name else c.label) else c
    })
    return withRemote(mapped).copy(scenes = if(scenes.any { it.id == canonical.id }) scenes.map { if(it.id == canonical.id) canonical else it } else scenes + canonical)
}

fun Home.replaceRoutine(scene: Scene): Home {
    require(scene.name.isNotBlank() && scene.buttons.isNotEmpty()) { "Name the automation and add an action" }
    scene.validateTiming()
    require(scene.buttons.all { id -> buttons.any { it.id == id } }) { "Check the automation's signals" }
    val previous = scenes.firstOrNull { it.id == scene.id }
    if(previous == null && scenes.any { it.steps == scene.steps && it.delayMs == scene.delayMs }) return this
    return copy(scenes = if(previous == null) scenes + scene else scenes.map { if(it.id == scene.id) scene else it },
        remotes = remotes.map { r -> r.copy(controls = r.controls.map { c ->
            if(c.routines[ControlZone.MAIN] == scene.id && c.label == previous?.name) c.copy(label = scene.name) else c
        }) })
}

fun Home.replaceSignal(signal: RemoteButton): Home {
    val old = buttons.first { it.id == signal.id }
    require(signal.name.isNotBlank()) { "Name the signal" }
    val kind = requireNotNull(RemoteKind.forFile(signal.path)) { "Choose a saved .ir or .sub file" }
    require(kind != RemoteKind.INFRARED || signal.signal.isNotBlank()) { "Choose an infrared signal" }
    require(signal.holdMs in 100..10000) { "Press length must be between 100 and 10000 ms" }
    return copy(buttons = buttons.map { if(it.id == signal.id) signal.copy(voiceName = old.voiceName, voiceHoldMs = old.voiceHoldMs) else it }, remotes = remotes.map { r -> r.copy(controls = r.controls.map { c ->
        if(c.bindings[ControlZone.MAIN] == old.id && c.label == old.name) c.copy(label = signal.name) else c
    }) })
}

fun Home.deleteSignal(id: String): Home = copy(
    buttons = buttons.filterNot { it.id == id },
    remotes = remotes.map { r -> r.copy(controls = r.controls.map { c -> c.copy(bindings = c.bindings.filterValues { it != id }) }) },
    scenes = scenes.map { it.withSteps(it.steps.filterNot { step -> step.signalId == id }) },
    shortcuts = shortcuts.filterNot { it == Shortcut(ShortcutKind.BUTTON,id) },
)

fun Home.deleteRoutine(id: String): Home = copy(
    scenes = scenes.filterNot { it.id == id },
    remotes = remotes.map { r -> r.copy(controls = r.controls.map { c -> c.copy(routines = c.routines.filterValues { it != id },
        automationZones = c.automationZones + c.routines.filterValues { it == id }.keys) }) },
    shortcuts = shortcuts.filterNot { it == Shortcut(ShortcutKind.ROUTINE,id) },
)

/** All sequence elements reference saved signals, including an expanded automation selection. */
data class ActionChoice(val title: String, val signals: List<String>, val automation: Boolean = false, val holdsMs: List<Long?> = emptyList()) {
    val steps: List<AutomationStep> get() = signals.mapIndexed { index, id -> AutomationStep(id,holdsMs.getOrNull(index)) }
}
fun Home.actionChoices(remoteId: String): List<ActionChoice> {
    val r = remotes.firstOrNull { it.id == remoteId } ?: return emptyList()
    val choices = r.controls.flatMap { c -> c.shape.zones.mapNotNull { zone ->
        val title = c.label.ifBlank { c.shape.label } + if(zone == ControlZone.MAIN) "" else " ${zone.label}"
        val scene = scenes.firstOrNull { it.id == c.routines[zone] }
        val signal = buttons.firstOrNull { it.id == c.bindings[zone] }
        when { scene != null && scene.buttons.isNotEmpty() -> ActionChoice(title,scene.buttons,true,scene.holdsMs)
            signal != null -> ActionChoice(title,listOf(signal.id))
            else -> null }
    } }
    val displayed = choices.filterNot { it.automation }.flatMap { it.signals }.toSet()
    return choices + buttons.filter { it.remoteId == remoteId && it.id !in displayed }.map { ActionChoice(it.name,listOf(it.id)) }
}

internal object RoutineRunner {
    suspend fun run(scene: Scene, signals: List<RemoteButton>, transmit: suspend (RemoteButton,Long?) -> Unit) {
        require(scene.buttons.isNotEmpty()) { "Add signals to ${scene.name} first" }
        scene.validateTiming()
        val commands = scene.buttons.map { id -> signals.firstOrNull { it.id == id } ?: error("A signal in ${scene.name} is missing. Edit the automation first.") }
        var completed = 0
        try { commands.forEachIndexed { index, command ->
            transmit(command,scene.holdsMs.getOrNull(index)); completed++
            if(index < commands.lastIndex) delay(scene.delayMs)
        } } catch(e: CancellationException) { throw e } catch(e: Exception) { error("Stopped after $completed/${commands.size} actions: ${e.message}") }
    }
}
