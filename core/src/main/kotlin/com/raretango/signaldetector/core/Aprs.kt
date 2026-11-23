package com.raretango.signaldetector.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * APRS / AX.25 packet decoder (1200 baud Bell 202 AFSK, e.g. 144.390 MHz in
 * North America, 144.800 MHz in Europe). Amateur radio is open by law, and
 * every packet carries the sender's callsign - so this tells you exactly WHO
 * is transmitting and usually WHERE they are.
 *
 * Feed it NFM-demodulated audio at 48 kHz.
 */
class AfskDemodulator(private val sampleRate: Int = AUDIO_RATE, private val onFrame: (ByteArray) -> Unit) {
    private val spb = sampleRate / 1200.0 // samples per bit
    private val win = spb.toInt()
    private val markRe = FloatArray(win); private val markIm = FloatArray(win)
    private val spaceRe = FloatArray(win); private val spaceIm = FloatArray(win)
    private var wi = 0
    private var mr = 0f; private var mi = 0f; private var sr = 0f; private var si = 0f
    private var n = 0L
    private var lastSym = false
    private var clock = 0.0
    private var lastBitSym = false
    private val hdlc = HdlcDeframer(onFrame)

    fun process(audio: FloatArray) {
        for (x in audio) {
            val t = 2 * PI * n / sampleRate
            n++
            val m1 = (x * cos(1200 * t)).toFloat(); val m2 = (x * sin(1200 * t)).toFloat()
            val s1 = (x * cos(2200 * t)).toFloat(); val s2 = (x * sin(2200 * t)).toFloat()
            // Sliding one-bit correlators.
            mr += m1 - markRe[wi]; mi += m2 - markIm[wi]; sr += s1 - spaceRe[wi]; si += s2 - spaceIm[wi]
            markRe[wi] = m1; markIm[wi] = m2; spaceRe[wi] = s1; spaceIm[wi] = s2
            wi = (wi + 1) % win
            val sym = (mr * mr + mi * mi) > (sr * sr + si * si) // true = mark
            // Bit clock: a DPLL nudged towards symbol transitions.
            if (sym != lastSym) {
                clock += if (clock < 0.5) -clock * 0.4 else (1 - clock) * 0.4
                lastSym = sym
            }
            val prev = clock
            clock += 1.0 / spb
            if (prev < 0.5 && clock >= 0.5) {
                // NRZI: no tone change = 1, change = 0
                hdlc.bit(sym == lastBitSym)
                lastBitSym = sym
            }
            if (clock >= 1.0) clock -= 1.0
        }
    }
}

/** HDLC flag detection, bit de-stuffing and FCS check. Emits frames without FCS. */
class HdlcDeframer(private val onFrame: (ByteArray) -> Unit) {
    private var ones = 0
    private var cur = 0
    private var nbits = 0
    private val bytes = java.io.ByteArrayOutputStream()
    private var inFrame = false

    fun bit(b: Boolean) {
        if (b) {
            ones++
            if (ones > 6) { inFrame = false; reset(); return } // abort
            push(true)
            return
        }
        if (ones == 6) { // 01111110 flag
            if (inFrame && nbits == 7) finish() // flag consumed 7 bits of "data"; drop them
            inFrame = true
            reset()
            ones = 0
            return
        }
        if (ones == 5) { ones = 0; return } // stuffed zero
        ones = 0
        push(false)
    }

    private fun push(b: Boolean) {
        if (!inFrame) return
        cur = cur or ((if (b) 1 else 0) shl nbits) // LSB first
        nbits++
        if (nbits == 8) {
            bytes.write(cur); cur = 0; nbits = 0
            if (bytes.size() > 400) { inFrame = false; reset() }
        }
    }

    private fun finish() {
        val f = bytes.toByteArray()
        if (f.size >= 18) {
            val body = f.copyOf(f.size - 2)
            val fcs = (f[f.size - 2].toInt() and 0xFF) or ((f[f.size - 1].toInt() and 0xFF) shl 8)
            if (Ax25.fcs(body) == fcs) onFrame(body)
        }
    }

    private fun reset() { bytes.reset(); cur = 0; nbits = 0 }
}

data class AprsPacket(
    val source: String,
    val destination: String,
    val path: List<String>,
    val info: String,
    val position: LatLon?,
    val symbol: String?,
    val comment: String,
)

