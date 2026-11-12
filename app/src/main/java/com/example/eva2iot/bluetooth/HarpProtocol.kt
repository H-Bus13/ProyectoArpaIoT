package com.example.eva2iot.bluetooth

/** Tramas ASCII con delimitadores y XOR: <FREQ,1,440*HH> seguido de salto de línea. */
object HarpProtocol {
    fun encode(payload: String): String {
        val checksum = payload.fold(0) { acc, char -> acc xor char.code }
        return "<$payload*${checksum.toString(16).uppercase().padStart(2, '0')}>\n"
    }

    fun decode(frame: String): String? {
        val match = Regex("^<([A-Z0-9,]+)\\*([0-9A-Fa-f]{2})>$").matchEntire(frame.trim()) ?: return null
        val payload = match.groupValues[1]
        return payload.takeIf { it.fold(0) { acc, char -> acc xor char.code } == match.groupValues[2].toInt(16) }
    }

    fun frequency(string: Int, hz: Int): String {
        require(string in 1..4 && hz in 100..2000)
        return "FREQ,$string,$hz"
    }
}
