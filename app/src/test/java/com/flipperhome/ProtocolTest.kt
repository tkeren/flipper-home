package com.flipperhome

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun requestsUseOfficialTagsAndLengthFraming() {
        val packet = Protocol.request(150, 75, Protocol.string(1, "Power"))
        val header = Protocol.readVarint(packet, 0)!!
        assertEquals(packet.size - header.second, header.first)
        val fields = Protocol.fields(packet.copyOfRange(header.second, packet.size))
        assertEquals(150L, fields.first { it.tag == 1 }.number)
        assertEquals("Power", Protocol.fields(fields.first { it.tag == 75 }.data).single().data.toString(Charsets.UTF_8))
    }
    @Test fun fragmentedVarintWaitsForMoreBytes() { assertNull(Protocol.readVarint(byteArrayOf(0x80.toByte()), 0)) }
    @Test fun repeatedStorageMessagesArePreserved() {
        val fields = Protocol.fields(Protocol.bytes(1, byteArrayOf(1)) + Protocol.bytes(1, byteArrayOf(2)))
        assertEquals(2, fields.size)
        assertArrayEquals(byteArrayOf(2), fields[1].data)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedPayloadIsRejected() { Protocol.fields(byteArrayOf(10, 4, 1)) }
    @Test(expected = IllegalArgumentException::class) fun oversizedFrameLengthIsRejected() { Protocol.readVarint(byteArrayOf(-1, -1, -1, -1, 127), 0) }

    @Test fun fullScreenSurvivesArbitraryBluetoothFragmentation() {
        val pixels = ByteArray(1024) { (it * 31).toByte() }
        val packet = Protocol.request(0, 22, Protocol.bytes(1, pixels))
        for (chunkSize in listOf(1, 20, 243, 486)) {
            val decoder = Protocol.StreamDecoder()
            val messages = packet.toList().chunked(chunkSize).flatMap { decoder.append(it.toByteArray()) }
            val screen = Protocol.fields(messages.single().first { it.tag == 22 }.data).single()
            assertArrayEquals("Chunk size $chunkSize", pixels, screen.data)
            assertEquals(0, decoder.bufferedBytes)
        }
    }

    @Test fun joinedAckAndPartialScreenWaitForCompleteFrame() {
        val decoder = Protocol.StreamDecoder()
        val ack = Protocol.request(17, 4, byteArrayOf())
        val frame = Protocol.request(0, 22, Protocol.bytes(1, ByteArray(1024)))
        val messages = decoder.append(ack + frame.copyOfRange(0, 200))
        assertEquals(17L, messages.single().first { it.tag == 1 }.number)
        assertEquals(200, decoder.bufferedBytes)
        val screens = decoder.append(frame.copyOfRange(200, frame.size) + frame)
        assertEquals(2, screens.size)
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test fun reconnectDiscardsIncompletePreviousSession() {
        val decoder = Protocol.StreamDecoder()
        val frame = Protocol.request(0, 22, Protocol.bytes(1, ByteArray(1024)))
        assertTrue(decoder.append(frame.copyOfRange(0, 75)).isEmpty())
        decoder.reset()
        val ping = decoder.append(Protocol.request(5, 6, byteArrayOf()))
        assertEquals(5L, ping.single().first { it.tag == 1 }.number)
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test fun malformedMessageExplainsTheReceiveFailure() {
        try {
            Protocol.StreamDecoder().append(byteArrayOf(3, 10, 4, 1))
            fail("Expected malformed protobuf to be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("Truncated protobuf field 1: needs 4 bytes, has 1", e.message)
        }
    }

    @Test fun unsignedCommandIdUsesAll32Bits() {
        // Independent wire fixture: field 1 uint32=0xFFFFFFFF, field 4 Empty.
        val message = byteArrayOf(0x08, -1, -1, -1, -1, 0x0F, 0x22, 0)
        assertEquals(4294967295L, Protocol.fields(message).first { it.tag == 1 }.number)
    }

    @Test fun screenMetadataSupportsTenByteSignedAndFullWidthUnsignedScalars() {
        // Literal schema fixture: pixels field 1, orientation field 2=-1, unknown field 3=uint64 max.
        // The signed enum is legal wire data even if its value is unfamiliar to this app.
        val pixels = ByteArray(1024) { (it xor 0xA5).toByte() }
        val negativeOne = ByteArray(10) { if (it == 9) 1 else -1 }
        val payload = byteArrayOf(0x0A, 0x80.toByte(), 0x08) + pixels + byteArrayOf(0x10) + negativeOne + byteArrayOf(0x18) + negativeOne
        // Main field 22 has key B2 01; its 1049-byte payload length is 99 08.
        val body = byteArrayOf(0xB2.toByte(), 0x01, 0x99.toByte(), 0x08) + payload
        // 1053-byte Main message length is 9D 08.
        val packet = byteArrayOf(0x9D.toByte(), 0x08) + body
        for (chunkSize in listOf(20, 217, 411)) {
            val decoder = Protocol.StreamDecoder()
            val messages = packet.toList().chunked(chunkSize).flatMap { decoder.append(it.toByteArray()) }
            assertArrayEquals(pixels, Protocol.screenPixels(messages.single()))
            assertEquals(0, decoder.bufferedBytes)
        }
        assertEquals(-1L, Protocol.fields(payload).first { it.tag == 2 }.number)
        assertEquals(-1L, Protocol.fields(payload).first { it.tag == 3 }.number)
    }

    @Test fun uint64AboveSigned32BitRangeDoesNotTruncate() {
        // field 3 uint64=2^32.
        assertEquals(4294967296L, Protocol.fields(byteArrayOf(0x18, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10)).single().number)
    }

    @Test(expected = IllegalArgumentException::class) fun overlongScalarRemainsInvalid() {
        Protocol.fields(byteArrayOf(0x08) + ByteArray(11) { 0x80.toByte() })
    }

    @Test(expected = IllegalArgumentException::class) fun scalarAbove64BitsRemainsInvalid() {
        Protocol.fields(byteArrayOf(0x08) + ByteArray(9) { -1 } + byteArrayOf(2))
    }

    @Test(expected = IllegalArgumentException::class) fun scalarSupportDoesNotPermitOversizedPayloadLengths() {
        Protocol.fields(byteArrayOf(0x0A, -1, -1, -1, -1, 0x0F))
    }
}
