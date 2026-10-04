package com.flipperhome

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutomationsTest {
    @Test fun newAutomationsDefaultTo200MillisecondsBetweenActions() {
        assertEquals(200L,Scene(name = "Lights",buttons = listOf("on","off")).delayMs)
    }
    private val room = Room("living","Living room")
    private fun signal(id: String) = RemoteButton(id,room.id,"Lights",id,"/ext/subghz/$id.sub","",500,"remote")
    private fun home(): Home {
        val signals = listOf(signal("on"),signal("off"))
        val remote = Remote("remote",room.id,"Lights",controls = signals.map { RemoteControl(id = it.id,label = it.name,bindings = mapOf(ControlZone.MAIN to it.id)) })
        return Home(listOf(room),signals,remotes = listOf(remote))
    }
    @Test fun automationUsesSameLayoutAndSignalIdsWithoutDuplicatingCaptures() {
        val home = home(); val sequence = Scene("routine","Bedtime",listOf("off","on","off"))
        val result = home.withAutomation(home.remotes.single(),"on",ControlZone.MAIN,sequence)
        assertEquals(home.buttons,result.buttons)
        assertEquals(sequence,result.scenes.single())
        val control = result.remotes.single().controls.first()
        assertTrue(control.bindings.isEmpty()); assertEquals("routine",control.routines[ControlZone.MAIN]); assertEquals("Bedtime",control.label)
        assertEquals(home.remotes.single().controls.first().x,control.x)
    }
    @Test fun replacingNoisySignalPreservesAllReferences() {
        val original = home().copy(scenes = listOf(Scene("routine","All off",listOf("off","off"))),shortcuts = listOf(Shortcut(ShortcutKind.BUTTON,"off")))
        val replacement = original.buttons.last().copy(name = "Ceiling off",path = "/ext/subghz/new.sub")
        val updated = original.replaceSignal(replacement)
        assertEquals(original.scenes,updated.scenes); assertEquals(original.shortcuts,updated.shortcuts)
        assertEquals("off",updated.remotes.single().controls.last().bindings[ControlZone.MAIN])
        assertEquals("Ceiling off",updated.remotes.single().controls.last().label)
        assertEquals(replacement,updated.buttons.last())
    }
    @Test fun signalRenamePreservesCustomButtonText() {
        val original = home(); val remote = original.remotes.single()
        val custom = original.withRemote(remote.copy(controls = remote.controls.map { it.copy(label = "Custom ${it.label}") }))
        val updated = custom.replaceSignal(custom.buttons.last().copy(name = "New off"))
        assertEquals("Custom off",updated.remotes.single().controls.last().label)
    }
    @Test fun deletingSignalClearsItsLinksAndEveryRepeatedSequenceStep() {
        val original = home().copy(scenes = listOf(Scene("routine","Sequence",listOf("off","on","off"))),shortcuts = listOf(Shortcut(ShortcutKind.BUTTON,"off"),Shortcut(ShortcutKind.REMOTE,"remote")))
        val updated = original.deleteSignal("off")
        assertEquals(listOf("on"),updated.buttons.map { it.id }); assertEquals(listOf("on"),updated.scenes.single().buttons)
        assertTrue(updated.remotes.single().controls.last().bindings.isEmpty())
        assertEquals(listOf(Shortcut(ShortcutKind.REMOTE,"remote")),updated.shortcuts)
    }
    @Test fun deletingRoutineDetachesAutomationButtonsAndHomePins() {
        val original = home(); val scene = Scene("routine","Night",listOf("off"))
        val mapped = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene).copy(shortcuts = listOf(Shortcut(ShortcutKind.ROUTINE,scene.id)))
        val updated = mapped.deleteRoutine(scene.id)
        assertTrue(updated.scenes.isEmpty()); assertTrue(updated.shortcuts.isEmpty()); assertTrue(updated.remotes.single().controls.first().routines.isEmpty())
        assertEquals(original.buttons,updated.buttons)
    }
    @Test fun pickerUsesRemoteIdsAndExpandsAutomationsIntoSignalSteps() {
        val original = home(); val scene = Scene("routine","Night",listOf("off","on","off"))
        val other = Remote("bedroom","another-room","Lights")
        val mapped = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene).copy(remotes = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene).remotes + other)
        assertTrue(mapped.actionChoices(other.id).isEmpty())
        val automation = mapped.actionChoices("remote").first { it.automation }
        assertEquals(listOf("off","on","off"),automation.signals)
        assertTrue(mapped.actionChoices("remote").any { it.signals == listOf("on") && !it.automation })
    }
    @Test fun replacingRoutinePreservesBindingAndUpdatesMatchingButtonText() {
        val original = home(); val scene = Scene("routine","Night",listOf("off"))
        val updated = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene).replaceRoutine(scene.copy(name = "Sleep",buttons = listOf("on","off")))
        assertEquals("Sleep",updated.remotes.single().controls.first().label)
        assertEquals("routine",updated.remotes.single().controls.first().routines[ControlZone.MAIN])
    }
    @Test fun sequenceSendsInOrderIncludingRepeatedSignals() = runBlocking {
        val sent = mutableListOf<String>()
        RoutineRunner.run(Scene(name = "Night",buttons = listOf("off","on","off"),delayMs = 0),home().buttons) { button, duration -> assertNull(duration); sent.add(button.id) }
        assertEquals(listOf("off","on","off"),sent)
    }
    @Test fun missingSignalStopsBeforeAnyTransmission() = runBlocking {
        val sent = mutableListOf<String>()
        try { RoutineRunner.run(Scene(name = "Night",buttons = listOf("on","missing"),delayMs = 0),home().buttons) { button, _ -> sent.add(button.id) }; fail("Expected missing signal") }
        catch(e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("missing")) }
        assertTrue(sent.isEmpty())
    }
    @Test fun sendFailureStopsSequenceAndReportsPartialCompletion() = runBlocking {
        val sent = mutableListOf<String>()
        try { RoutineRunner.run(Scene(name = "Night",buttons = listOf("on","off","on"),delayMs = 0),home().buttons) { button, _ -> sent.add(button.id); if(button.id == "off") error("Disconnected") }; fail("Expected failure") }
        catch(e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("1/3")) }
        assertEquals(listOf("on","off"),sent)
    }
    @Test fun cancellationIsNotConvertedToASuccessOrPartialFailure() = runBlocking {
        try { RoutineRunner.run(Scene(name = "Night",buttons = listOf("on"),delayMs = 0),home().buttons) { _, _ -> throw CancellationException("Stopped") }; fail("Expected cancellation") }
        catch(e: CancellationException) { assertEquals("Stopped",e.message) }
    }
    @Test fun eachOccurrenceKeepsItsOwnHoldDurationWithoutChangingSavedSignals() = runBlocking {
        val original = home()
        val scene = Scene(name = "Dim both",buttons = listOf("on","off","on"),delayMs = 0,holdsMs = listOf(5000L,5000L,null))
        val sent = mutableListOf<Pair<String,Long?>>()
        RoutineRunner.run(scene,original.buttons) { button, duration -> assertEquals(500L,button.holdMs); sent.add(button.id to duration) }
        assertEquals(listOf("on" to 5000L,"off" to 5000L,"on" to null),sent)
    }
    @Test fun deletingRepeatedSignalKeepsDurationsOnTheRemainingActions() {
        val original = home().copy(scenes = listOf(Scene(name = "Dim",buttons = listOf("off","on","off","on"),holdsMs = listOf(5000L,2000L,null,7000L))))
        val scene = original.deleteSignal("off").scenes.single()
        assertEquals(listOf(AutomationStep("on",2000),AutomationStep("on",7000)),scene.steps)
    }
    @Test fun selectingAnExistingAutomationPreservesItsHolds() {
        val original = home(); val scene = Scene("routine","Dim",listOf("on","off"),holdsMs = listOf(5000L,null))
        val mapped = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene)
        assertEquals(scene.steps,mapped.actionChoices("remote").first { it.automation }.steps)
    }
    @Test fun reorderingAndRemovingStepsPreservesPerOccurrenceTiming() {
        val scene = Scene(name = "Dim",buttons = listOf("on","on","off"),holdsMs = listOf(5000L,null,2000L))
        val reordered = scene.withSteps(listOf(scene.steps[2],scene.steps[0]))
        assertEquals(listOf("off","on"),reordered.buttons)
        assertEquals(listOf(2000L,5000L),reordered.holdsMs)
    }
    @Test fun invalidOrMisalignedDurationsAreRejectedBeforeAnyTransmission() = runBlocking {
        for(holds in listOf(listOf(5000L),listOf(5000L,0L),listOf(5000L,60001L))) {
            var sends = 0
            try { RoutineRunner.run(Scene(name = "Dim",buttons = listOf("on","off"),holdsMs = holds),home().buttons) { _, _ -> sends++ }; fail("Expected invalid timing") }
            catch(_: IllegalArgumentException) { assertEquals(0,sends) }
        }
    }
    @Test fun durationEntrySupportsSecondsAndRejectsInvalidOrOverflowingValues() {
        assertEquals(5000L,parseHoldSeconds("5")); assertEquals(1500L,parseHoldSeconds("1.5"))
        assertEquals(100L,parseHoldSeconds("0.1")); assertEquals(60000L,parseHoldSeconds("60"))
        for(text in listOf("","0","-5","61","NaN","100000000000000000000","0.1001")) assertNull(text,parseHoldSeconds(text))
        assertEquals("5",holdSeconds(5000)); assertEquals("1.5",holdSeconds(1500))
    }

    @Test fun restoringALayoutButtonReusesTheSavedAutomationAndItsTimings() {
        val original = home(); val scene = Scene("routine","Dim both",listOf("on","off"),holdsMs = listOf(5000L,5000L))
        val mapped = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene)
        val removed = mapped.withRemote(mapped.remotes.single().copy(controls = mapped.remotes.single().controls.filterNot { it.id == "on" }))
        val added = removed.remotes.single().copy(controls = removed.remotes.single().controls + RemoteControl(id = "restore"))
        val restored = removed.withAutomation(added,"restore",ControlZone.MAIN,scene)
        assertEquals(listOf(scene),restored.scenes)
        assertEquals(scene.id,restored.remotes.single().controls.last().routines[ControlZone.MAIN])
        assertEquals(original.buttons,restored.buttons)
    }
    @Test fun identicalWrapperUsesTheExistingIdEvenWithADifferentLabel() {
        val original = home(); val scene = Scene("routine","Night",listOf("on","off"),holdsMs = listOf(5000L,null))
        val saved = original.copy(scenes = listOf(scene))
        val restored = saved.withAutomation(saved.remotes.single(),"on",ControlZone.MAIN,scene.copy(id = "wrapper",name = "My night button"))
        assertEquals(listOf(scene),restored.scenes)
        assertEquals("routine",restored.remotes.single().controls.first().routines[ControlZone.MAIN])
        assertEquals("My night button",restored.remotes.single().controls.first().label)
        assertEquals(saved,saved.replaceRoutine(scene.copy(id = "wrapper")))
    }
    @Test fun reuseNormalizesLegacyNullDurationsButKeepsDifferentTimingsSeparate() {
        val original = home(); val scene = Scene("routine","Night",listOf("on","off"))
        val saved = original.copy(scenes = listOf(scene))
        val equivalent = saved.withAutomation(saved.remotes.single(),"on",ControlZone.MAIN,scene.copy(id = "wrapper",holdsMs = listOf(null,null)))
        assertEquals(1,equivalent.scenes.size)
        for(changed in listOf(scene.copy(id = "held",holdsMs = listOf(5000L,null)),scene.copy(id = "slower",delayMs = 1000))) {
            assertEquals(2,saved.withAutomation(saved.remotes.single(),"on",ControlZone.MAIN,changed).scenes.size)
        }
    }
    @Test fun editingASharedAutomationPreservesItsIdAndBothBindings() {
        val original = home(); val scene = Scene("routine","Night",listOf("on","off"))
        val shared = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene)
        val second = shared.withAutomation(shared.remotes.single(),"off",ControlZone.MAIN,scene)
        val edited = second.withAutomation(second.remotes.single(),"on",ControlZone.MAIN,scene.copy(delayMs = 800))
        assertEquals(800L,edited.scenes.single().delayMs)
        assertTrue(edited.remotes.single().controls.all { it.routines[ControlZone.MAIN] == scene.id })
    }
    @Test fun deletingAnAutomationKeepsTheButtonTypeForRemappingNotSignalCapture() {
        val original = home(); val scene = Scene("routine","Night",listOf("off"))
        val deleted = original.withAutomation(original.remotes.single(),"on",ControlZone.MAIN,scene).deleteRoutine(scene.id)
        val control = deleted.remotes.single().controls.first()
        assertTrue(control.routines.isEmpty()); assertEquals(setOf(ControlZone.MAIN),control.automationZones)
        assertTrue(deleted.actionChoices("remote").none { it.automation })
        val signalAgain = deleted.bind("remote","on",ControlZone.MAIN,signal("replacement"))
        assertTrue(signalAgain.remotes.single().controls.first().automationZones.isEmpty())
    }
}
