package com.raretango.signaldetector.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R_KM = 6371.0088

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R_KM * kotlin.math.asin(sqrt(h))
    }

    /** Initial great-circle bearing from [a] to [b], degrees 0..360 (true north). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm(Math.toDegrees(atan2(y, x)))
    }

    fun norm(deg: Double): Double = ((deg % 360) + 360) % 360

    /** Smallest signed difference a-b in degrees, -180..180. */
    fun diff(a: Double, b: Double): Double {
        val d = norm(a - b)
        return if (d > 180) d - 360 else d
    }

    fun compassPoint(deg: Double): String {
        val pts = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return pts[((norm(deg) + 11.25) / 22.5).toInt() % 16]
    }
}

/**
 * Direction finding by rotating a directional antenna (Yagi, log-periodic,
 * or your body as a shield for a phone-taped "body-fade" hunt) while the phone
 * compass records which way it points.
 *
 * Feed (heading, signal dB) pairs; [estimate] returns the bearing of the
 * strongest response (directional antennas) or of the deepest null (loops).
 */
class BearingSweep(val binDeg: Int = 5) {
    enum class AntennaType { DIRECTIONAL_PEAK, LOOP_NULL }

    private val bins = binsCount()
    private val max = FloatArray(bins) { Float.NaN }
    private val min = FloatArray(bins) { Float.NaN }
    var samples = 0
        private set

    private fun binsCount() = 360 / binDeg

    fun reset() { max.fill(Float.NaN); min.fill(Float.NaN); samples = 0 }

    fun add(headingDeg: Double, levelDb: Float) {
        val b = (Geo.norm(headingDeg) / binDeg).toInt() % bins
        if (max[b].isNaN() || levelDb > max[b]) max[b] = levelDb
        if (min[b].isNaN() || levelDb < min[b]) min[b] = levelDb
        samples++
    }

    /** Fraction of the circle that has been covered (0..1). */
    fun coverage(): Float = max.count { !it.isNaN() }.toFloat() / bins

    /** Per-bin level for plotting (NaN where not yet measured). */
    fun pattern(): FloatArray = max.copyOf()

    data class Result(val bearingDeg: Double, val spreadDeg: Double, val contrastDb: Float, val coverage: Float)

    fun estimate(type: AntennaType = AntennaType.DIRECTIONAL_PEAK): Result? {
        val src = if (type == AntennaType.DIRECTIONAL_PEAK) max else min
        val measured = src.indices.filter { !src[it].isNaN() }
        if (measured.size < 3) return null
        // Smooth over neighbouring bins so one spike does not win.
        val smooth = FloatArray(bins) { i ->
            var s = 0f; var c = 0
            for (d in -1..1) {
                val v = src[(i + d + bins) % bins]
                if (!v.isNaN()) { s += v; c++ }
            }
            if (c == 0) Float.NaN else s / c
        }
        val valid = smooth.indices.filter { !smooth[it].isNaN() }
        val best = if (type == AntennaType.DIRECTIONAL_PEAK) valid.maxBy { smooth[it] } else valid.minBy { smooth[it] }
        val worst = if (type == AntennaType.DIRECTIONAL_PEAK) valid.minBy { smooth[it] } else valid.maxBy { smooth[it] }
        val contrast = abs(smooth[best] - smooth[worst])
        // Refine: circular weighted mean of bins within 3 dB of the extreme.
        var sx = 0.0; var sy = 0.0; var w = 0.0
        val within = valid.filter { abs(smooth[it] - smooth[best]) <= 3f && abs(Geo.diff(it * binDeg.toDouble(), best * binDeg.toDouble())) <= 60 }
        for (i in within) {
            val wt = 10.0.pow(-abs(smooth[i] - smooth[best]) / 10.0)
            val a = Math.toRadians(i * binDeg + binDeg / 2.0)
            sx += wt * sin(a); sy += wt * cos(a); w += wt
        }
        val bearing = Geo.norm(Math.toDegrees(atan2(sx / w, sy / w)))
        val spread = max(binDeg.toDouble(), within.size * binDeg.toDouble())
        return Result(bearing, spread, contrast, coverage())
    }
}

/**
 * One reading from a KrakenSDR (5-channel coherent receiver) running the
 * KrakenSDR DoA DSP software, which publishes results at
 * http://<kraken-ip>:8081/DOA_value.html in its "Kraken App" CSV format:
 * epoch, doa_deg, confidence, rssi_db, freq_hz, array_type, latency_ms,
 * station_id, lat, lon, gps_heading, compass_heading, heading_source,
 * 4 reserved fields, then 360 values of the DoA spectrum.
 */
