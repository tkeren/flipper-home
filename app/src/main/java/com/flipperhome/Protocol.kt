package com.flipperhome

/** The small subset of official Flipper protobuf used by this app. */
object Protocol {
    fun varint(value: Int): ByteArray {
        require(value >= 0)
        var n = value
        val out = ArrayList<Byte>()
        do { out.add(((n and 127) or if (n > 127) 128 else 0).toByte()); n = n ushr 7 } while (n != 0)
        return out.toByteArray()
    }
    fun number(field: Int, value: Int) = varint(field shl 3) + varint(value)
    fun bytes(field: Int, value: ByteArray) = varint((field shl 3) or 2) + varint(value.size) + value
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun request(id: Int, field: Int, payload: ByteArray): ByteArray {
        val body = number(1, id) + bytes(field, payload)
        return varint(body.size) + body
    }
    // Scalars retain all 64 wire bits. Lengths and keys have separate bounded readers.
    data class Field(val tag: Int, val number: Long = 0, val data: ByteArray = byteArrayOf())
    fun readVarint(data: ByteArray, offset: Int): Pair<Int, Int>? {
        var n = 0; var pos = offset
        for (shift in 0..28 step 7) {
            if (pos >= data.size) return null
            val b = data[pos++].toInt() and 255
            require(shift != 28 || b <= 7) { "Oversized protobuf integer" }
            n = n or ((b and 127) shl shift)
            if (b and 128 == 0) return n to pos
        }
        error("Invalid protobuf integer")
    }
    private fun readScalar(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L; var pos = offset
        for (index in 0..9) {
            require(pos < data.size) { "Truncated protobuf scalar at byte $pos" }
            val byte = data[pos++].toInt() and 255
            require(index != 9 || byte <= 1) { "Protobuf scalar exceeds 64 bits at byte ${pos - 1}" }
            value = value or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) return value to pos
        }
        error("Invalid protobuf scalar")
    }
    fun fields(data: ByteArray): List<Field> {
        val out = ArrayList<Field>(); var p = 0
        fun integer(): Int { val v = requireNotNull(readVarint(data, p)) { "Truncated protobuf integer at byte $p" }; p = v.second; return v.first }
        while (p < data.size) {
            val key = integer(); require(key ushr 3 > 0) { "Invalid protobuf field at byte $p" }
            when (key and 7) {
                0 -> {
                    val (value, end) = readScalar(data, p)
                    p = end
                    out.add(Field(key ushr 3, value))
                }
                2 -> { val size = integer(); require(size <= data.size - p) { "Truncated protobuf field ${key ushr 3}: needs $size bytes, has ${data.size - p}" }; out.add(Field(key ushr 3, data = data.copyOfRange(p, p + size))); p += size }
                1 -> { require(p + 8 <= data.size) { "Truncated fixed64 field" }; p += 8 }
                5 -> { require(p + 4 <= data.size) { "Truncated fixed32 field" }; p += 4 }
                else -> error("Unsupported protobuf wire type")
            }
        }
        return out
    }

    fun screenPixels(message: List<Field>): ByteArray? {
        val frame = message.firstOrNull { it.tag == 22 } ?: return null
        val pixels = requireNotNull(fields(frame.data).firstOrNull { it.tag == 1 }) { "Screen frame is missing pixel data" }.data
        require(pixels.size == 1024) { "Unexpected screen size: ${pixels.size} bytes" }
        return pixels
    }

    /** BLE packet boundaries need not match protobuf message boundaries. */
    class StreamDecoder {
        private var buffer = byteArrayOf()
        val bufferedBytes: Int get() = buffer.size
        fun reset() { buffer = byteArrayOf() }
        fun append(bytes: ByteArray): List<List<Field>> {
            require(buffer.size + bytes.size <= 1024 * 1024) { "RPC buffer exceeded limit" }
            buffer += bytes
            val messages = mutableListOf<List<Field>>()
            while (buffer.isNotEmpty()) {
                val (length, start) = readVarint(buffer, 0) ?: break
                require(length <= 1024 * 1024) { "RPC message exceeded limit" }
                if (buffer.size - start < length) break
                messages.add(fields(buffer.copyOfRange(start, start + length)))
                buffer = buffer.copyOfRange(start + length, buffer.size)
            }
            return messages
        }
    }
}
