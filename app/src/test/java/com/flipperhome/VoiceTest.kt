package com.flipperhome

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceTest {
    private val button = RemoteButton(id="lamp",room="room",device="Lights",name="Off",path="/ext/subghz/off.sub",signal="")
    private val home = Home(listOf(Room("room","Bedroom")),listOf(button))
    private val now = 1_000_000L

    @Test fun voiceIsOptInAndOnlyForFlipper() {
        assertTrue(home.voiceActions.isEmpty())
        val configured = home.withVoice("lamp","Bedroom lights off",null)
        assertEquals(listOf(VoiceAction("lamp","Bedroom lights off","Bedroom")),configured.voiceActions)
        assertTrue(configured.withVoice("lamp","",null).voiceActions.isEmpty())
        val tv = button.copy(id="tv",tvId="chromecast",path="")
        assertThrows(IllegalArgumentException::class.java) { home.copy(buttons=listOf(tv)).withVoice("tv","TV off",null) }
    }
    @Test fun namesAndDurationsAreValidated() {
        val two = home.copy(buttons=home.buttons + button.copy(id="other"))
        val configured = two.withVoice("lamp","Bedroom off",5000)
        assertThrows(IllegalArgumentException::class.java) { configured.withVoice("other","bedroom OFF",null) }
        assertThrows(IllegalArgumentException::class.java) { home.withVoice("lamp","off\nlight",null) }
        assertThrows(IllegalArgumentException::class.java) { home.withVoice("lamp","x".repeat(81),null) }
        listOf(99L,60001L).forEach { duration -> assertThrows(IllegalArgumentException::class.java) { home.withVoice("lamp","off",duration) } }
        assertEquals(5000L,configured.buttons.first().voiceHoldMs)
    }
    @Test fun relearningPreservesVoiceSelectionAndDuration() {
        val remote = Remote(id="remote",room="room",name="Lights",controls=listOf(RemoteControl(id="control",label="Off",bindings=mapOf(ControlZone.MAIN to "lamp"))))
        val configured = home.copy(remotes=listOf(remote)).withVoice("lamp","Bedroom lights off",5000)
        val replaced = configured.bind("remote","control",ControlZone.MAIN,button.copy(id="new",path="/ext/subghz/new.sub"))
        assertEquals("Bedroom lights off",replaced.buttons.single().voiceName)
        assertEquals(5000L,replaced.buttons.single().voiceHoldMs)
        assertEquals("Bedroom lights off",configured.replaceSignal(button.copy(path="/ext/subghz/new.sub")).buttons.single().voiceName)
        assertTrue(configured.deleteSignal("lamp").voiceActions.isEmpty())
    }
    @Test fun enabledCommandRoutesItsLocalDuration() = runBlocking {
        val sent = mutableListOf<Pair<String,Long?>>()
        val executor = VoiceExecutor({home.withVoice("lamp","Dim bedroom",5000)},{true},{false},{now}) { b,d -> sent += b.id to d }
        assertTrue(executor.execute(VoiceRequest("request","lamp",now)).success)
        assertEquals(listOf("lamp" to 5000L),sent)
    }
    @Test fun disabledUnknownAndTvCommandsNeverTransmit() = runBlocking {
        var calls = 0
        val executor = VoiceExecutor({home},{true},{false},{now}) { _,_ -> calls++ }
        assertFalse(executor.execute(VoiceRequest("a","lamp",now)).success)
        assertFalse(executor.execute(VoiceRequest("b","unknown",now)).success)
        val tvExecutor = VoiceExecutor({home.copy(buttons=listOf(button.copy(tvId="tv",voiceName="TV off")))},{true},{false},{now}) { _,_ -> calls++ }
        assertFalse(tvExecutor.execute(VoiceRequest("c","lamp",now)).success)
        assertEquals(0,calls)
    }
    @Test fun staleFutureAndDuplicateRequestsAreNotReplayed() = runBlocking {
        var calls = 0
        val executor = VoiceExecutor({home.withVoice("lamp","Off",null)},{true},{false},{now}) { _,_ -> calls++ }
        assertFalse(executor.execute(VoiceRequest("old","lamp",now-15001)).success)
        assertFalse(executor.execute(VoiceRequest("future","lamp",now+5001)).success)
        assertTrue(executor.execute(VoiceRequest("once","lamp",now)).success)
        assertFalse(executor.execute(VoiceRequest("once","lamp",now)).success)
        assertEquals(1,calls)
    }
    @Test fun offlineOrBusyRequestsFailWithoutQueueing() = runBlocking {
        var connected = false; var busy = false; var calls = 0
        val executor = VoiceExecutor({home.withVoice("lamp","Off",null)},{connected},{busy},{now}) { _,_ -> calls++ }
        assertFalse(executor.execute(VoiceRequest("offline","lamp",now)).success)
        connected=true; busy=true
        assertFalse(executor.execute(VoiceRequest("busy","lamp",now)).success)
        busy=false
        assertFalse(executor.execute(VoiceRequest("offline","lamp",now)).success)
        assertEquals(0,calls)
    }
    @Test fun failureIsNotReportedAsSuccess() = runBlocking {
        val executor = VoiceExecutor({home.withVoice("lamp","Off",null)},{true},{false},{now}) { _,_ -> error("Flipper is busy") }
        assertEquals(VoiceResult(false,"Flipper is busy"),executor.execute(VoiceRequest("req","lamp",now)))
    }
    @Test fun socketUrlRequiresHttpsAndPreservesBasePath() {
        assertEquals("wss://home.example/api/websocket",homeAssistantSocketUrl("https://home.example/"))
        assertEquals("wss://home.example:8123/ha/api/websocket",homeAssistantSocketUrl("https://home.example:8123/ha/"))
        listOf("http://home.example", "https://user:token@home.example", "https://home.example?token=secret", "https://home.example/#a", "file:///secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { homeAssistantSocketUrl(it) }
        }
    }
    @Test fun handshakeRequiresAuthenticatedRegisteredSubscription() {
        val protocol = VoiceProtocol("phone","Test phone")
        val auth = protocol.receive("{\"type\":\"auth_required\"}","test-token",emptyList()).first!!
        assertEquals("test-token",JSONObject(auth).getString("access_token"))
        val register = JSONObject(protocol.receive("{\"type\":\"auth_ok\"}","test-token",listOf(VoiceAction("lamp","Off","Bedroom"))).first!!)
        assertEquals("flipper_home/subscribe",register.getString("type"))
        assertFalse(register.toString().contains("test-token"))
        assertFalse(register.toString().contains("subghz"))
        val event="{\"id\":1,\"type\":\"event\",\"event\":{\"request_id\":\"req\",\"action_id\":\"lamp\",\"issued_at_ms\":1000000}}"
        assertNull(protocol.receive(event,"test-token",emptyList()).second)
        protocol.receive("{\"id\":1,\"type\":\"result\",\"success\":true}","test-token",emptyList())
        assertTrue(protocol.ready)
        assertEquals(VoiceRequest("req","lamp",now),protocol.receive(event,"test-token",emptyList()).second)
        assertNull(protocol.receive(event.replace("\"id\":1","\"id\":2"),"test-token",emptyList()).second)
        val update=JSONObject(protocol.update(emptyList(),true))
        val heartbeat=JSONObject(protocol.heartbeat(true))
        assertTrue(heartbeat.getInt("id") > update.getInt("id"))
    }
    @Test fun missingIntegrationAndWrongTokenAreSetupFailures() {
        val protocol=VoiceProtocol("phone","Test")
        protocol.receive("{\"type\":\"result\",\"id\":1,\"success\":false,\"error\":{\"code\":\"unknown_command\"}}","token",emptyList())
        assertTrue(protocol.error.contains("Install"))
        val wrongToken=VoiceProtocol("phone","Test")
        wrongToken.receive("{\"type\":\"auth_invalid\",\"message\":\"secret token\"}","token",emptyList())
        assertEquals("Home Assistant rejected the token",wrongToken.error)
    }
}
