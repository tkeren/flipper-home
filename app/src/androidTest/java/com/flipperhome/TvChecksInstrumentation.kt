package com.flipperhome

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** Runs only in the test APK. Loopback TLS exercises Android Keystore without a real TV. */
class TvChecksInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking { withTimeout(45000) { checkPersistence(); checkTlsPlayback() } }
            result.putString("stream","Google TV native checks passed: legacy/new storage, mutual TLS, authenticated pairing, tap, timed hold, release, cancellation, early release, identity pinning.\n")
            finish(Activity.RESULT_OK,result)
        } catch(e: Throwable) {
            result.putString("stream","Google TV native checks FAILED: ${e.stackTraceToString().take(5000)}\n")
            finish(Activity.RESULT_CANCELED,result)
        }
    }
    private fun checkPersistence() {
        val preferencesName = "tv-check-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = targetContext.getSharedPreferences(preferencesName,mode)
        }
        val store = HomeStore(isolated)
        try {
            val room = Room("check-room","Living room")
            var home = Home(listOf(room)).withChromecast(remoteTemplate(room.id,"Chromecast",RemoteCategory.CHROMECAST))
            val remote = home.remotes.single()
            val radio = RemoteButton(room = room.id,device = remote.name,name = "Light off",path = "/ext/subghz/test.sub",signal = "",remoteId = remote.id)
            home = home.copy(buttons = home.buttons + radio,scenes = listOf(Scene(name = "Mixed",buttons = listOf(home.buttons.first().id,radio.id),holdsMs = listOf(null,5000L))),
                tvDevices = home.tvDevices.map { it.copy(host = "127.0.0.1") },remotes = home.remotes.map { it.copy(controls = it.controls.map { c -> c.copy(color = ControlColor.BLUE,textSize = ControlTextSize.LARGE) }) })
            store.save(home); check(home == store.load()) { "Mixed remote storage roundtrip failed" }
            val data = JSONObject(isolated.getSharedPreferences("home",Context.MODE_PRIVATE).getString("data",null)!!)
            data.remove("tvDevices")
            val buttons = data.getJSONArray("buttons")
            for(index in 0 until buttons.length()) { buttons.getJSONObject(index).remove("tvId"); buttons.getJSONObject(index).remove("tvKey") }
            val remotes = data.getJSONArray("remotes")
            for(index in 0 until remotes.length()) { remotes.getJSONObject(index).remove("tvId"); remotes.getJSONObject(index).put("category","TV") }
            isolated.getSharedPreferences("home",Context.MODE_PRIVATE).edit().putString("data",data.toString()).commit()
            val legacy = store.load()
            check(legacy.tvDevices.isEmpty() && legacy.buttons.none { it.isTv } && legacy.remotes.all { it.tvId.isEmpty() }) { "Legacy home migration failed" }
            check(legacy.scenes == home.scenes && legacy.rooms == home.rooms) { "Legacy actions/rooms were changed" }
        } finally { targetContext.deleteSharedPreferences(preferencesName) }
    }
    private suspend fun checkTlsPlayback() {
        val tv = TvConnection(targetContext)
        val server = LoopbackTv()
        val id = "tv-check-${UUID.randomUUID()}"
        val device = TvDevice(id,"Loopback Chromecast","127.0.0.1",server.port)
        try {
            server.start()
            try { tv.startPairing(device) } catch(e: Exception) {
                if(server.code.isCancelled) try { server.code.await() } catch(serverError: Exception) { e.addSuppressed(serverError) }
                throw e
            }
            val code = server.code.await()
            try { tv.finishPairing("%02X".format((code.take(2).toInt(16)+1) and 255)+code.drop(2)); error("Incorrect code accepted") }
            catch(_: IllegalArgumentException) { }
            check(tv.finishPairing(code) == device)
            tv.connect(device); check(tv.states.value[device.id]?.connected == true)
            tv.transmit(device,TvKey.OK.code)
            check(server.commands.receive() == (23 to 3)) { "Wrong tap command" }
            val released = CompletableDeferred<Unit>()
            val held = CoroutineScope(currentCoroutineContext()).launch { tv.transmit(device,TvKey.VOLUME_UP.code,released) }
            check(server.commands.receive() == (24 to 1)); released.complete(Unit); held.join()
            check(server.commands.receive() == (24 to 2)) { "Held key was not released" }
            val timed = CoroutineScope(currentCoroutineContext()).launch { tv.transmit(device,TvKey.DOWN.code,duration = 150) }
            check(server.commands.receive() == (20 to 1)); val start = System.nanoTime(); check(server.commands.receive() == (20 to 2)); timed.join()
            check((System.nanoTime()-start)/1000000 >= 100) { "Timed key released early" }
            val cancelled = CoroutineScope(currentCoroutineContext()).launch { tv.transmit(device,TvKey.UP.code,duration = 60000) }
            check(server.commands.receive() == (19 to 1)); cancelled.cancelAndJoin()
            check(server.commands.receive() == (19 to 2)) { "Cancelled key was not released" }
            val early = CompletableDeferred<Unit>().apply { complete(Unit) }
            tv.transmit(device,TvKey.HOME.code,early)
            check(withTimeoutOrNull(250) { server.commands.receive() } == null) { "Released gesture sent a delayed key" }
            tv.disconnectAll()
            // A different TLS identity at the same device ID must never become connected.
            val impostor = LoopbackTv()
            try {
                impostor.start()
                try { tv.connect(device.copy(port = impostor.port)); error("Changed TV identity accepted") }
                catch(_: SSLException) { }
                check(tv.states.value[device.id]?.connected != true)
            } finally { impostor.close() }
        } finally {
            tv.close(); server.close()
            targetContext.getSharedPreferences("google_tv",Context.MODE_PRIVATE).edit().remove("pin:$id").commit()
        }
    }

    private class LoopbackTv {
        private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val alias = "flipper-home-tv-check-${UUID.randomUUID()}"
        private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private val certificate: X509Certificate
        private val control: SSLServerSocket
        private val pairing: SSLServerSocket
        private val sockets = java.util.concurrent.CopyOnWriteArrayList<SSLSocket>()
        val code = CompletableDeferred<String>()
        val commands = Channel<Pair<Int,Int>>(Channel.UNLIMITED)
        val port: Int get() = control.localPort
        init {
            KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).setRandomizedEncryptionRequired(false)
                    .setCertificateSubject(X500Principal("CN=Loopback Google TV"))
                    .setCertificateSerialNumber(BigInteger.valueOf(2)).setCertificateNotBefore(Date(System.currentTimeMillis()-86400000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis()+86400000*365L)).build())
                generateKeyPair()
            }
            val entry = store.getEntry(alias,null) as KeyStore.PrivateKeyEntry
            certificate = entry.certificate as X509Certificate
            val keys = object : X509ExtendedKeyManager() {
                override fun getClientAliases(type: String?,issuers: Array<Principal>?) = null
                override fun chooseClientAlias(types: Array<String>?,issuers: Array<Principal>?,socket: Socket?) = null
                override fun getServerAliases(type: String?,issuers: Array<Principal>?) = if(type == "RSA") arrayOf(alias) else null
                override fun chooseServerAlias(type: String?,issuers: Array<Principal>?,socket: Socket?): String? = if(type == "RSA") alias else null
                override fun chooseEngineServerAlias(type: String?,issuers: Array<Principal>?,engine: SSLEngine?): String? = chooseServerAlias(type,issuers,null)
                override fun getCertificateChain(alias: String?) = arrayOf(certificate)
                override fun getPrivateKey(alias: String?) = entry.privateKey
            }
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkServerTrusted(chain: Array<X509Certificate>?,type: String?) { }
                override fun checkClientTrusted(chain: Array<X509Certificate>?,type: String?) { check(!chain.isNullOrEmpty()) }
            }
            val factory = SSLContext.getInstance("TLS").apply { init(arrayOf(keys),arrayOf(trust),SecureRandom()) }.serverSocketFactory
            var first: SSLServerSocket? = null; var second: SSLServerSocket? = null
            repeat(20) {
                if(second == null) {
                    first = factory.createServerSocket(0,10,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
                    try { second = factory.createServerSocket(first!!.localPort+1,10,InetAddress.getByName("127.0.0.1")) as SSLServerSocket }
                    catch(_: Exception) { first!!.close() }
                }
            }
            control = first ?: error("No loopback port"); pairing = second ?: error("No pairing port")
            control.needClientAuth = true; pairing.needClientAuth = true
        }
        private fun response(tag: Int,body: ByteArray = byteArrayOf()) = Protocol.number(1,2)+Protocol.number(2,200)+Protocol.bytes(tag,body)
        fun start() {
            scope.launch {
                try {
                    val socket = pairing.accept() as SSLSocket; sockets += socket; socket.soTimeout = 10000; socket.startHandshake()
                    val input = socket.inputStream; val output = socket.outputStream
                    check(TvProtocol.read(input).any { it.tag == 10 }); TvProtocol.write(output,response(11))
                    check(TvProtocol.read(input).any { it.tag == 20 }); TvProtocol.write(output,response(20))
                    check(TvProtocol.read(input).any { it.tag == 30 }); TvProtocol.write(output,response(31))
                    val client = (socket.session.peerCertificates.first() as X509Certificate).publicKey as RSAPublicKey
                    val server = certificate.publicKey as RSAPublicKey
                    fun unsigned(number: BigInteger): ByteArray { val hex = number.toString(16).let { if(it.length%2 == 0) it else "0$it" }; return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
                    val proof = MessageDigest.getInstance("SHA-256").digest(unsigned(client.modulus)+unsigned(client.publicExponent)+unsigned(server.modulus)+unsigned(server.publicExponent)+byteArrayOf(0x12,0x34))
                    code.complete("%02X1234".format(proof[0].toInt() and 255))
                    val secret = Protocol.fields(TvProtocol.read(input).first { it.tag == 40 }.data).single().data
                    check(secret.contentEquals(proof)) { "Client authentication proof did not match" }
                    TvProtocol.write(output,response(41,Protocol.bytes(1,proof)))
                } catch(e: Exception) { if(!code.isCompleted) code.completeExceptionally(e) }
            }
            scope.launch {
                try {
                    val socket = control.accept() as SSLSocket; sockets += socket; socket.soTimeout = 10000; socket.startHandshake()
                    val input = socket.inputStream; val output = socket.outputStream
                    TvProtocol.write(output,Protocol.bytes(1,Protocol.number(1,99))); check(TvProtocol.read(input).any { it.tag == 1 })
                    TvProtocol.write(output,Protocol.bytes(2,Protocol.number(1,99))); check(TvProtocol.read(input).any { it.tag == 2 })
                    TvProtocol.write(output,Protocol.bytes(40,Protocol.number(1,1)))
                    while(isActive) {
                        val fields = TvProtocol.read(input)
                        val key = Protocol.fields(fields.first { it.tag == 10 }.data)
                        commands.send(key.first { it.tag == 1 }.number.toInt() to key.first { it.tag == 2 }.number.toInt())
                    }
                } catch(_: Exception) { }
            }
        }
        fun close() { sockets.forEach { runCatching { it.close() } }; control.close(); pairing.close(); scope.cancel(); store.deleteEntry(alias) }
    }
}
