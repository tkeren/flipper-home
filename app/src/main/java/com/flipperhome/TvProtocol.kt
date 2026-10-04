package com.flipperhome

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Android TV Remote v2 / Polo wire format. See the protocol references in README. */
internal object TvProtocol {
    const val MAX_FRAME = 1024*1024
    fun write(output: OutputStream,message: ByteArray) {
        require(message.size in 1..MAX_FRAME)
        output.write(Protocol.varint(message.size)); output.write(message); output.flush()
    }
    fun read(input: InputStream): List<Protocol.Field> {
        var length = 0
        for(index in 0..4) {
            val b = input.read(); if(b < 0) throw EOFException("Google TV closed the connection")
            require(index != 4 || b <= 7) { "Invalid Google TV frame length" }
            length = length or ((b and 127) shl (index*7))
            if(b and 128 == 0) {
                require(length in 1..MAX_FRAME) { "Google TV frame exceeds limit" }
                val frame = ByteArray(length); var offset = 0
                while(offset < length) { val count = input.read(frame,offset,length-offset); if(count < 0) throw EOFException("Incomplete Google TV response"); offset += count }
                return Protocol.fields(frame)
            }
        }
        error("Invalid Google TV frame length")
    }
    private fun pairing(tag: Int,body: ByteArray) = Protocol.number(1,2) + Protocol.number(2,200) + Protocol.bytes(tag,body)
    fun pairRequest() = pairing(10,Protocol.string(1,"atvremote") + Protocol.string(2,"Flipper Home"))
    private fun encoding() = Protocol.number(1,3) + Protocol.number(2,6)
    fun pairOptions() = pairing(20,Protocol.bytes(1,encoding()) + Protocol.number(3,1))
    fun pairConfiguration() = pairing(30,Protocol.bytes(1,encoding()) + Protocol.number(2,1))
    fun pairSecret(proof: ByteArray) = pairing(40,Protocol.bytes(1,proof))
    fun checkPairResponse(fields: List<Protocol.Field>,expected: Int) {
        val status = fields.firstOrNull { it.tag == 2 }?.number
        require(status == 200L) { if(status == 402L) "Pairing code rejected. Start pairing again." else "Google TV rejected pairing ($status)" }
        require(fields.any { it.tag == expected }) { "Unexpected Google TV pairing response" }
    }
    fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let { if(it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1,it.size) else it }
    fun proof(client: RSAPublicKey,server: RSAPublicKey,code: String): ByteArray {
        require(code.matches(Regex("[0-9a-fA-F]{6}"))) { "Enter the six-character code on your TV" }
        val salt = code.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(unsigned(client.modulus)+unsigned(client.publicExponent)+unsigned(server.modulus)+unsigned(server.publicExponent)+salt)
        require((digest[0].toInt() and 255) == code.take(2).toInt(16)) { "Code does not match this TV. Check the code and try again." }
        return digest
    }
    fun key(code: Int,direction: Int = 3) = Protocol.bytes(10,Protocol.number(1,code)+Protocol.number(2,direction))
    private fun scalar(field: Int,value: Long): ByteArray {
        val bytes = mutableListOf<Byte>(); var remaining = value
        do { bytes += ((remaining and 127) or if(remaining ushr 7 != 0L) 128 else 0).toByte(); remaining = remaining ushr 7 } while(remaining != 0L)
        return Protocol.varint(field shl 3) + bytes.toByteArray()
    }
    class Handshake {
        private var features = 99 // ping, keys, power and volume; microphone/IME are not advertised.
        var ready = false; private set
        fun respond(fields: List<Protocol.Field>): ByteArray? {
            val config = fields.firstOrNull { it.tag == 1 }
            if(config != null) {
                features = features and (Protocol.fields(config.data).firstOrNull { it.tag == 1 }?.number?.toInt() ?: 0)
                require(features and 2 != 0) { "This TV does not support remote buttons" }
                val info = Protocol.number(3,1)+Protocol.string(4,"1")+Protocol.string(5,"com.flipperhome")+Protocol.string(6,"0.1.0")
                return Protocol.bytes(1,Protocol.number(1,features)+Protocol.bytes(2,info))
            }
            if(fields.any { it.tag == 2 }) return Protocol.bytes(2,Protocol.number(1,features))
            fields.firstOrNull { it.tag == 8 }?.let { ping ->
                val value = Protocol.fields(ping.data).firstOrNull { it.tag == 1 }?.number ?: 0
                return Protocol.bytes(9,scalar(1,value))
            }
            require(fields.none { it.tag == 3 }) { "Google TV rejected the remote command" }
            if(fields.any { it.tag == 40 }) ready = true
            return null
        }
    }
}