data class KrakenDoa(
    val epochMs: Long,
    val bearingDeg: Double,
    val confidence: Double,
    val rssiDb: Double,
    val freqHz: Double,
    val arrayType: String,
    val stationId: String,
    val position: LatLon?,
    val spectrum: FloatArray,
) {
    companion object {
        fun parse(text: String): KrakenDoa? {
            val f = text.trim().split(',').map { it.trim() }
            if (f.size < 5) return null
            val doa = f[1].toDoubleOrNull() ?: return null
            val lat = f.getOrNull(8)?.toDoubleOrNull()
            val lon = f.getOrNull(9)?.toDoubleOrNull()
            val spec = if (f.size >= 17 + 360) FloatArray(360) { f[17 + it].toFloatOrNull() ?: 0f } else FloatArray(0)
            var freq = f[4].toDoubleOrNull() ?: 0.0
            if (freq in 1.0..10_000.0) freq *= 1e6 // some versions report MHz
            return KrakenDoa(
                epochMs = f[0].toDoubleOrNull()?.toLong() ?: 0L,
                bearingDeg = Geo.norm(doa),
                confidence = f[2].toDoubleOrNull() ?: 0.0,
                rssiDb = f[3].toDoubleOrNull() ?: 0.0,
                freqHz = freq,
                arrayType = f.getOrElse(5) { "" },
                stationId = f.getOrElse(7) { "" },
                position = if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) LatLon(lat, lon) else null,
                spectrum = spec,
            )
        }
    }
}

/** A user-entered (or imported) transmitter with a known location. */
data class KnownStation(
    val freqHz: Double,
    val name: String,
    val position: LatLon?,
    val notes: String = "",
    val toleranceHz: Double = 6e3,
) {
    companion object {
        /**
         * Parse CSV lines: frequency_MHz,name,lat,lon[,notes]. Header lines and
         * blank lines are skipped; lat/lon may be empty.
         */
        fun parseCsv(text: String): List<KnownStation> = text.lineSequence().mapNotNull { line ->
            val f = splitCsv(line)
            val mhz = f.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = f.getOrNull(2)?.toDoubleOrNull()
            val lon = f.getOrNull(3)?.toDoubleOrNull()
            KnownStation(
                freqHz = mhz * 1e6,
                name = f.getOrElse(1) { "" }.ifBlank { "%.4f MHz".format(mhz) },
                position = if (lat != null && lon != null) LatLon(lat, lon) else null,
                notes = f.getOrElse(4) { "" },
            )
        }.toList()

        fun toCsv(list: List<KnownStation>): String = buildString {
            appendLine("frequency_mhz,name,lat,lon,notes")
            for (s in list) {
                appendLine(listOf(
                    "%.6f".format(java.util.Locale.US, s.freqHz / 1e6), quote(s.name),
                    s.position?.lat?.toString() ?: "", s.position?.lon?.toString() ?: "", quote(s.notes),
                ).joinToString(","))
            }
        }

        private fun quote(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

        private fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> q = !q
                    c == ',' && !q -> { out += sb.toString().trim(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString().trim()
            return out
        }
    }
}

/** Candidate source for a signal, ranked by frequency match and bearing agreement. */
data class SourceMatch(
    val station: KnownStation,
    val bearingDeg: Double?,
    val distanceKm: Double?,
    /** |measured - expected| bearing, when both are known. */
    val bearingErrorDeg: Double?,
)

object SourceLocator {
    fun candidates(
        freqHz: Double, stations: List<KnownStation>, me: LatLon?, measuredBearing: Double?,
    ): List<SourceMatch> = stations
        .filter { abs(it.freqHz - freqHz) <= it.toleranceHz }
        .map { s ->
            val brg = if (me != null && s.position != null) Geo.bearingDeg(me, s.position) else null
            val dist = if (me != null && s.position != null) Geo.distanceKm(me, s.position) else null
            val err = if (brg != null && measuredBearing != null) abs(Geo.diff(brg, measuredBearing)) else null
            SourceMatch(s, brg, dist, err)
        }
        .sortedWith(compareBy<SourceMatch> { it.bearingErrorDeg ?: 999.0 }.thenBy { abs(it.station.freqHz - freqHz) })

    /**
     * Intersect two bearings taken from different places (triangulation).
     * Uses a local flat-earth approximation, fine for tens of km.
     */
    fun triangulate(p1: LatLon, b1: Double, p2: LatLon, b2: Double): LatLon? {
        val kmPerDegLat = 111.32
        val kmPerDegLon = 111.32 * cos(Math.toRadians((p1.lat + p2.lat) / 2))
        val x2 = (p2.lon - p1.lon) * kmPerDegLon
        val y2 = (p2.lat - p1.lat) * kmPerDegLat
        val d1x = sin(Math.toRadians(b1)); val d1y = cos(Math.toRadians(b1))
        val d2x = sin(Math.toRadians(b2)); val d2y = cos(Math.toRadians(b2))
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-6) return null // parallel
        val t = (x2 * d2y - y2 * d2x) / den
        if (t < 0) return null // intersection is behind us
        val s = (x2 * d1y - y2 * d1x) / den
        if (s < 0) return null
        return LatLon(p1.lat + t * d1y / kmPerDegLat, p1.lon + t * d1x / kmPerDegLon)
    }
}
