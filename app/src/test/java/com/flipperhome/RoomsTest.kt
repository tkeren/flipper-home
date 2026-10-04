package com.flipperhome

import org.junit.Assert.*
import org.junit.Test

class RoomsTest {
    private fun home(): Home {
        val rooms = listOf(Room("a","Bedroom"),Room("b","Kitchen"))
        val first = Remote("remote-a","a","Lights",controls = listOf(RemoteControl("control-a",bindings = mapOf(ControlZone.MAIN to "dim-a"))))
        val second = Remote("remote-b","b","Other light",controls = listOf(RemoteControl("control-b",bindings = mapOf(ControlZone.MAIN to "dim-a")),RemoteControl("control-c",bindings = mapOf(ControlZone.MAIN to "dim-b"))))
        val signals = listOf(RemoteButton("dim-a","a","Lights","Dim","/ext/subghz/a.sub","",remoteId = first.id),RemoteButton("dim-b","b","Other light","Dim","/ext/subghz/b.sub","",remoteId = second.id))
        return Home(rooms,signals,listOf(Scene("scene","Dim both",listOf("dim-a","dim-b","dim-a"),holdsMs = listOf(5000L,2000L,1000L))),listOf(first,second),
            listOf(Shortcut(ShortcutKind.REMOTE,first.id),Shortcut(ShortcutKind.BUTTON,"dim-a"),Shortcut(ShortcutKind.ROUTINE,"scene")))
    }
    @Test fun renamePreservesIdsRemoteLayoutsSignalsAndAutomationReferences() {
        val old = home(); val updated = old.renameRoom("a","  Office  ")
        assertEquals(Room("a","Office"),updated.rooms.first())
        assertEquals(old.remotes,updated.remotes); assertEquals(old.buttons,updated.buttons); assertEquals(old.scenes,updated.scenes); assertEquals(old.shortcuts,updated.shortcuts)
    }
    @Test fun blankAndDuplicateRoomNamesAreRejected() {
        for(name in listOf(" "," kitchen ","KITCHEN")) try { home().renameRoom("a",name); fail("Expected invalid name") } catch(_: IllegalArgumentException) { }
        try { home().addRoom(" bedroom "); fail("Expected duplicate") } catch(_: IllegalArgumentException) { }
        assertEquals("Bedroom",home().renameRoom("a","Bedroom").rooms.first().name)
    }
    @Test fun movingContentsBeforeDeletionPreservesAllLinksAndHolds() {
        val old = home(); val updated = old.deleteRoom("a","b")
        assertEquals(listOf(Room("b","Kitchen")),updated.rooms)
        assertEquals("b",updated.remotes.first().room); assertEquals(old.remotes.first().controls,updated.remotes.first().controls)
        assertEquals(old.buttons.first().copy(room = "b"),updated.buttons.first())
        assertEquals(old.scenes,updated.scenes); assertEquals(old.shortcuts,updated.shortcuts)
    }
    @Test fun deletingContentsClearsCrossRoomLinksAndPreservesRemainingStepDuration() {
        val updated = home().deleteRoom("a")
        assertEquals(listOf("remote-b"),updated.remotes.map { it.id }); assertEquals(listOf("dim-b"),updated.buttons.map { it.id })
        assertTrue(updated.remotes.single().controls.first().bindings.isEmpty())
        assertEquals(listOf(AutomationStep("dim-b",2000L)),updated.scenes.single().steps)
        assertEquals(listOf(Shortcut(ShortcutKind.ROUTINE,"scene")),updated.shortcuts)
    }
    @Test fun deletingLastRoomProducesAnEmptyHomeWithoutAddingDefaults() {
        val updated = home().deleteRoom("a").deleteRoom("b")
        assertTrue(updated.rooms.isEmpty()); assertTrue(updated.remotes.isEmpty()); assertTrue(updated.buttons.isEmpty())
        assertTrue(updated.scenes.single().steps.isEmpty()); assertEquals(updated,updated.migrateRemotes())
    }
    @Test fun deletingAnEmptyRoomPreservesEveryExistingControlAndAutomation() {
        val old = home().addRoom("Unused")
        assertEquals(home(),old.deleteRoom(old.rooms.last().id))
    }
    @Test fun invalidMoveDestinationIsRejectedWithoutRemovingData() {
        for(destination in listOf("a","missing")) try { home().deleteRoom("a",destination); fail("Expected invalid move") } catch(_: IllegalArgumentException) { }
    }
}
