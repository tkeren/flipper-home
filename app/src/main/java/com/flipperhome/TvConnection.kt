package com.flipperhome

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TvState(val connected: Boolean = false,val connecting: Boolean = false,val message: String = "Pair Chromecast")

/** Client keys stay in Android Keystore; only a code-verified TV's public-key pin is saved. */
internal class TvIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("google_tv",Context.MODE_PRIVATE)
    private val alias = "flipper-home-google-tv"
    @Synchronized private fun entry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore")
            // Conscrypt also uses the raw RSA operation when authenticating with a Keystore key.
            generator.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).setRandomizedEncryptionRequired(false)
                .setCertificateSubject(X500Principal("CN=Flipper Home"))
                .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(Date(System.currentTimeMillis()-86400000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis()+20L*365*86400000)).build())
            generator.generateKeyPair()
        }
        return store.getEntry(alias,null) as KeyStore.PrivateKeyEntry
    }
    fun pin(id: String): String? = preferences.getString("pin:$id",null)
    fun savePin(id: String,certificate: X509Certificate) {
        check(preferences.edit().putString("pin:$id",fingerprint(certificate)).commit()) { "Could not save TV pairing" }
    }
    private fun fingerprint(cert: X509Certificate) = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded),Base64.NO_WRAP)
    fun socket(pin: String?): SSLSocket {
        val identity = entry()
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?,issuers: Array<java.security.Principal>?): Array<String>? = if(keyType == "RSA") arrayOf(alias) else null
            override fun chooseClientAlias(keyType: Array<String>?,issuers: Array<java.security.Principal>?,socket: Socket?): String? = if(keyType?.any { it == "RSA" } == true) alias else null
            override fun chooseEngineClientAlias(keyType: Array<String>?,issuers: Array<java.security.Principal>?,engine: SSLEngine?): String? = chooseClientAlias(keyType,issuers,null)
            override fun getServerAliases(keyType: String?,issuers: Array<java.security.Principal>?) = null
            override fun chooseServerAlias(keyType: String?,issuers: Array<java.security.Principal>?,socket: Socket?) = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = identity.certificateChain.map { it as X509Certificate }.toTypedArray()
            override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?,authType: String?) { throw CertificateException("Client trust is not used") }
            override fun checkServerTrusted(chain: Array<X509Certificate>?,authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("TV certificate is missing")
                if(pin != null && fingerprint(cert) != pin) throw CertificateException("TV identity changed. Pair the TV again.")
            }
        }
        return (SSLContext.getInstance("TLS").apply { init(arrayOf(keys),arrayOf(trust),SecureRandom()) }.socketFactory.createSocket() as SSLSocket).apply {
            useClientMode = true; soTimeout = 30000; tcpNoDelay = true
        }
    }
}

