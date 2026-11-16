package com.mytvs.ntorq

/**
 * Codec for the TVS Ntorq 150 TFT cluster BLE frames.
 *
 * Reverse-engineered from the owner's own cluster (nRF Connect captures) and
 * cross-checked against the TVS Connect app's own BLE classes. Verified: this
 * decoder/encoder reproduces every captured frame byte-for-byte.
 *
 * Wire frame = 20 bytes:  5A | ID | D0..D15 (16) | CHK | FF
 *  - checksum  = (0xFF - (sum(plaintext[0..17]) & 0xFF)) & 0xFF   (over 5A,ID,16 data)
 *  - obfuscation ("encryption"): XOR 0xEA over bytes 2..18 (the 16 data + checksum).
 *    The 0x5A start, the ID byte and the 0xFF end stay in clear.
 */
object NtorqCodec {
    const val START: Int = 0x5A
    const val END: Int = 0xFF
    const val XOR_KEY: Int = 0xEA
    const val FRAME_LEN: Int = 20

    /** A decoded frame: its id and the 16 plaintext data bytes. */
    class Frame(val id: Int, val payload: ByteArray, val checksumValid: Boolean) {
        /** Unsigned value of data byte i (0..15). */
        fun u(i: Int): Int = payload[i].toInt() and 0xFF
    }

    /** Decode a 20-byte wire frame. Returns null if it is not a well-formed frame. */
    fun decode(wire: ByteArray): Frame? {
        if (wire.size != FRAME_LEN) return null
        if ((wire[0].toInt() and 0xFF) != START) return null
        if ((wire[19].toInt() and 0xFF) != END) return null
        val plain = wire.copyOf()
        for (i in 2..18) plain[i] = (plain[i].toInt() xor XOR_KEY).toByte()
        val id = plain[1].toInt() and 0xFF
        val payload = plain.copyOfRange(2, 18)
        val want = checksum(plain)
        val got = plain[18].toInt() and 0xFF
        return Frame(id, payload, want == got)
    }

    /** Build a 20-byte wire frame from an id and up to 16 plaintext data bytes. */
    fun encode(id: Int, data16: ByteArray): ByteArray {
        require(data16.size <= 16) { "data must be <= 16 bytes" }
        val plain = ByteArray(FRAME_LEN)
        plain[0] = START.toByte()
        plain[1] = id.toByte()
        for (i in data16.indices) plain[2 + i] = data16[i]
        plain[18] = checksum(plain).toByte()
        plain[19] = END.toByte()
        val wire = plain.copyOf()
        for (i in 2..18) wire[i] = (wire[i].toInt() xor XOR_KEY).toByte()
        return wire
    }

    /** Checksum over the plaintext start+id+16 data bytes (indices 0..17). */
    fun checksum(plain: ByteArray): Int {
        var s = 0
        for (i in 0..17) s += plain[i].toInt() and 0xFF
        return (0xFF - (s and 0xFF)) and 0xFF
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
