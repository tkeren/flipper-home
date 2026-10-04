package com.flipperhome

import java.util.UUID

data class TvDevice(val id: String = UUID.randomUUID().toString(),val name: String,val host: String = "",val port: Int = 6466)
enum class TvKey(val label: String,val code: Int) {
    UP("Up",19), DOWN("Down",20), LEFT("Left",21), RIGHT("Right",22), OK("OK",23),
    BACK("Back",4), HOME("Home",3), PLAY_PAUSE("Play / pause",85), VOLUME_UP("Volume +",24), VOLUME_DOWN("Volume −",25),
    MUTE("Mute",164), POWER("Power",26), SETTINGS("Settings",176), REWIND("Rewind",89), FAST_FORWARD("Fast forward",90)
}
val RemoteButton.isTv: Boolean get() = tvId.isNotBlank()

/** Network and Flipper actions share stable IDs, so layouts and automations can mix both. */
fun Home.withChromecast(remote: Remote): Home {
    val device = TvDevice(id = remote.id,name = remote.name)
    val actions = mutableListOf<RemoteButton>()
    val controls = remote.controls.map { c ->
        val keys = when(c.label) {
            "Navigation" -> mapOf(ControlZone.UP to TvKey.UP,ControlZone.DOWN to TvKey.DOWN,ControlZone.LEFT to TvKey.LEFT,ControlZone.RIGHT to TvKey.RIGHT,ControlZone.OK to TvKey.OK)
            "Volume" -> mapOf(ControlZone.PLUS to TvKey.VOLUME_UP,ControlZone.MINUS to TvKey.VOLUME_DOWN)
            else -> mapOf(ControlZone.MAIN to TvKey.entries.first { it.label == c.label })
        }
        val bindings = keys.mapValues { (_,key) ->
            val action = RemoteButton(room = remote.room,device = remote.name,name = key.label,path = "",signal = "",remoteId = remote.id,tvId = device.id,tvKey = key.code)
            actions += action; action.id
        }
        c.copy(bindings = bindings)
    }
    return withRemote(remote.copy(tvId = device.id,controls = controls)).copy(buttons = buttons + actions,tvDevices = tvDevices + device)
}

fun Home.bindTv(remote: Remote,controlId: String,zone: ControlZone,device: TvDevice,key: TvKey): Home {
    val control = remote.controls.first { it.id == controlId }
    val previous = buttons.firstOrNull { it.id == control.bindings[zone] }
    val action = RemoteButton(id = if(previous?.isTv == true && remotes.sumOf { r -> r.controls.sumOf { c -> c.bindings.values.count { it == previous.id } } } == 1) previous.id else UUID.randomUUID().toString(),
        room = remote.room,device = remote.name,name = key.label,path = "",signal = "",remoteId = remote.id,tvId = device.id,tvKey = key.code)
    val changed = remote.copy(controls = remote.controls.map { c -> if(c.id == controlId) c.copy(bindings = c.bindings + (zone to action.id),routines = c.routines - zone,
        automationZones = c.automationZones - zone) else c })
    return withRemote(changed).copy(buttons = buttons.filterNot { it.id == action.id } + action)
}

fun Home.canUse(action: RemoteButton,flipperConnected: Boolean): Boolean =
    if(action.isTv) tvDevices.any { it.id == action.tvId && it.host.isNotBlank() } else flipperConnected
fun Home.canUse(scene: Scene,flipperConnected: Boolean): Boolean = scene.buttons.isNotEmpty() && scene.buttons.all { id ->
    buttons.firstOrNull { it.id == id }?.let { canUse(it,flipperConnected) } == true
}
