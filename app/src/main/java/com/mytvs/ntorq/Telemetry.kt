package com.mytvs.ntorq

/**
 * Turns decoded frames into human-readable lines.
 *
 * Frame identity (which id is which) is taken from the TVS Connect app's own
 * NtorqFrameIdentifier. Exact field BYTE positions inside each frame are not all
 * confirmed yet, so this milestone shows the decoded data bytes per frame and
 * labels only what is certain. Precise fields (speed value, odometer, fuel) land
 * in the next milestone once each frame class is mapped.
 */
object Telemetry {
    fun label(id: Int): String = when (id) {
        0x10 -> "Speedometer 1 (5A10)"
        0x11 -> "Speedometer 2 / status (5A11)"
        0x12 -> "Speed frame (5A12)"
        0x16 -> "Sensor frame (5A16)"
        0x18 -> "Speedometer 4 (5A18)"
        0x19 -> "Speedometer 3 (5A19)"
        0x29 -> "Wi-Fi AP credentials (5A29)"
        0x37 -> "Calibration (5A37)"
        0x54 -> "ACK / keepalive (5A54)"
        else -> "Frame 5A%02X".format(id)
    }

    /** One compact line for the live view. */
    fun line(f: NtorqCodec.Frame): String {
        val chk = if (f.checksumValid) "" else "  [bad checksum]"
        val extra = when (f.id) {
            0x29 -> {
                val txt = buildString {
                    for (b in f.payload) {
                        val c = b.toInt() and 0xFF
                        if (c in 0x20..0x7E) append(c.toChar())
                    }
                }
                "  ascii=\"$txt\""
            }
            else -> ""
        }
        return "%-26s %s%s%s".format(label(f.id), NtorqCodec.hex(f.payload), extra, chk)
    }
}