internal class TvConnection(context: Context) {
    private val identity = TvIdentity(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val clients = ConcurrentHashMap<String,Client>()
    private val mutableStates = MutableStateFlow<Map<String,TvState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private data class PairSession(val device: TvDevice,val socket: SSLSocket)
    @Volatile private var pairing: PairSession? = null
    private val pairingGeneration = AtomicInteger()
    private fun state(id: String,value: TvState) { mutableStates.update { it + (id to value) } }
    fun paired(device: TvDevice) = identity.pin(device.id) != null && device.host.isNotBlank()

    // Closing the socket wakes blocking TLS reads on coroutine cancellation.
    private suspend fun <T> io(socket: SSLSocket,work: () -> T): T = suspendCancellableCoroutine { continuation ->
        val job = scope.launch {
            try { val value = work(); if(continuation.isActive) continuation.resume(value) }
            catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
        }
        continuation.invokeOnCancellation { runCatching { socket.close() }; job.cancel() }
    }
    private suspend fun open(device: TvDevice,port: Int,pin: String?): SSLSocket {
        require(device.host.isNotBlank()) { "Choose a Chromecast first" }
        require(port in 1..65535) { "Invalid TV port" }
        val socket = withContext(Dispatchers.IO) { identity.socket(pin) }
        try {
            withTimeout(10000) { io(socket) { socket.connect(InetSocketAddress(device.host,port),6000); socket.startHandshake() } }
            return socket
        } catch(e: Exception) { runCatching { socket.close() }; throw e }
    }
    suspend fun startPairing(device: TvDevice) {
        cancelPairing(); clients.remove(device.id)?.close()
        val generation = pairingGeneration.get()
        val socket = open(device,device.port+1,null)
        try {
            withTimeout(12000) { io(socket) {
                val output = socket.outputStream; val input = socket.inputStream
                TvProtocol.write(output,TvProtocol.pairRequest()); TvProtocol.checkPairResponse(TvProtocol.read(input),11)
                TvProtocol.write(output,TvProtocol.pairOptions()); TvProtocol.checkPairResponse(TvProtocol.read(input),20)
                TvProtocol.write(output,TvProtocol.pairConfiguration()); TvProtocol.checkPairResponse(TvProtocol.read(input),31)
            } }
            check(pairingGeneration.get() == generation) { "Pairing was cancelled. Start again." }
            pairing = PairSession(device,socket)
        } catch(e: Exception) { socket.close(); throw e }
    }
    suspend fun finishPairing(code: String): TvDevice {
        val session = pairing ?: error("Start pairing again to get a new code")
        val peer = session.socket.session.peerCertificates.first() as X509Certificate
        val client = session.socket.session.localCertificates.first() as X509Certificate
        val proof = TvProtocol.proof(client.publicKey as RSAPublicKey,peer.publicKey as RSAPublicKey,code)
        try {
            withTimeout(10000) { io(session.socket) {
                TvProtocol.write(session.socket.outputStream,TvProtocol.pairSecret(proof))
                TvProtocol.checkPairResponse(TvProtocol.read(session.socket.inputStream),41)
            } }
            identity.savePin(session.device.id,peer)
            return session.device
        } finally { cancelPairing() }
    }
    fun cancelPairing() { pairingGeneration.incrementAndGet(); val previous = pairing; pairing = null; runCatching { previous?.socket?.close() } }
    suspend fun connect(device: TvDevice) {
        val client = clients.computeIfAbsent(device.id) { Client(device) }
        if(client.device.host != device.host || client.device.port != device.port) { client.close(); clients.remove(device.id,client); connect(device); return }
        client.connect()
    }
    suspend fun transmit(device: TvDevice,key: Int,release: Deferred<Unit>? = null,duration: Long? = null,started: () -> Unit = {}) {
        require(TvKey.entries.any { it.code == key }) { "Choose a Google TV command" }
        if(release?.isCompleted == true) return
        connect(device)
        currentCoroutineContext().ensureActive()
        if(release?.isCompleted == true) return
        val client = clients.getValue(device.id)
        if(release == null && duration == null) { client.write(TvProtocol.key(key)); return }
        var pressed = false
        try {
            client.write(TvProtocol.key(key,1)); pressed = true; started()
            if(duration != null) { require(duration in 100..60000); delay(duration) } else release!!.await()
        } finally {
            if(pressed) withContext(NonCancellable) { try { withTimeout(2000) { client.write(TvProtocol.key(key,2)) } } catch(_: Exception) { client.close() } }
        }
    }
    fun disconnectAll() { cancelPairing(); clients.values.forEach { it.close() }; clients.clear() }
    fun close() { disconnectAll(); scope.cancel() }

    private inner class Client(val device: TvDevice) {
        private val generation = AtomicInteger()
        private val connectionLock = Mutex()
        private val writeLock = Mutex()
        @Volatile private var socket: SSLSocket? = null
        private var reader: Job? = null
        private var ready = CompletableDeferred<Unit>()
        suspend fun connect() = connectionLock.withLock {
            if(socket?.isClosed == false && ready.isCompleted && !ready.isCancelled && reader?.isActive == true) return@withLock
            val pin = identity.pin(device.id) ?: error("Pair ${device.name} first")
            state(device.id,TvState(connecting = true,message = "Connecting…"))
            closeSocket(); ready = CompletableDeferred()
            val attempt = generation.get()
            try {
                val current = open(device,device.port,pin)
                if(generation.get() != attempt) { current.close(); throw CancellationException("TV connection cancelled") }
                socket = current
                val awaiting = ready
                reader = scope.launch {
                    try {
                        val handshake = TvProtocol.Handshake()
                        while(isActive) {
                            val response = handshake.respond(TvProtocol.read(current.inputStream))
                            if(response != null) write(response)
                            if(handshake.ready && !awaiting.isCompleted) awaiting.complete(Unit)
                        }
                    } catch(e: Exception) {
                        if(!awaiting.isCompleted) awaiting.completeExceptionally(e)
                        if(socket === current) { state(device.id,TvState(message = "Reconnect Chromecast")); closeSocket() }
                    }
                }
                withTimeout(10000) { awaiting.await() }
                state(device.id,TvState(connected = true,message = "${device.name} · Wi-Fi"))
            } catch(e: Exception) {
                close(); state(device.id,TvState(message = "Reconnect Chromecast")); throw e
            }
        }
        suspend fun write(message: ByteArray) = writeLock.withLock {
            val current = socket ?: error("Chromecast disconnected")
            try { withTimeout(5000) { io(current) { TvProtocol.write(current.outputStream,message) } } }
            catch(e: Exception) { close(); throw e }
        }
        private fun closeSocket() { generation.incrementAndGet(); val previous = socket; socket = null; runCatching { previous?.close() }; reader?.cancel(); reader = null }
        fun close() { closeSocket(); state(device.id,TvState(message = if(paired(device)) "Reconnect Chromecast" else "Pair Chromecast")) }
    }
}
