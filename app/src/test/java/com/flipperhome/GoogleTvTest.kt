package com.flipperhome

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

class GoogleTvTest {
    private val room = Room("room","Living room")
    private fun home(): Home = Home(listOf(room)).withChromecast(remoteTemplate(room.id,"Chromecast",RemoteCategory.CHROMECAST))
    private fun radio() = RemoteButton(id = "light",room = room.id,device = "Light",name = "Off",path = "/ext/subghz/light.sub",signal = "")
    @Test fun templateIsMappedWithoutLearningAndCanCoexistWithFlipperSignals() {
        val home = home(); val remote = home.remotes.single()
        assertEquals(12,home.buttons.size); assertTrue(home.buttons.all { it.isTv && it.path.isEmpty() && it.tvId == remote.tvId })
        assertEquals(TvKey.entries.filter { it !in listOf(TvKey.SETTINGS,TvKey.REWIND,TvKey.FAST_FORWARD) }.map { it.code }.toSet(),home.buttons.map { it.tvKey }.toSet())
        assertTrue(remote.controls.all { it.bindings.keys.toList() == it.shape.zones || it.bindings.keys.toSet() == it.shape.zones.toSet() })
        val added = remote.copy(controls = remote.controls + RemoteControl(id = "light",label = "Light off"))
        val mixed = home.withRemote(added).bind(remote.id,"light",ControlZone.MAIN,radio())
        assertEquals(home.buttons,mixed.buttons.filter { it.isTv }); assertTrue(mixed.buttons.any { !it.isTv })
        assertEquals(mixed,mixed.migrateRemotes())
    }
    @Test fun availabilityChecksEachTransportAndDoesNotGateTvOnFlipper() {
        val home = home(); val device = home.tvDevices.single()
        assertFalse(home.canUse(home.buttons.first(),false))
        val paired = home.copy(tvDevices = listOf(device.copy(host = "192.168.1.20")),buttons = home.buttons + radio())
        assertTrue(paired.canUse(paired.buttons.first(),false)); assertFalse(paired.canUse(radio(),false))
        assertTrue(paired.canUse(Scene(name = "TV",buttons = listOf(paired.buttons.first().id)),false))
        val mixed = Scene(name = "Bedtime",buttons = listOf(paired.buttons.first().id,"light"))
        assertFalse(paired.canUse(mixed,false)); assertTrue(paired.canUse(mixed,true))
    }
    @Test fun remappingATvKeyDoesNotModifySharedCommandsOrCaptureFiles() {
        val home = home(); val remote = home.remotes.single(); val source = remote.controls.first { it.label == "Home" }
        val copy = RemoteControl(id = "copy",label = "Copy",bindings = source.bindings)
        val shared = home.withRemote(remote.copy(controls = remote.controls + copy))
        val changed = shared.bindTv(shared.remotes.single(),source.id,ControlZone.MAIN,shared.tvDevices.single(),TvKey.BACK)
        val originalId = source.bindings.getValue(ControlZone.MAIN)
        assertEquals(TvKey.HOME.code,changed.buttons.first { it.id == originalId }.tvKey)
        assertNotEquals(originalId,changed.remotes.single().controls.first { it.id == source.id }.bindings[ControlZone.MAIN])
        assertEquals(originalId,changed.remotes.single().controls.last().bindings[ControlZone.MAIN])
    }
    @Test fun replacingATvButtonWithFlipperKeepsTheOriginalNetworkActionReusable() {
        val home = home(); val remote = home.remotes.single(); val control = remote.controls.first { it.label == "Power" }
        val previousId = control.bindings.getValue(ControlZone.MAIN)
        val scene = Scene(name = "TV off",buttons = listOf(previousId))
        val changed = home.copy(scenes = listOf(scene)).bind(remote.id,control.id,ControlZone.MAIN,radio())
        assertTrue(changed.buttons.first { it.id == previousId }.isTv)
        assertEquals(scene,changed.scenes.single())
        assertEquals("light",changed.remotes.single().controls.first { it.id == control.id }.bindings[ControlZone.MAIN])
    }
    @Test fun mixedAutomationDispatchesInOrderWithPerActionHolds() = runBlocking {
        val tv = home().buttons.first(); val radio = radio(); val calls = mutableListOf<String>()
        val sender = UniversalSender({ action,_,duration,_ -> calls += "flipper:${action.id}:$duration" },{ action,_,duration,_ -> calls += "tv:${action.id}:$duration" })
        RoutineRunner.run(Scene(name = "Movie",buttons = listOf(radio.id,tv.id,radio.id),delayMs = 0,holdsMs = listOf(5000L,null,2000L)),listOf(tv,radio)) { action,duration -> sender.transmit(action,duration = duration) }
        assertEquals(listOf("flipper:light:5000","tv:${tv.id}:null","flipper:light:2000"),calls)
    }
    @Test fun tvHoldKeepsTheOriginalReleaseSignalAndAcknowledgementCallback() = runBlocking {
        val release = CompletableDeferred<Unit>(); var acknowledged = false
        val sender = UniversalSender({ _,_,_,_ -> fail("TV button used Flipper") },{ _,signal,duration,started -> assertSame(release,signal); assertNull(duration); started() })
        sender.transmit(home().buttons.first(),release,started = { acknowledged = true }); assertTrue(acknowledged)
    }
    @Test fun keysMatchIndependentWireFixturesForTapPressAndRelease() {
        assertArrayEquals(byteArrayOf(0x52,4,8,23,16,3),TvProtocol.key(TvKey.OK.code))
        assertArrayEquals(byteArrayOf(0x52,4,8,24,16,1),TvProtocol.key(TvKey.VOLUME_UP.code,1))
        assertArrayEquals(byteArrayOf(0x52,4,8,24,16,2),TvProtocol.key(TvKey.VOLUME_UP.code,2))
    }
    @Test fun framingReadsFragmentedLengthsAndPayloadsWithoutConsumingTheNextMessage() {
        val output = ByteArrayOutputStream(); val payload = Protocol.string(5,"x".repeat(140)); TvProtocol.write(output,payload); TvProtocol.write(output,TvProtocol.key(23))
        val bytes = ByteArrayInputStream(output.toByteArray())
        val fragmented = object : InputStream() { override fun read() = bytes.read(); override fun read(b: ByteArray,off: Int,len: Int) = bytes.read(b,off,minOf(len,1)) }
        assertEquals("x".repeat(140),TvProtocol.read(fragmented).single().data.toString(Charsets.UTF_8))
        assertEquals(10,TvProtocol.read(fragmented).single().tag)
    }
    @Test fun malformedOrTruncatedFramesAreRejectedBeforeAllocation() {
        for(bytes in listOf(byteArrayOf(0),Protocol.varint(TvProtocol.MAX_FRAME+1),byteArrayOf(-1,-1,-1,-1,8))) {
            try { TvProtocol.read(ByteArrayInputStream(bytes)); fail("Expected invalid frame") } catch(_: IllegalArgumentException) { }
        }
        try { TvProtocol.read(ByteArrayInputStream(byteArrayOf(6,0x52))); fail("Expected incomplete frame") } catch(_: EOFException) { }
    }
    @Test fun handshakeNegotiatesSupportedFeaturesAndWaitsForRemoteStart() {
        val handshake = TvProtocol.Handshake()
        val reply = handshake.respond(Protocol.fields(byteArrayOf(10,3,8,(-17).toByte(),4)))!! // offered mask 623
        val config = Protocol.fields(Protocol.fields(reply).single().data)
        assertEquals(99L,config.first { it.tag == 1 }.number); assertFalse(handshake.ready)
        assertEquals(2,Protocol.fields(handshake.respond(Protocol.fields(byteArrayOf(18,2,8,99)))!!).single().tag)
        handshake.respond(Protocol.fields(byteArrayOf((-62).toByte(),2,2,8,0))) // RemoteStart, even if TV is asleep
        assertTrue(handshake.ready)
    }
    @Test fun pingRepliesPreserveNegativeInt32Values() {
        val negative = byteArrayOf(8,-1,-1,-1,-1,-1,-1,-1,-1,-1,1)
        val reply = TvProtocol.Handshake().respond(Protocol.fields(Protocol.bytes(8,negative)))!!
        assertArrayEquals(negative,Protocol.fields(reply).single().data)
        assertEquals(9,Protocol.fields(reply).single().tag)
    }
    @Test fun pairingMessagesRequestSixHexCharactersAndRejectWrongStages() {
        val encoding = Protocol.fields(Protocol.fields(TvProtocol.pairOptions()).first { it.tag == 20 }.data)
        val fields = Protocol.fields(encoding.first { it.tag == 1 }.data)
        assertEquals(3L,fields[0].number); assertEquals(6L,fields[1].number)
        TvProtocol.checkPairResponse(Protocol.fields(byteArrayOf(8,2,16,(-56).toByte(),1,90,0)),11)
        try { TvProtocol.checkPairResponse(Protocol.fields(Protocol.number(2,402)+Protocol.bytes(41,byteArrayOf())),41); fail("Expected code rejection") } catch(_: IllegalArgumentException) { }
        try { TvProtocol.checkPairResponse(Protocol.fields(Protocol.number(2,200)+Protocol.bytes(20,byteArrayOf())),31); fail("Expected stage rejection") } catch(_: IllegalArgumentException) { }
    }
    @Test fun pairingCodeBindsBothRsaKeysAndRejectsWrongCodeOrWrongTv() {
        val factory = KeyFactory.getInstance("RSA")
        fun key(number: String) = factory.generatePublic(RSAPublicKeySpec(BigInteger(number,16),BigInteger.valueOf(65537))) as RSAPublicKey
        val client = key("80"+"12".repeat(127)); val server = key("90"+"34".repeat(127))
        val bytes = TvProtocol.unsigned(client.modulus)+byteArrayOf(1,0,1)+TvProtocol.unsigned(server.modulus)+byteArrayOf(1,0,1)+byteArrayOf(0x12,0x34)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
        val code = "%02X1234".format(expected[0].toInt() and 255)
        assertArrayEquals(expected,TvProtocol.proof(client,server,code))
        assertEquals(128,TvProtocol.unsigned(client.modulus).size)
        for(invalid in listOf("12345","NOTHEX","%02X1234".format((expected[0].toInt()+1) and 255))) {
            try { TvProtocol.proof(client,server,invalid); fail("Expected invalid code") } catch(_: IllegalArgumentException) { }
        }
        try { TvProtocol.proof(client,key("A0"+"56".repeat(127)),code); fail("Expected wrong TV") } catch(_: IllegalArgumentException) { }
    }
}
