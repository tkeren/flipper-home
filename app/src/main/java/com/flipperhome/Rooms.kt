package com.flipperhome

fun Home.addRoom(name: String): Home {
    val room = Room(name = name.trim())
    validateRoomName(room.id,room.name)
    return copy(rooms = rooms + room)
}
fun Home.renameRoom(id: String, name: String): Home {
    require(rooms.any { it.id == id }) { "Room not found" }
    val trimmed = name.trim()
    validateRoomName(id,trimmed)
    return copy(rooms = rooms.map { if(it.id == id) it.copy(name = trimmed) else it })
}
private fun Home.validateRoomName(id: String, name: String) {
    require(name.isNotBlank()) { "Enter a room name" }
    require(rooms.none { it.id != id && it.name.equals(name,true) }) { "A room with this name already exists" }
}

/** Moving a room's contents preserves signal IDs, remote layouts and automation timing. */
fun Home.deleteRoom(id: String, moveTo: String? = null): Home {
    require(rooms.any { it.id == id }) { "Room not found" }
    if(moveTo != null) {
        require(moveTo != id && rooms.any { it.id == moveTo }) { "Choose another room" }
        return copy(rooms = rooms.filterNot { it.id == id },remotes = remotes.map { if(it.room == id) it.copy(room = moveTo) else it },
            buttons = buttons.map { if(it.room == id) it.copy(room = moveTo) else it })
    }
    val remoteIds = remotes.filter { it.room == id }.map { it.id }.toSet()
    val signalIds = buttons.filter { it.room == id || it.remoteId in remoteIds }.map { it.id }
    val cleaned = signalIds.fold(this) { current, signal -> current.deleteSignal(signal) }
    return cleaned.copy(rooms = cleaned.rooms.filterNot { it.id == id },remotes = cleaned.remotes.filterNot { it.id in remoteIds },
        shortcuts = cleaned.shortcuts.filterNot { it.kind == ShortcutKind.REMOTE && it.id in remoteIds })
}
