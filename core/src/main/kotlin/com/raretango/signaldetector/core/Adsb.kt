package com.raretango.signaldetector.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * ADS-B (Mode S extended squitter, 1090 MHz) decoder. Aircraft broadcast these
 * openly for air-traffic safety, and receiving them is legal. Tells you WHO is
 * transmitting (ICAO address + flight callsign) and, combined with your GPS,
 * which direction and how far away they are.
 *
 * Input: IQ at exactly 2 MS/s tuned to 1090 MHz.
 */
class AdsbDecoder {
    data class Message(val bytes: ByteArray) {
        val hex get() = bytes.joinToString("") { "%02X".format(it) }
        val df get() = (bytes[0].toInt() and 0xFF) shr 3
        val icao get() = ((bytes[1].toInt() and 0xFF) shl 16) or ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val typeCode get() = (bytes[4].toInt() and 0xFF) shr 3
    }

    private var tail = FloatArray(0)
    var messagesDecoded = 0L
        private set

    /** Demodulate a block; returns CRC-valid DF17/18 messages. */
    fun process(iq: Iq): List<Message> {
        val mag = FloatArray(tail.size + iq.size)
        System.arraycopy(tail, 0, mag, 0, tail.size)
        for (i in 0 until iq.size) mag[tail.size + i] = sqrt(iq.re[i] * iq.re[i] + iq.im[i] * iq.im[i])
        val out = demodulate(mag)
        // Keep enough samples to catch a message that straddles the block edge.
        val keep = minOf(mag.size, 16 + 112 * 2)
        tail = mag.copyOfRange(mag.size - keep, mag.size)
        messagesDecoded += out.size
        return out
    }

    fun demodulate(m: FloatArray): List<Message> {
        val out = ArrayList<Message>()
        val len = 16 + 112 * 2
        var i = 0
        while (i < m.size - len) {
            // Preamble: pulses at 0, 1.0, 3.5, 4.5 us => samples 0, 2, 7, 9 at 2 MS/s.
            val p0 = m[i]; val p2 = m[i + 2]; val p7 = m[i + 7]; val p9 = m[i + 9]
            if (!(p0 > m[i + 1] && m[i + 1] < p2 && p2 > m[i + 3] && m[i + 3] < p0 &&
                    m[i + 4] < p0 && m[i + 5] < p0 && m[i + 6] < p7 && p7 > m[i + 8] && m[i + 8] < p9)) {
                i++; continue
            }
            val high = (p0 + p2 + p7 + p9) / 6f
            if (m[i + 4] >= high || m[i + 5] >= high || m[i + 11] >= high || m[i + 12] >= high ||
                m[i + 13] >= high || m[i + 14] >= high) {
                i++; continue
            }
            val bytes = ByteArray(14)
            var weak = 0
            for (b in 0 until 112) {
                val a = m[i + 16 + 2 * b]; val c = m[i + 17 + 2 * b]
                if (abs(a - c) < high * 0.1f) weak++
                if (a > c) bytes[b / 8] = (bytes[b / 8].toInt() or (0x80 ushr (b % 8))).toByte()
            }
            val df = (bytes[0].toInt() and 0xFF) shr 3
            if ((df == 17 || df == 18) && weak < 20 && crc(bytes, 112) == 0) {
                out += Message(bytes)
                i += len
            } else {
                i++
            }
        }
        return out
    }

