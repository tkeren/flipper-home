package com.flipperhome

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** TLS/WebSocket fixture and fake transmitter; never uses BLE or emits IR/RF. */
internal suspend fun checkVoiceNative(context: Context) {
    val suffix = UUID.randomUUID().toString()
    val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("voice-check-$name-$suffix", mode)
    }
    val store = HomeStore(isolated)
    val button = RemoteButton(id="native-lamp",room="room",device="Lights",name="Dim",path="/ext/subghz/native-test.sub",signal="",voiceName="Dim bedroom",voiceHoldMs=150)
    var home = Home(listOf(Room("room","Bedroom")),listOf(button)).migrateRemotes()
    store.save(home)
    check(store.load() == home) { "Voice settings storage failed" }
    val legacyData = JSONObject(isolated.getSharedPreferences("home",0).getString("data",null)!!)
    legacyData.getJSONArray("buttons").getJSONObject(0).apply { remove("voiceName"); remove("voiceHoldMs") }
    isolated.getSharedPreferences("home",0).edit().putString("data",legacyData.toString()).commit()
    check(store.load().voiceActions.isEmpty()) { "Legacy signals were exposed automatically" }
    store.save(home)

    val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(),clientCertificates.trustManager).build()
    val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(),false) }
    val serverSocket = AtomicReference<WebSocket>()
    val results = Channel<JSONObject>(16)
    val updated = Channel<JSONObject>(16)
    val token = "native-test-token-not-a-real-credential"
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket.set(webSocket); webSocket.send("{\"type\":\"auth_required\"}") }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when(msg.getString("type")) {
                    "auth" -> { check(msg.getString("access_token") == token); webSocket.send("{\"type\":\"auth_ok\"}") }
                    "flipper_home/subscribe" -> {
                        check(msg.getJSONArray("actions").length() == 1)
                        check(!text.contains("subghz") && !text.contains(token))
                        webSocket.send("{\"id\":${msg.getInt("id")},\"type\":\"result\",\"success\":true}")
                    }
                    "flipper_home/update" -> { updated.trySend(msg); webSocket.send("{\"id\":${msg.getInt("id")},\"type\":\"result\",\"success\":true}") }
                    "flipper_home/heartbeat" -> webSocket.send("{\"id\":${msg.getInt("id")},\"type\":\"result\",\"success\":true}")
                    "flipper_home/result" -> { results.trySend(msg); webSocket.send("{\"id\":${msg.getInt("id")},\"type\":\"result\",\"success\":true}") }
                }
            } catch(e: Throwable) { results.close(e); updated.close(e); webSocket.cancel() }
        }
    }
    server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    withContext(Dispatchers.IO) { server.start(InetAddress.getByName("127.0.0.1"),0) }
    val settings = VoiceSettings(isolated)
    settings.save("https://localhost:${server.port}",token,true)
    check(settings.load().token == token)
    check(isolated.getSharedPreferences("voice",0).all.values.none { it.toString().contains(token) }) { "Token was stored in plaintext" }
    val calls = Channel<Pair<String,Long?>>(16)
    val releases = Channel<Unit>(16)
    val connected = MutableStateFlow(true)
    var manualBusy = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var manager: VoiceBridge? = null
    try {
        withContext(Dispatchers.Main) {
            manager = VoiceBridge(isolated,scope,connected,{manualBusy},{ b,d ->
                calls.send(b.id to d)
                try { delay(d ?: 1) } finally { releases.trySend(Unit) }
            },client)
        }
        val bridge = manager!!
        withTimeout(12000) { bridge.status.first { it.connected } }
        fun command(id: String, action: String = "native-lamp") {
            check(serverSocket.get().send(JSONObject().put("id",1).put("type","event").put("event",JSONObject().put("request_id",id).put("action_id",action).put("issued_at_ms",System.currentTimeMillis())).toString()))
        }
        command("first")
        check(calls.receive() == ("native-lamp" to 150L))
        releases.receive()
        check(results.receive().getBoolean("success")) { "Timed command did not complete" }
        command("first")
        check(!results.receive().getBoolean("success")) { "Duplicate command transmitted" }
        command("unknown","unknown")
        check(!results.receive().getBoolean("success")) { "Unknown action transmitted" }
        withContext(Dispatchers.Main) { manualBusy = true }
        command("busy")
        check(!results.receive().getBoolean("success")) { "Busy phone accepted a voice command" }
        withContext(Dispatchers.Main) { manualBusy = false }
        home = home.withVoice("native-lamp","Dim bedroom",60000)
        store.save(home)
        command("cancel")
        check(calls.receive() == ("native-lamp" to 60000L))
        withContext(Dispatchers.Main) { bridge.cancelCommand() }
        releases.receive()
        check(!results.receive().getBoolean("success")) { "Cancelled command was reported successful" }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        serverSocket.get().send("{\"id\":99,\"type\":\"result\",\"success\":false,\"error\":{\"code\":\"bridge_error\",\"message\":\"This phone connection is no longer active\"}}")
        withTimeout(3000) { bridge.status.first { !it.connected } }
        withTimeout(12000) { bridge.status.first { it.connected } }
        command("disable")
        calls.receive()
        store.save(home.withVoice("native-lamp","",null))
        withContext(Dispatchers.Main) { bridge.updateActions() }
        check(updated.receive().getJSONArray("actions").length() == 0)
        releases.receive()
        check(!results.receive().getBoolean("success")) { "Disabling voice didn't cancel an active hold" }
        command("disabled")
        check(!results.receive().getBoolean("success")) { "Disabled action transmitted" }
        connected.value = false
        withTimeout(3000) { bridge.status.first { !it.connected } }
        check(calls.tryReceive().isFailure) { "Extra transmitter call" }
        check(!bridge.busy.value)
        settings.forget()
        check(settings.load().token.isBlank())
    } finally {
        withContext(Dispatchers.Main) { manager?.stop(); scope.cancel() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        withContext(Dispatchers.IO) { server.shutdown() }
        context.deleteSharedPreferences("voice-check-home-$suffix")
        context.deleteSharedPreferences("voice-check-voice-$suffix")
    }
}
