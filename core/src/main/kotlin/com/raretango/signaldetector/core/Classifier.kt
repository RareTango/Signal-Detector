package com.raretango.signaldetector.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Works out what KIND of signal is on the tuned channel, and pulls out any
 * code it carries:
 *
 *  - Morse code: a carrier switched on and off (CW), or a keyed tone inside FM/AM
 *    audio (MCW, used for repeater and beacon IDs). Decoded to text.
 *  - On/off-keyed (OOK) data: short pulse bursts from remotes, doorbells, tyre and
 *    weather sensors. Shown as raw bits.
 *  - Audio-tone data (AFSK): two alternating tones (APRS/packet, RTTY, old modems).
 *  - Direct FSK data: a square-wave bit stream in the FM audio (telemetry,
 *    pagers, AIS). Shown with its baud rate and raw bits.
 *  - Digital voice: 4-level FSK (DMR, P25, NXDN...). Only the type is reported.
 *    No bits are shown and nothing is decoded.
 *  - Steady tone, unmodulated carrier, voice/analog audio, or nothing.
 *
 * Feed it the channel envelope and the demodulated audio, then call [classify]
 * about once a second.
 */
enum class SignalKind(val label: String) {
    NONE("No signal"),
    CARRIER("Unmodulated carrier"),
    TONE("Steady tone"),
    MORSE("Morse code"),
    OOK_DATA("On/off-keyed data (binary)"),
    AFSK_DATA("Audio-tone data (AFSK)"),
    FSK_DATA("Digital data (FSK, binary)"),
    DIGITAL_VOICE("Digital voice / data (4-level FSK)"),
    VOICE("Voice / analog audio"),
}

data class Classification(
    val kind: SignalKind,
    /** 0..1 */
    val confidence: Float,
    val summary: String,
    val details: List<String> = emptyList(),
    /** Decoded text (Morse). */
    val text: String? = null,
    /** Raw recovered bits, e.g. "10110010 01110001". */
    val bits: String? = null,
    val baud: Double? = null,
    val wpm: Double? = null,
) {
    val hex: String? get() = bits?.let { Bits.toHex(it.replace(" ", "")) }
}

