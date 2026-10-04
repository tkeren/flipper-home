package com.flipperhome

import org.junit.Assert.*
import org.junit.Test

class RemotesTest {
    private val room = Room("room", "Living room")
    private fun button(id: String, device: String = "Lights") = RemoteButton(id,room.id,device,"Off","/ext/subghz/$id.sub","",700)

    @Test fun migrationPreservesCommandsAndRoutinesAndIsIdempotent() {
        val original = Home(listOf(room),listOf(button("one"),button("two"),button("tv","TV")),listOf(Scene("scene","Bedtime",listOf("one","two"))))
        val migrated = original.migrateRemotes()
        assertEquals(2,migrated.remotes.size)
        assertEquals(original.scenes,migrated.scenes)
        original.buttons.forEach { b ->
            val saved = migrated.buttons.first { it.id == b.id }
            assertEquals(b,saved.copy(remoteId = ""))
            assertTrue(migrated.remotes.first { it.id == saved.remoteId }.controls.any { it.bindings[ControlZone.MAIN] == b.id })
        }
        assertEquals(migrated,migrated.migrateRemotes())
        assertEquals(migrated,original.migrateRemotes())
    }
    @Test fun identicalDeviceNamesInDifferentRoomsRemainSeparate() {
        val home = Home(listOf(room,Room("other","Bedroom")),listOf(button("one"),button("two").copy(room = "other"))).migrateRemotes()
        assertEquals(2,home.remotes.size)
        assertNotEquals(home.buttons[0].remoteId,home.buttons[1].remoteId)
    }
    @Test fun importingAnotherSignalKeepsExistingLayout() {
        val original = Home(listOf(room),listOf(button("one"))).migrateRemotes()
        val next = original.copy(buttons = original.buttons + button("two")).migrateRemotes()
        assertEquals(1,next.remotes.size)
        assertEquals(original.remotes.single().controls.first(),next.remotes.single().controls.first())
        assertEquals(2,next.remotes.single().controls.size)
        assertNotEquals(next.remotes.single().controls.first().x,next.remotes.single().controls.last().x)
        assertEquals(next.remotes.single().id,next.buttons.last().remoteId)
        assertEquals(next,next.migrateRemotes())
    }
    @Test fun relearningPreservesReferencesAndChangesOnlyThatCommand() {
        val original = Home(listOf(room),listOf(button("one"),button("two")),listOf(Scene("scene","Off",listOf("one"))),shortcuts = listOf(Shortcut(ShortcutKind.BUTTON,"one"))).migrateRemotes()
        val remote = original.remotes.single()
        val control = remote.controls.first()
        val updated = original.bind(remote.id,control.id,ControlZone.MAIN,button("new"))
        assertEquals(2,updated.buttons.size)
        assertEquals("/ext/subghz/new.sub",updated.buttons.first { it.id == "one" }.path)
        assertEquals(original.buttons.first { it.id == "two" },updated.buttons.first { it.id == "two" })
        assertEquals(original.scenes,updated.scenes)
        assertEquals(original.shortcuts,updated.shortcuts)
        assertEquals("one",updated.remotes.single().controls.first().bindings[ControlZone.MAIN])
    }
    @Test fun rockerZonesAndDirectionsLearnIndependently() {
        val r = remoteTemplate(room.id,"Lights",RemoteCategory.LIGHTS)
        val c = r.controls.first { it.shape == ControlShape.ROCKER_HORIZONTAL }
        val home = Home(listOf(room),remotes = listOf(r)).bind(r.id,c.id,ControlZone.PLUS,button("plus")).bind(r.id,c.id,ControlZone.MINUS,button("minus"))
        val mapped = home.remotes.single().controls.first { it.id == c.id }
        assertEquals("plus",mapped.bindings[ControlZone.PLUS]); assertEquals("minus",mapped.bindings[ControlZone.MINUS])
        assertEquals(5,ControlShape.DPAD.zones.size)
    }
    @Test fun changingShapeRetainsLearnedCommands() {
        val c = RemoteControl(bindings = mapOf(ControlZone.MAIN to "one",ControlZone.PLUS to "two"))
        assertEquals(c.bindings,c.simpleSized(newShape = ControlShape.ROCKER_HORIZONTAL).simpleSized(newShape = ControlShape.CIRCLE).bindings)
    }
    @Test fun positionStaysInsideCanvasAndSnapsToGrid() {
        val c = RemoteControl(width = 72f,height = 72f)
        val edge = c.positioned(-5f,8f,320f,480f,true)
        assertTrue(edge.x*320 >= 42); assertTrue(edge.y*480 <= 480-62)
        val grid = c.positioned(.5f,.5f,320f,480f,true)
        assertEquals(0f,(grid.x*320)%8,.01f); assertEquals(0f,(grid.y*480)%8,.01f)
    }
    @Test fun smallCompositeControlsHaveUsableTouchTargets() {
        for(shape in simpleShapes) {
            val c = RemoteControl().simpleSized(0,shape)
            assertTrue(c.width >= 48f); assertTrue(c.height >= 48f)
            if(shape == ControlShape.ROCKER_HORIZONTAL) assertTrue((c.width-4)/2 >= 48f)
            if(shape == ControlShape.ROCKER_VERTICAL) assertTrue((c.height-4)/2 >= 48f)
            if(shape == ControlShape.DPAD) assertTrue((c.width-6)/3 >= 48f)
        }
    }
    @Test fun chosenSizeStaysSelectedForEveryShape() {
        for(shape in simpleShapes) for(index in 0..2) assertEquals(index,RemoteControl().simpleSized(index,shape).sizeIndex())
    }
    @Test fun movingRemoteKeepsFilesAndSceneReferences() {
        val original = Home(listOf(room),listOf(button("one"))).migrateRemotes()
        val updated = original.withRemote(original.remotes.single().copy(name = "Ceiling",room = "bedroom"))
        assertEquals("bedroom",updated.buttons.single().room); assertEquals("Ceiling",updated.buttons.single().device)
        assertEquals(original.buttons.single().path,updated.buttons.single().path)
        assertEquals(original.buttons.single().id,updated.buttons.single().id)
    }
    @Test fun mapNextMovesForwardThroughZonesBeforeWrappingAndSkipsAutomations() {
        val controls = listOf(RemoteControl(id = "first"),RemoteControl(id = "rocker",label = "Brightness",shape = ControlShape.ROCKER_HORIZONTAL),
            RemoteControl(id = "automation",automationZones = setOf(ControlZone.MAIN)),RemoteControl(id = "last"))
        val r = Remote(room = room.id,name = "Lights",controls = controls)
        assertEquals("rocker" to ControlZone.MINUS,r.nextUnmapped("rocker" to ControlZone.PLUS))
        assertEquals("last" to ControlZone.MAIN,r.nextUnmapped("rocker" to ControlZone.MINUS))
        assertEquals("first" to ControlZone.MAIN,r.nextUnmapped("last" to ControlZone.MAIN))
        assertEquals("Brightness · +",controls[1].mappingName(ControlZone.PLUS))
    }
    @Test fun mapNextStopsWhenEveryOtherSignalZoneIsMapped() {
        val r = Remote(room = room.id,name = "Lights",controls = listOf(RemoteControl(id = "current"),
            RemoteControl(id = "mapped",bindings = mapOf(ControlZone.MAIN to "one")),RemoteControl(id = "automation",routines = mapOf(ControlZone.MAIN to "scene"))))
        assertNull(r.nextUnmapped("current" to ControlZone.MAIN))
    }
    @Test fun stylingSurvivesResizingMovingAndRelearningWithoutChangingOtherButtons() {
        val controls = listOf(RemoteControl(id = "styled",label = "Ceiling",color = ControlColor.LAVENDER,textSize = ControlTextSize.LARGE),RemoteControl(id = "other"))
        val r = Remote(id = "remote",room = room.id,name = "Lights",controls = controls)
        val changed = controls.first().simpleSized(2,ControlShape.UP).positioned(.2f,.3f,320f,480f,true)
        val saved = Home(listOf(room),remotes = listOf(r.copy(controls = listOf(changed,controls.last())))).bind(r.id,"styled",ControlZone.MAIN,button("new"))
        val styled = saved.remotes.single().controls.first()
        assertEquals(ControlColor.LAVENDER,styled.color); assertEquals(ControlTextSize.LARGE,styled.textSize)
        assertEquals(changed.x,styled.x); assertEquals(changed.y,styled.y); assertEquals(controls.last(),saved.remotes.single().controls.last())
    }
}
