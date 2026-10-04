package com.flipperhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Room(val id: String = UUID.randomUUID().toString(), val name: String)
data class RemoteButton(val id: String = UUID.randomUUID().toString(), val room: String, val device: String, val name: String, val path: String, val signal: String, val holdMs: Long = 500, val remoteId: String = "",
    val tvId: String = "",val tvKey: Int = 0)
data class AutomationStep(val signalId: String, val holdMs: Long? = null)
const val DEFAULT_ACTION_DELAY_MS = 200L
data class Scene(val id: String = UUID.randomUUID().toString(), val name: String, val buttons: List<String>, val delayMs: Long = DEFAULT_ACTION_DELAY_MS,
    val holdsMs: List<Long?> = emptyList(),
) {
    val steps: List<AutomationStep> get() = buttons.mapIndexed { index, id -> AutomationStep(id,holdsMs.getOrNull(index)) }
    fun withSteps(steps: List<AutomationStep>): Scene = copy(buttons = steps.map { it.signalId },holdsMs = steps.map { it.holdMs })
    fun validateTiming() {
        require(delayMs in 0..60000) { "Delay must be between 0 and 60000 ms" }
        require(holdsMs.isEmpty() || holdsMs.size == buttons.size) { "Check the automation's action durations" }
        require(holdsMs.all { it == null || it in 100..60000 }) { "Hold duration must be between 0.1 and 60 seconds" }
    }
}
data class Home(val rooms: List<Room>, val buttons: List<RemoteButton> = emptyList(), val scenes: List<Scene> = emptyList(), val remotes: List<Remote> = emptyList(), val shortcuts: List<Shortcut> = emptyList(),val tvDevices: List<TvDevice> = emptyList())
class HomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    fun load(): Home {
        val text = prefs.getString("data", null) ?: return Home(emptyList())
        val json = JSONObject(text)
        fun array(key: String) = (json.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        return Home(array("rooms").map { Room(it.getString("id"), it.getString("name")) },
            array("buttons").map { RemoteButton(it.getString("id"), it.getString("room"), it.getString("device"), it.getString("name"), it.getString("path"), it.getString("signal"), it.optLong("holdMs", 500), it.optString("remoteId"),it.optString("tvId"),it.optInt("tvKey")) },
            array("scenes").map { j -> Scene(j.getString("id"), j.getString("name"), j.getJSONArray("buttons").let { a -> (0 until a.length()).map { a.getString(it) } }, j.getLong("delay"),
                j.optJSONArray("holdsMs")?.let { a -> (0 until a.length()).map { if(a.isNull(it)) null else a.getLong(it) } } ?: emptyList()) },
            array("remotes").map { j ->
                val controls = j.getJSONArray("controls").let { a -> (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i); val bindings = c.getJSONObject("bindings")
                    RemoteControl(c.getString("id"), c.getString("label"), c.optString("symbol"), c.optBoolean("showLabel", true), ControlShape.valueOf(c.getString("shape")),
                        c.getDouble("x").toFloat(), c.getDouble("y").toFloat(), c.getDouble("width").toFloat(), c.getDouble("height").toFloat(), c.optBoolean("pinned"),
                        bindings.keys().asSequence().associate { ControlZone.valueOf(it) to bindings.getString(it) },
                        (c.optJSONObject("routines") ?: JSONObject()).let { routines -> routines.keys().asSequence().associate { ControlZone.valueOf(it) to routines.getString(it) } },
                        ControlTextSize.entries.firstOrNull { it.name == c.optString("textSize") } ?: ControlTextSize.MEDIUM,
                        ControlColor.entries.firstOrNull { it.name == c.optString("color") } ?: ControlColor.DEFAULT,
                        c.optJSONArray("automationZones")?.let { zones -> (0 until zones.length()).mapNotNull { index -> ControlZone.entries.firstOrNull { it.name == zones.optString(index) } }.toSet() }
                            ?: (c.optJSONObject("routines") ?: JSONObject()).keys().asSequence().map { ControlZone.valueOf(it) }.toSet())
                } }
                Remote(j.getString("id"), j.getString("room"), j.getString("name"), RemoteCategory.valueOf(j.getString("category")), RemoteKind.valueOf(j.getString("kind")),
                    j.optString("frequency", "433.92"), RadioPreset.valueOf(j.optString("preset", "AM650")), j.optDouble("canvasHeight", 480.0).toFloat(), j.optBoolean("snap", true), controls,j.optString("tvId"))
            }, array("shortcuts").map { Shortcut(ShortcutKind.valueOf(it.getString("kind")), it.getString("id")) },
            array("tvDevices").map { TvDevice(it.getString("id"),it.getString("name"),it.optString("host"),it.optInt("port",6466)) }).migrateRemotes()
    }
    fun save(home: Home) {
        val json = JSONObject()
            .put("rooms", JSONArray(home.rooms.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("buttons", JSONArray(home.buttons.map { JSONObject().put("id", it.id).put("room", it.room).put("device", it.device).put("name", it.name).put("path", it.path).put("signal", it.signal).put("holdMs", it.holdMs).put("remoteId", it.remoteId).put("tvId",it.tvId).put("tvKey",it.tvKey) }))
            .put("scenes", JSONArray(home.scenes.map { JSONObject().put("id", it.id).put("name", it.name).put("buttons", JSONArray(it.buttons)).put("delay", it.delayMs)
                .put("holdsMs", JSONArray(it.steps.map { step -> step.holdMs ?: JSONObject.NULL })) }))
            .put("schema", 6)
            .put("tvDevices",JSONArray(home.tvDevices.map { JSONObject().put("id",it.id).put("name",it.name).put("host",it.host).put("port",it.port) }))
            .put("remotes", JSONArray(home.remotes.map { r -> JSONObject().put("id", r.id).put("room", r.room).put("name", r.name).put("category", r.category.name).put("kind", r.kind.name)
                .put("frequency", r.frequency).put("preset", r.preset.name).put("canvasHeight", r.canvasHeight).put("snap", r.snap).put("tvId",r.tvId).put("controls", JSONArray(r.controls.map { c ->
                    JSONObject().put("id", c.id).put("label", c.label).put("symbol", c.symbol).put("showLabel", c.showLabel).put("shape", c.shape.name)
                        .put("x", c.x).put("y", c.y).put("width", c.width).put("height", c.height).put("pinned", c.pinned)
                        .put("bindings", JSONObject(c.bindings.mapKeys { it.key.name }))
                        .put("routines", JSONObject(c.routines.mapKeys { it.key.name })).put("textSize", c.textSize.name).put("color", c.color.name)
                        .put("automationZones", JSONArray(c.automationZones.map { it.name }))
                })) }))
            .put("shortcuts", JSONArray(home.shortcuts.map { JSONObject().put("kind", it.kind.name).put("id", it.id) }))
        check(prefs.edit().putString("data", json.toString()).commit()) { "Could not save your home" }
    }
}