    companion object {
        private const val POLY = 0xFFF409

        /** Mode S CRC over [bits] bits including the 24-bit parity; 0 = valid. */
        fun crc(msg: ByteArray, bits: Int): Int {
            var rem = 0
            for (i in 0 until bits) {
                val bit = (msg[i / 8].toInt() shr (7 - i % 8)) and 1
                val top = (rem shr 23) and 1
                rem = ((rem shl 1) or bit) and 0xFFFFFF
                if (top == 1) rem = rem xor POLY
            }
            return rem
        }

        fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        private const val CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

        /** Read [n] bits starting at bit [start] (0 = MSB of byte 0). */
        fun bits(msg: ByteArray, start: Int, n: Int): Long {
            var v = 0L
            for (i in start until start + n) v = (v shl 1) or ((msg[i / 8].toLong() shr (7 - i % 8)) and 1)
            return v
        }

        fun callsign(msg: ByteArray): String =
            (0 until 8).map { CHARS[bits(msg, 40 + it * 6, 6).toInt()] }.joinToString("").replace("#", "").trim()

        /** Barometric altitude in feet for airborne-position messages (TC 9-18). */
        fun altitudeFt(msg: ByteArray): Int? {
            val a = bits(msg, 40, 12).toInt()
            if (a == 0) return null
            val q = (a shr 4) and 1
            if (q == 0) return null // Gillham-coded; rare for ADS-B
            val n = ((a shr 5) shl 4) or (a and 0xF)
            return n * 25 - 1000
        }

        fun cprOdd(msg: ByteArray) = bits(msg, 53, 1) == 1L
        fun cprLat(msg: ByteArray) = bits(msg, 54, 17).toDouble() / 131072.0
        fun cprLon(msg: ByteArray) = bits(msg, 71, 17).toDouble() / 131072.0

        private fun nl(lat: Double): Int {
            if (lat == 0.0) return 59
            if (abs(lat) == 87.0) return 2
            if (abs(lat) > 87.0) return 1
            val nz = 15.0
            val a = 1 - cos(Math.PI / (2 * nz))
            val b = cos(Math.PI / 180.0 * abs(lat)).let { it * it }
            return floor(2 * Math.PI / kotlin.math.acos(1 - a / b)).toInt()
        }

        private fun mod(a: Double, b: Double) = a - b * floor(a / b)

        /** Global CPR decode from an even + odd pair; [oddIsNewer] picks which to report. */
        fun globalPosition(even: ByteArray, odd: ByteArray, oddIsNewer: Boolean): LatLon? {
            val lat0 = cprLat(even); val lon0 = cprLon(even)
            val lat1 = cprLat(odd); val lon1 = cprLon(odd)
            val dLat0 = 360.0 / 60; val dLat1 = 360.0 / 59
            val j = floor(59 * lat0 - 60 * lat1 + 0.5)
            var rLat0 = dLat0 * (mod(j, 60.0) + lat0)
            var rLat1 = dLat1 * (mod(j, 59.0) + lat1)
            if (rLat0 >= 270) rLat0 -= 360
            if (rLat1 >= 270) rLat1 -= 360
            if (nl(rLat0) != nl(rLat1)) return null
            val lat = if (oddIsNewer) rLat1 else rLat0
            val nlv = nl(lat)
            val ni = maxOf(if (oddIsNewer) nlv - 1 else nlv, 1)
            val m = floor(lon0 * (nlv - 1) - lon1 * nlv + 0.5)
            val lonCpr = if (oddIsNewer) lon1 else lon0
            var lon = (360.0 / ni) * (mod(m, ni.toDouble()) + lonCpr)
            if (lon >= 180) lon -= 360
            return LatLon(lat, lon)
        }

        /** Local CPR decode from one message using a reference within ~180 NM (e.g. your GPS). */
        fun localPosition(msg: ByteArray, ref: LatLon): LatLon {
            val odd = cprOdd(msg)
            val dLat = 360.0 / if (odd) 59 else 60
            val latCpr = cprLat(msg); val lonCpr = cprLon(msg)
            val j = floor(ref.lat / dLat) + floor(mod(ref.lat, dLat) / dLat - latCpr + 0.5)
            val lat = dLat * (j + latCpr)
            val nlv = nl(lat) - if (odd) 1 else 0
            val dLon = 360.0 / maxOf(nlv, 1)
            val m = floor(ref.lon / dLon) + floor(mod(ref.lon, dLon) / dLon - lonCpr + 0.5)
            return LatLon(lat, dLon * (m + lonCpr))
        }

        /** Ground speed (knots) and track (deg) from TC 19 subtype 1/2. */
        fun velocity(msg: ByteArray): Pair<Double, Double>? {
            val st = bits(msg, 37, 3).toInt()
            if (st != 1 && st != 2) return null
            val dEw = bits(msg, 45, 1); val vEw = bits(msg, 46, 10) - 1
            val dNs = bits(msg, 56, 1); val vNs = bits(msg, 57, 10) - 1
            if (vEw < 0 || vNs < 0) return null
            val mult = if (st == 2) 4 else 1
            val vx = (if (dEw == 1L) -vEw else vEw).toDouble() * mult
            val vy = (if (dNs == 1L) -vNs else vNs).toDouble() * mult
            val speed = sqrt(vx * vx + vy * vy)
            val track = Geo.norm(Math.toDegrees(atan2(vx, vy)))
            return speed to track
        }
    }
}

data class Aircraft(
    val icao: Int,
    var callsign: String? = null,
    var altitudeFt: Int? = null,
    var position: LatLon? = null,
    var speedKt: Double? = null,
    var trackDeg: Double? = null,
    var lastSeenMs: Long = 0,
    var messages: Int = 0,
) {
    val icaoHex get() = "%06X".format(icao)
    internal var evenMsg: ByteArray? = null
    internal var evenMs = 0L
    internal var oddMsg: ByteArray? = null
    internal var oddMs = 0L
}

/** Tracks aircraft across messages. */
class AircraftTracker {
    private val map = LinkedHashMap<Int, Aircraft>()

    @Synchronized
    fun update(msg: AdsbDecoder.Message, nowMs: Long, receiver: LatLon?) {
        val a = map.getOrPut(msg.icao) { Aircraft(msg.icao) }
        a.lastSeenMs = nowMs; a.messages++
        val b = msg.bytes
        when (val tc = msg.typeCode) {
            in 1..4 -> a.callsign = AdsbDecoder.callsign(b)
            in 9..18 -> {
                a.altitudeFt = AdsbDecoder.altitudeFt(b) ?: a.altitudeFt
                if (AdsbDecoder.cprOdd(b)) { a.oddMsg = b; a.oddMs = nowMs } else { a.evenMsg = b; a.evenMs = nowMs }
                val e = a.evenMsg; val o = a.oddMsg
                a.position = when {
                    e != null && o != null && abs(a.evenMs - a.oddMs) < 10_000 ->
                        AdsbDecoder.globalPosition(e, o, a.oddMs >= a.evenMs) ?: a.position
                    receiver != null -> AdsbDecoder.localPosition(b, receiver)
                    else -> a.position
                }
            }
            19 -> AdsbDecoder.velocity(b)?.let { (s, t) -> a.speedKt = s; a.trackDeg = t }
            else -> {}
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long, maxAgeMs: Long = 60_000): List<Aircraft> {
        map.values.removeAll { nowMs - it.lastSeenMs > maxAgeMs }
        return map.values.map { it.copy() }.sortedByDescending { it.lastSeenMs }
    }
}