object Ax25 {
    /** CRC-16/X.25 as used by AX.25. */
    fun fcs(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }

    private fun addr(f: ByteArray, off: Int): String {
        val call = (0 until 6).map { ((f[off + it].toInt() and 0xFF) shr 1).toChar() }.joinToString("").trim()
        val ssid = ((f[off + 6].toInt() and 0xFF) shr 1) and 0x0F
        return if (ssid == 0) call else "$call-$ssid"
    }

    fun decode(frame: ByteArray): AprsPacket? {
        if (frame.size < 16) return null
        val dest = addr(frame, 0)
        val src = addr(frame, 7)
        val path = ArrayList<String>()
        var off = 14
        var last = (frame[13].toInt() and 1) == 1
        while (!last && off + 7 <= frame.size) {
            path += addr(frame, off)
            last = (frame[off + 6].toInt() and 1) == 1
            off += 7
        }
        if (off + 2 > frame.size) return null
        off += 2 // control + PID
        val info = String(frame, off, frame.size - off, Charsets.ISO_8859_1)
        val (pos, sym, comment) = Aprs.parsePosition(info)
        return AprsPacket(src, dest, path, info, pos, sym, comment)
    }

    /** Build a UI frame (for tests / simulation). */
    fun encode(src: String, dest: String, path: List<String>, info: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val all = listOf(dest, src) + path
        all.forEachIndexed { i, a ->
            val (call, ssid) = a.split('-').let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
            for (c in call.padEnd(6).take(6)) out.write(c.code shl 1)
            out.write(0x60 or (ssid shl 1) or if (i == all.size - 1) 1 else 0)
        }
        out.write(0x03); out.write(0xF0)
        out.write(info.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }
}

object Aprs {
    /** Uncompressed APRS position: !DDMM.mmN/DDDMM.mmW$comment (also =, / and @ with timestamp). */
    fun parsePosition(info: String): Triple<LatLon?, String?, String> {
        if (info.isEmpty()) return Triple(null, null, "")
        val body = when (info[0]) {
            '!', '=' -> info.substring(1)
            '/', '@' -> if (info.length > 8) info.substring(8) else return Triple(null, null, info)
            else -> return Triple(null, null, info)
        }
        val m = Regex("""^(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)$""", RegexOption.DOT_MATCHES_ALL)
            .find(body) ?: return Triple(null, null, body)
        val g = m.groupValues
        var lat = g[1].toDouble() + g[2].toDouble() / 60
        if (g[3] == "S") lat = -lat
        var lon = g[5].toDouble() + g[6].toDouble() / 60
        if (g[7] == "W") lon = -lon
        return Triple(LatLon(lat, lon), g[4] + g[8], g[9].trim())
    }
}

/** Audio-rate AFSK modulator used for tests and the simulator. */
object AfskModulator {
    fun modulate(frame: ByteArray, sampleRate: Int = AUDIO_RATE, preambleFlags: Int = 30): FloatArray {
        val fcs = Ax25.fcs(frame)
        val full = frame + byteArrayOf((fcs and 0xFF).toByte(), (fcs shr 8).toByte())
        val bits = ArrayList<Boolean>()
        fun flag() { for (i in 0 until 8) bits += ((0x7E shr i) and 1) == 1 }
        repeat(preambleFlags) { flag() }
        var ones = 0
        for (b in full) for (i in 0 until 8) {
            val bit = ((b.toInt() shr i) and 1) == 1
            bits += bit
            if (bit) { ones++; if (ones == 5) { bits += false; ones = 0 } } else ones = 0
        }
        repeat(3) { flag() }
        val spb = sampleRate / 1200.0
        val out = FloatArray((bits.size * spb).toInt() + 1)
        var tone = true // mark
        var phase = 0.0
        var idx = 0
        for ((k, bit) in bits.withIndex()) {
            if (!bit) tone = !tone // NRZI
            val end = ((k + 1) * spb).toInt()
            while (idx < end && idx < out.size) {
                phase += 2 * PI * (if (tone) 1200 else 2200) / sampleRate
                out[idx++] = (0.5 * sin(phase)).toFloat()
            }
        }
        return out
    }
}