/** Fixed-size float ring buffer. */
class FloatRing(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var pos = 0
    var size = 0
        private set

    fun add(v: Float) {
        data[pos] = v; pos = (pos + 1) % capacity; if (size < capacity) size++
    }

    fun addAll(a: FloatArray) { for (v in a) add(v) }

    fun clear() { pos = 0; size = 0 }

    /** Contents in time order (oldest first). */
    fun toArray(): FloatArray {
        val out = FloatArray(size)
        val start = (pos - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }
}

/** A run of the channel being on (keyed) or off. */
data class Segment(val on: Boolean, val ms: Double)

object Bits {
    fun group(bits: String, n: Int = 8) = bits.chunked(n).joinToString(" ")
    fun toHex(bits: String): String = bits.chunked(4).filter { it.length == 4 }
        .joinToString("") { Integer.toHexString(it.toInt(2)).uppercase() }.chunked(2).joinToString(" ")
}

// --------------------------------------------------------------- Morse ---

object MorseCode {
    val TABLE: Map<String, Char> = mapOf(
        ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E', "..-." to 'F', "--." to 'G',
        "...." to 'H', ".." to 'I', ".---" to 'J', "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N',
        "---" to 'O', ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T', "..-" to 'U',
        "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y', "--.." to 'Z',
        "-----" to '0', ".----" to '1', "..---" to '2', "...--" to '3', "....-" to '4', "....." to '5',
        "-...." to '6', "--..." to '7', "---.." to '8', "----." to '9',
        ".-.-.-" to '.', "--..--" to ',', "..--.." to '?', "-..-." to '/', "-...-" to '=', ".-.-." to '+',
        "-....-" to '-', "-.--." to '(', "-.--.-" to ')', ".--.-." to '@', "---..." to ':',
    )
    private val REVERSE = TABLE.entries.associate { (k, v) -> v to k }

    /** Text -> keying in dit units (true = key down). Used by the simulator and tests. */
    fun encode(text: String): BooleanArray {
        val units = ArrayList<Boolean>()
        val words = text.uppercase().trim().split(Regex("\\s+"))
        for ((wi, w) in words.withIndex()) {
            for ((ci, c) in w.withIndex()) {
                val code = REVERSE[c] ?: continue
                for ((si, s) in code.withIndex()) {
                    repeat(if (s == '.') 1 else 3) { units += true }
                    if (si < code.length - 1) units += false
                }
                if (ci < w.length - 1) repeat(3) { units += false }
            }
            if (wi < words.size - 1) repeat(7) { units += false }
        }
        return units.toBooleanArray()
    }

    data class Fit(val text: String, val ditMs: Double, val confidence: Float) {
        val wpm get() = 1200.0 / ditMs
    }

    /** Decode on/off segments as Morse. Returns null if the timing doesn't look like Morse. */
    fun decode(segments: List<Segment>): Fit? {
        val ons = segments.filter { it.on }.map { it.ms }
        if (ons.size < 5) return null
        val sorted = ons.sorted()
        // Two-cluster split of key-down lengths (dits vs dahs), in the log domain.
        var lo = sorted[sorted.size / 5]
        var hi = sorted[sorted.size * 4 / 5]
        repeat(12) {
            val mid = sqrt(lo * hi)
            val a = ons.filter { it < mid }; val b = ons.filter { it >= mid }
            if (a.isNotEmpty()) lo = a.average()
            if (b.isNotEmpty()) hi = b.average()
        }
        val offs = segments.filter { !it.on }.map { it.ms }.sorted()
        val dit = if (hi / lo >= 1.8) lo else min(lo, offs.getOrElse(offs.size / 4) { lo })
        if (dit < 15 || dit > 300) return null
        if (hi / lo >= 1.8 && hi / lo > 4.8) return null

        var conform = 0
        for (d in ons) if (abs(d / dit - 1) < 0.5 || abs(d / (3 * dit) - 1) < 0.4) conform++
        val conformity = conform.toFloat() / ons.size
        if (conformity < 0.75f) return null

        val sb = StringBuilder()
        val sym = StringBuilder()
        var known = 0; var total = 0
        fun flush() {
            if (sym.isEmpty()) return
            total++
            val c = TABLE[sym.toString()]
            if (c != null) known++
            sb.append(c ?: '?')
            sym.clear()
        }
        for (s in segments) {
            if (s.on) sym.append(if (s.ms < 2 * dit) '.' else '-')
            else when {
                s.ms < 2 * dit -> Unit
                s.ms < 5 * dit -> flush()
                else -> { flush(); if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ') }
            }
        }
        flush()
        if (total < 2) return null
        val valid = known.toFloat() / total
        if (valid < 0.7f) return null
        return Fit(sb.toString().trim(), dit, conformity * valid)
    }
}

// ----------------------------------------------------------- classifier ---

class SignalClassifier(
    /** Sample rate of the envelope passed to [feed]. */
    val envRate: Double,
    val audioRate: Int = AUDIO_RATE,
    historySeconds: Double = 15.0,
) {
    private val env = FloatRing((envRate * historySeconds).toInt())
    private val audio = FloatRing((audioRate * historySeconds).toInt())

    /** Optional diagnostics sink. */
    var trace: ((String) -> Unit)? = null

    fun reset() { env.clear(); audio.clear() }

    /** [envelope]: channel magnitude (linear) at [envRate]. [audioBlock]: demodulated audio (unsquelched). */
    fun feed(envelope: FloatArray, audioBlock: FloatArray) {
        for (v in envelope) env.add((20 * log10(v + 1e-9)).toFloat())
        audio.addAll(audioBlock)
    }

    /** [snrDb]: tuned-channel power above the noise floor from the spectrum. */
    fun classify(snrDb: Float, freqHz: Double = 0.0): Classification {
        val e = env.toArray()
        if (e.size < envRate * 0.5) return Classification(SignalKind.NONE, 0f, "Listening…")

        keyedEnvelope(e, freqHz)?.let { return it }

        if (snrDb < 6) return Classification(SignalKind.NONE, 0.8f, "Nothing on this channel (just noise).")

        val a = audio.toArray()
        if (a.size < audioRate) return Classification(SignalKind.NONE, 0f, "Listening…")
        val rms = sqrt(a.fold(0.0) { s, v -> s + v * v } / a.size)
        if (rms < 1e-3) return Classification(SignalKind.CARRIER, 0.7f, "Steady carrier with no audio (unmodulated or squelched).")

        audioAnalysis(a, freqHz)?.let { return it }
        directFsk(a)?.let { return it }

        return Classification(SignalKind.VOICE, 0.5f, "Analog audio (voice or music). No code detected.")
    }

    // -- 1. keyed carrier: CW Morse or OOK pulses ---------------------------

    private fun keyedEnvelope(e: FloatArray, freqHz: Double): Classification? {
        val s = e.copyOf(); s.sort()
        val p10 = s[s.size / 10]; val p50 = s[s.size / 2]; val p99 = s[s.size * 99 / 100]
        if (p99 - p10 < 12 && p99 - p50 < 12) return null
        // Half the key-down amplitude (-6 dB) keeps pulse edges symmetric.
        val mid = max(p99 - 6f, p10 + 6f)
        val hiT = mid + 1f
        val loT = mid - 1f
        val msPerSample = 1000.0 / envRate
        val segs = ArrayList<Segment>()
        var on = e[0] > hiT
        var run = 0
        val onLevels = ArrayList<Float>() // median level of each key-down
        val cur = ArrayList<Float>()
        for (v in e) {
            val now = if (on) v > loT else v > hiT
            if (now != on) {
                segs += Segment(on, run * msPerSample); run = 0
                if (on && cur.isNotEmpty()) { cur.sort(); onLevels += cur[cur.size / 2]; cur.clear() }
                on = now
            }
            if (now) cur += v
            run++
        }
        if (segs.size < 2) return null
        segs.removeAt(0) // first run started before the history window
        val ons = segs.filter { it.on }
        if (ons.size < 4 || onLevels.size < 3) return null
        // Keying flattens the on-level; AM voice wanders constantly.
        val ol = onLevels.sorted()
        val onSpread = ol[ol.size * 9 / 10] - ol[ol.size / 10]

        MorseCode.decode(segs)?.let { fit ->
            return Classification(
                SignalKind.MORSE, fit.confidence,
                "Morse code at %.0f WPM (carrier keyed on/off).".format(fit.wpm),
                listOf("Dit length %.0f ms".format(fit.ditMs), "Usual on HF ham bands, beacons and navigation aids (NDB)."),
                text = fit.text, wpm = fit.wpm,
            )
        }

        val medianOn = ons.map { it.ms }.sorted()[ons.size / 2]
        trace?.invoke("keyed: ons=${ons.size} medianOn=$medianOn onSpread=$onSpread")
        if (medianOn > 25 || onSpread > 6) return null
        // Data bursts: durations are whole multiples of a basic time unit.
        val inBurst = segs.filter { it.on || it.ms < medianOn * 12 }
        val u0 = inBurst.map { it.ms }.sorted()[inBurst.size / 7].coerceAtLeast(msPerSample * 2)
        val unit = inBurst.filter { round(it.ms / u0) == 1.0 }.map { it.ms }.average().takeIf { !it.isNaN() } ?: u0
        val err = inBurst.map { val r = it.ms / unit; abs(r - round(r)) }.average()
        trace?.invoke("ook: unit=$unit err=$err")
        if (err > 0.22) return null

        // Bits of the most recent complete burst.
        val bursts = ArrayList<MutableList<Segment>>()
        var burst = ArrayList<Segment>()
        for (sg in segs) {
            if (!sg.on && sg.ms > unit * 12) { if (burst.isNotEmpty()) bursts += burst; burst = ArrayList() }
            else burst += sg
        }
        if (burst.isNotEmpty()) bursts += burst
        val last = bursts.lastOrNull { b -> b.count { it.on } >= 4 } ?: return null
        val bits = StringBuilder()
        for (sg in last) repeat(max(1, round(sg.ms / unit).toInt())) { bits.append(if (sg.on) '1' else '0') }
        val baud = 1000.0 / unit
        val ism = listOf(315e6, 433.92e6, 868e6, 915e6).any { abs(it - freqHz) < 3e6 }
        return Classification(
            SignalKind.OOK_DATA, (1 - err * 3).toFloat().coerceIn(0.3f, 0.95f),
            "On/off-keyed data bursts, ~%.0f baud, %d bursts in view.".format(baud, bursts.size),
            listOfNotNull(
                "Basic pulse %.2f ms".format(unit),
                if (ism) "Typical of key fobs, doorbells, weather and tyre-pressure sensors." else "Pulsed data transmitter.",
                "Raw bits show pulse timing; the device's own encoding (PWM/Manchester) is not decoded.",
            ),
            bits = Bits.group(bits.take(256).toString()), baud = baud,
        )
    }

    // -- 2. tones in the audio: MCW Morse, steady tone, AFSK -----------------

    private fun audioAnalysis(a: FloatArray, freqHz: Double): Classification? {
        val fftN = 4096
        val fft = Fft(fftN)
        val win = FloatArray(fftN) { (0.5 - 0.5 * cos(2 * Math.PI * it / (fftN - 1))).toFloat() }
        val spec = DoubleArray(fftN / 2)
        val re = FloatArray(fftN); val im = FloatArray(fftN)
        val binHz = audioRate.toDouble() / fftN
        val lo = (280 / binHz).toInt(); val hi = (3500 / binHz).toInt()
        // Only average frames where the signal is tonal, so bursts (packets,
        // IDs) aren't drowned by the noise between them.
        var frames = 0
        var off = max(0, a.size - audioRate * 8)
        val frameSpec = DoubleArray(fftN / 2)
        while (off + fftN <= a.size) {
            for (i in 0 until fftN) { re[i] = a[off + i] * win[i]; im[i] = 0f }
            fft.transform(re, im)
            for (i in frameSpec.indices) frameSpec[i] = (re[i] * re[i] + im[i] * im[i]).toDouble()
            val tot = (lo..hi).sumOf { frameSpec[it] }
            val top = (lo..hi).map { frameSpec[it] }.sortedDescending().take(12).sum()
            if (tot > 0 && top / tot > 0.35) {
                for (i in spec.indices) spec[i] += frameSpec[i]
                frames++
            }
            off += fftN
        }
        trace?.invoke("audio: tonalFrames=$frames")
        if (frames < 2) return null
        val total = (lo..hi).sumOf { spec[it] }
        if (total <= 0) return null
        fun power(c: Int) = (max(lo, c - 2)..min(hi, c + 2)).sumOf { spec[it] }
        val b1 = (lo..hi).maxBy { spec[it] }
        val p1 = power(b1)
        val b2 = (lo..hi).filter { abs(it - b1) > (120 / binHz) }.maxBy { spec[it] }
        val p2 = power(b2)
        val f1 = b1 * binHz; val f2 = b2 * binHz
        // Real tones are sharp peaks, not the top of a broad noise hump.
        fun peaky(b: Int): Boolean {
            val around = ((b - 40)..(b + 40)).filter { it in lo..hi && abs(it - b) > 6 }.map { spec[it] }.sorted()
            return around.isEmpty() || spec[b] > 20 * around[around.size / 2]
        }
        trace?.invoke("audio: f1=$f1 p1=${p1 / total} f2=$f2 p2=${p2 / total} peaky1=${peaky(b1)} peaky2=${peaky(b2)}")
        val tonal = peaky(b1)

        if (tonal && p1 / total > 0.45 && p2 / total < 0.15) {
            // A single tone. Is it keyed (Morse) or steady?
            val frame = audioRate / 200 // 5 ms
            val levels = FloatArray(a.size / frame) { i -> goertzelDb(a, i * frame, frame, f1) }
            val sorted = levels.sorted()
            val range = sorted[sorted.size * 95 / 100] - sorted[sorted.size / 20]
            if (range > 12) {
                val segs = segmentsFrom(levels, sorted[sorted.size / 20] + range * 0.5f, 5.0)
                MorseCode.decode(segs)?.let { fit ->
                    return Classification(
                        SignalKind.MORSE, fit.confidence,
                        "Morse code at %.0f WPM, keyed %.0f Hz tone.".format(fit.wpm, f1),
                        listOf("Dit length %.0f ms".format(fit.ditMs), "Tone-keyed Morse (MCW): repeater IDs, beacons."),
                        text = fit.text, wpm = fit.wpm,
                    )
                }
                return Classification(SignalKind.TONE, 0.5f, "Pulsed %.0f Hz tone (keyed, but not Morse timing).".format(f1))
            }
            val what = when {
                abs(f1 - 1000) < 15 -> "1 kHz test tone"
                abs(f1 - 1050) < 15 -> "1050 Hz NOAA weather alert tone"
                abs(f1 - 853) < 10 || abs(f1 - 960) < 10 -> "Emergency Alert System attention tone"
                else -> "steady %.0f Hz tone".format(f1)
            }
            return Classification(SignalKind.TONE, 0.8f, "Steady tone: $what.")
        }

        fun band(fHz: Double, w: Double = 150.0) = ((max(lo.toDouble(), (fHz - w) / binHz).toInt())..(min(hi.toDouble(), (fHz + w) / binHz).toInt())).sumOf { spec[it] }
        val bell202 = band(1200.0) / total > 0.12 && band(2200.0) / total > 0.12 && (band(1200.0) + band(2200.0)) / total > 0.6
        val wp1 = band(f1, 90.0); val wp2 = band(f2, 90.0)
        if (bell202 || ((wp1 + wp2) / total > 0.6 && wp2 / total > 0.12 && peaky(b2))) {
            // Two tones. AFSK alternates between them; a fixed chord (DTMF) does not.
            val frame = audioRate / 100 // 10 ms
            val n = a.size / frame
            val ratios = FloatArray(n) { i -> goertzelDb(a, i * frame, frame, f1) - goertzelDb(a, i * frame, frame, f2) }
            val mean = ratios.average()
            val std = sqrt(ratios.fold(0.0) { s, r -> s + (r - mean) * (r - mean) } / n)
            val (lowF, highF) = if (bell202) 1200.0 to 2200.0 else if (f1 < f2) f1 to f2 else f2 to f1
            val shift = highF - lowF
            if (bell202 || std > 3.0 || shift in 150.0..250.0) {
                val (name, baud) = when {
                    near(lowF, 1200.0, 60.0) && near(highF, 2200.0, 60.0) -> "Bell 202 AFSK: APRS / AX.25 packet radio" to 1200.0
                    shift in 150.0..190.0 -> "RTTY radioteletype (170 Hz shift)" to 45.45
                    near(lowF, 1070.0, 40.0) && near(highF, 1270.0, 40.0) -> "Bell 103 modem (300 baud)" to 300.0
                    near(lowF, 2025.0, 40.0) && near(highF, 2225.0, 40.0) -> "Bell 103 modem (300 baud)" to 300.0
                    near(lowF, 1600.0, 40.0) && near(highF, 1800.0, 40.0) -> "HF packet (300 baud)" to 300.0
                    else -> "Two-tone FSK, %.0f Hz shift".format(shift) to null
                }
                return Classification(
                    SignalKind.AFSK_DATA, if (baud != null) 0.85f else 0.6f,
                    "$name.",
                    listOfNotNull(
                        "Tones %.0f / %.0f Hz".format(lowF, highF),
                        baud?.let { "%.2f".format(it).trimEnd('0').trimEnd('.') + " baud" },
                        if (near(freqHz, 144.39e6, 5e3) || near(freqHz, 144.8e6, 5e3)) "Packets are decoded on the Who tab." else null,
                    ),
                    baud = baud,
                )
            }
            return Classification(SignalKind.TONE, 0.6f, "Two steady tones %.0f + %.0f Hz (e.g. DTMF / paging tones).".format(lowF, highF))
        }
        return null
    }

    // -- 3. square-wave bit stream in the FM audio ---------------------------

    private fun directFsk(raw: FloatArray): Classification? {
        val mean = raw.average().toFloat()
        val x = FloatArray(raw.size) { raw[it] - mean }
        // light low-pass
        val y = FloatArray(x.size)
        var acc = 0f
        for (i in x.indices) { acc += x[i]; if (i >= 4) acc -= x[i - 4]; y[i] = acc / 4 }
        val amp = y.map { abs(it) }.sorted()[y.size * 95 / 100]
        if (amp < 1e-3) return null
        val hyst = amp * 0.15f
        val crossings = ArrayList<Int>()
        var state = y[0] > 0
        for (i in y.indices) {
            if (state && y[i] < -hyst) { state = false; crossings += i }
            else if (!state && y[i] > hyst) { state = true; crossings += i }
        }
        if (crossings.size < 40) { trace?.invoke("fsk: only ${crossings.size} crossings"); return null }
        val intervals = crossings.zipWithNext { p, q -> (q - p).toDouble() }

        data class Fit(val baud: Double, val err: Double, val variety: Boolean)
        val fits = listOf(300.0, 512.0, 600.0, 1200.0, 2400.0, 3600.0, 4800.0, 9600.0).map { baud ->
            val spb = audioRate / baud
            val rs = intervals.map { it / spb }
            val e = rs.map { r -> if (r < 0.6) 0.5 else abs(r - round(r)) }.average()
            val ones = rs.count { round(it) == 1.0 }.toFloat() / rs.size
            val more = rs.count { round(it) in 2.0..8.0 }.toFloat() / rs.size
            Fit(baud, e, ones > 0.1f && more > 0.1f)
        }
        trace?.invoke("fsk: crossings=${crossings.size} " + fits.joinToString { "%.0f:%.3f:%s".format(it.baud, it.err, it.variety) })
        val best = fits.firstOrNull { f ->
            f.variety && f.err < 0.16 && fits.filter { it !== f && it.baud > f.baud }.all { it.err >= f.err * 0.8 } &&
                fits.filter { it.baud < f.baud }.all { it.err > f.err * 1.6 }
        } ?: return null

        // 2-level or 4-level? Look at the amplitude distribution.
        val mid = y.count { val r = abs(it) / amp; r in 0.15f..0.55f }.toFloat() / y.size
        val fourLevel = mid > 0.22f
        if (fourLevel) {
            val what = when (best.baud) {
                4800.0 -> "DMR, P25 Phase 1, NXDN-9600 or dPMR (4800 symbols/s)"
                2400.0 -> "NXDN-4800 or similar (2400 symbols/s)"
                else -> "%.0f symbols/s".format(best.baud)
            }
            return Classification(
                SignalKind.DIGITAL_VOICE, (1 - best.err * 4).toFloat().coerceIn(0.3f, 0.9f),
                "Digital voice/data: 4-level FSK, $what.",
                listOf(
                    "This app identifies digital voice but does not decode it, and never decrypts.",
                    "It sounds like a harsh buzz on an analog receiver.",
                ),
                baud = best.baud,
            )
        }
        // Recover bits by sampling mid-symbol from the first crossing.
        val spb = audioRate / best.baud
        val bits = StringBuilder()
        var t = crossings[0] + spb / 2
        while (t < y.size && bits.length < 256) { bits.append(if (y[t.toInt()] > 0) '1' else '0'); t += spb }
        val what = when (best.baud) {
            9600.0 -> "9600 baud: AIS ship tracking (on 161.975/162.025) or 9600 packet"
            1200.0 -> "1200 baud FSK: telemetry / data"
            512.0, 2400.0 -> "%.0f baud: POCSAG-style paging or telemetry".format(best.baud)
            else -> "%.0f baud FSK data".format(best.baud)
        }
        return Classification(
            SignalKind.FSK_DATA, (1 - best.err * 4).toFloat().coerceIn(0.3f, 0.9f),
            "Binary data stream, $what.",
            listOf("Two-level frequency-shift keying; raw bits are shown as received (protocol not decoded)."),
            bits = Bits.group(bits.toString()), baud = best.baud,
        )
    }

    // ------------------------------------------------------------ helpers

    private fun near(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

    private fun goertzelDb(x: FloatArray, start: Int, n: Int, f: Double): Float {
        val w = 2 * Math.PI * f / audioRate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in start until min(x.size, start + n)) {
            val s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0
        }
        val p = s1 * s1 + s2 * s2 - c * s1 * s2
        return (10 * log10(p / n + 1e-12)).toFloat()
    }

    private fun segmentsFrom(levels: FloatArray, threshold: Float, msPerFrame: Double): List<Segment> {
        val segs = ArrayList<Segment>()
        var on = levels[0] > threshold
        var run = 0
        for (v in levels) {
            val now = v > threshold
            if (now != on) { segs += Segment(on, run * msPerFrame); run = 0; on = now }
            run++
        }
        if (segs.isNotEmpty()) segs.removeAt(0)
        return segs
    }
}
