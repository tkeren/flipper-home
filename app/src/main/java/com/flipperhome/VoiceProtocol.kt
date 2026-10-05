package com.flipperhome

import org.json.JSONArray
import org.json.JSONObject

/** Home Assistant authentication/registration is complete before accepting commands. */
internal class VoiceProtocol(private val bridgeId: String, private val phoneName: String) {
    private var nextId = 1
    private var subscriptionId = 0
    private var registering = false
    var ready = false; private set
    var error = ""; private set
    var setupFailure = false; private set

    fun receive(text: String, token: String, actions: List<VoiceAction>): Pair<String?, VoiceRequest?> {
        require(text.length <= 256_000) { "Home Assistant message is too large" }
        val message = JSONObject(text)
        return when(message.optString("type")) {
            "auth_required" -> JSONObject().put("type", "auth").put("access_token", token).toString() to null
            "auth_invalid" -> { error = "Home Assistant rejected the token"; setupFailure = true; null to null }
            "auth_ok" -> {
                check(!registering) { "Unexpected authentication response" }
                registering = true
                subscriptionId = nextId++
                JSONObject().put("id", subscriptionId).put("type", "flipper_home/subscribe")
                    .put("bridge_id", bridgeId).put("name", phoneName).put("actions", actionJson(actions)).put("available", true).toString() to null
            }
            "result" -> {
                if(!message.optBoolean("success")) {
                    val code = message.optJSONObject("error")?.optString("code").orEmpty()
                    setupFailure = !ready || code == "unknown_command"
                    error = if(code == "unknown_command") "Install and enable the Flipper Home integration in Home Assistant"
                        else message.optJSONObject("error")?.optString("message")?.take(200) ?: "Home Assistant request failed"
                } else if(registering && message.optInt("id") == subscriptionId) ready = true
                null to null
            }
            "event" -> {
                if(!ready || message.optInt("id") != subscriptionId) null to null
                else message.getJSONObject("event").let { data -> null to VoiceRequest(data.getString("request_id"), data.getString("action_id"), data.getLong("issued_at_ms")) }
            }
            else -> null to null
        }
    }

    fun update(actions: List<VoiceAction>, available: Boolean): String {
        check(ready)
        return command("update").put("actions", actionJson(actions)).put("available", available).toString()
    }
    fun heartbeat(available: Boolean): String = command("heartbeat").put("available", available).toString()
    fun result(request: VoiceRequest, result: VoiceResult): String = command("result").put("request_id", request.id)
        .put("success", result.success).put("message", result.message.take(300)).toString()
    private fun command(name: String) = JSONObject().put("id", nextId++).put("type", "flipper_home/$name").put("bridge_id", bridgeId)
    private fun actionJson(actions: List<VoiceAction>) = JSONArray(actions.map { JSONObject().put("id", it.id).put("name", it.name).put("room", it.room.take(80)) })
}
