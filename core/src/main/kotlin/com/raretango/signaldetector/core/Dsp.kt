package com.raretango.signaldetector.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val AUDIO_RATE = 48_000

/** Complex samples as two parallel float arrays (fast on Android, no boxing). */
class Iq(val re: FloatArray, val im: FloatArray) {
    constructor(n: Int) : this(FloatArray(n), FloatArray(n))
    val size get() = re.size
}

// ------------------------------------------------------------------ FFT ---

class Fft(val size: Int) {
    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    /** In-place forward FFT. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosT[k]; val s = sinT[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre; im[l] = im[j] - tim
                    re[j] += tre; im[j] += tim
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}

/** Averaged, DC-centred power spectrum in dBFS. */
class SpectrumAnalyzer(val fftSize: Int = 2048) {
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) {
        // Blackman window
        (0.42 - 0.5 * cos(2 * PI * it / (fftSize - 1)) + 0.08 * cos(4 * PI * it / (fftSize - 1))).toFloat()
    }
    private val wsum = window.sum().let { it * it }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    fun compute(iq: Iq, maxAverages: Int = 16): FloatArray {
        val acc = DoubleArray(fftSize)
        val n = min(maxAverages, max(1, iq.size / fftSize))
        for (a in 0 until n) {
            val off = a * fftSize
            for (i in 0 until fftSize) {
                val idx = off + i
                if (idx < iq.size) {
                    re[i] = iq.re[idx] * window[i]; im[i] = iq.im[idx] * window[i]
                } else {
                    re[i] = 0f; im[i] = 0f
                }
            }
            fft.transform(re, im)
            for (i in 0 until fftSize) {
                val j = (i + fftSize / 2) % fftSize // fftshift
                acc[i] += (re[j] * re[j] + im[j] * im[j]).toDouble()
            }
        }
        return FloatArray(fftSize) { (10 * log10(acc[it] / n / wsum + 1e-20)).toFloat() }
    }
}

// --------------------------------------------------------------- peaks ---

data class Peak(
    val freqHz: Double,
    val powerDb: Float,
    val snrDb: Float,
    val bandwidthHz: Double,
)

object PeakFinder {
    fun noiseFloor(psd: FloatArray): Float {
        val s = psd.copyOf(); s.sort()
        return s[s.size / 4]
    }

    /** Signals at least [thresholdDb] above the noise floor in a DC-centred PSD. */
    fun find(
        psd: FloatArray, centerHz: Double, sampleRate: Double,
        thresholdDb: Float = 10f, minSeparationHz: Double = 10e3, maxPeaks: Int = 40,
    ): List<Peak> {
        val n = psd.size
        val binHz = sampleRate / n
        val floor = noiseFloor(psd)
        val smooth = FloatArray(n) { i ->
            val a = psd[max(0, i - 1)]; val b = psd[i]; val c = psd[min(n - 1, i + 1)]
            (a + b + c) / 3
        }
        val edge = max(2, n / 50)
        val above = BooleanArray(n) { smooth[it] > floor + thresholdDb && it >= edge && it < n - edge }
        // Ignore the hardware DC spike in the centre.
        for (i in n / 2 - 1..n / 2 + 1) above[i] = false

        val found = ArrayList<Peak>()
        var i = 0
        while (i < n) {
            if (!above[i]) { i++; continue }
            var j = i
            var p = i
            while (j < n && above[j]) { if (smooth[j] > smooth[p]) p = j; j++ }
            val pk = smooth[p]
            var lo = p; while (lo > 0 && smooth[lo] > pk - 6) lo--
            var hi = p; while (hi < n - 1 && smooth[hi] > pk - 6) hi++
            found += Peak(
                freqHz = centerHz + (p - n / 2) * binHz,
                powerDb = pk, snrDb = pk - floor,
                bandwidthHz = max(1, hi - lo) * binHz,
            )
            i = j
        }
        val kept = ArrayList<Peak>()
        for (p in found.sortedByDescending { it.powerDb }) {
            if (kept.none { abs(it.freqHz - p.freqHz) < minSeparationHz }) kept += p
            if (kept.size >= maxPeaks) break
        }
        return kept.sortedBy { it.freqHz }
    }

    /** Mean power (dB) of the bins within [bandwidthHz] around [freqHz]. */
    fun channelPower(psd: FloatArray, centerHz: Double, sampleRate: Double, freqHz: Double, bandwidthHz: Double): Float {
        val n = psd.size
        val binHz = sampleRate / n
        val c = ((freqHz - centerHz) / binHz + n / 2).toInt()
        val half = max(1, (bandwidthHz / 2 / binHz).toInt())
        var sum = 0.0; var cnt = 0
        for (i in max(0, c - half)..min(n - 1, c + half)) {
            sum += Math.pow(10.0, psd[i] / 10.0); cnt++
        }
        if (cnt == 0) return -150f
        return (10 * log10(sum / cnt + 1e-20)).toFloat()
    }
}

// ------------------------------------------------------------- filters ---

fun lowpassTaps(cutoffHz: Double, sampleRate: Double, numTaps: Int): FloatArray {
    val fc = cutoffHz / sampleRate
    val m = (numTaps - 1) / 2.0
    val h = DoubleArray(numTaps) { i ->
        val x = i - m
        val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
        sinc * (0.54 - 0.46 * cos(2 * PI * i / (numTaps - 1)))
    }
    val s = h.sum()
    return FloatArray(numTaps) { (h[it] / s).toFloat() }
}

/** Streaming complex FIR low-pass + decimation; only computes kept outputs. */
class ComplexDecimator(val factor: Int, cutoffHz: Double, inRate: Double, numTaps: Int = 64) {
    private val taps = lowpassTaps(cutoffHz, inRate, numTaps)
    private val histRe = FloatArray(numTaps - 1)
    private val histIm = FloatArray(numTaps - 1)
    private var phase = 0

    fun process(x: Iq): Iq {
        val nt = taps.size
        val h = nt - 1
        val total = h + x.size
        val bRe = FloatArray(total); val bIm = FloatArray(total)
        System.arraycopy(histRe, 0, bRe, 0, h); System.arraycopy(histIm, 0, bIm, 0, h)
        System.arraycopy(x.re, 0, bRe, h, x.size); System.arraycopy(x.im, 0, bIm, h, x.size)
        val outN = if (x.size > phase) (x.size - phase + factor - 1) / factor else 0
        val out = Iq(outN)
        var o = 0
        var idx = phase
        while (idx < x.size) {
            var sr = 0f; var si = 0f
            val base = idx // output aligned to end of window: window = [idx, idx+nt)
            for (t in 0 until nt) {
                val w = taps[t]
                sr += bRe[base + t] * w; si += bIm[base + t] * w
            }
            out.re[o] = sr; out.im[o] = si; o++
            idx += factor
        }
        phase = idx - x.size
        System.arraycopy(bRe, total - h, histRe, 0, h); System.arraycopy(bIm, total - h, histIm, 0, h)
        return out
    }
}

/** Streaming real FIR low-pass + decimation. */
class RealDecimator(val factor: Int, cutoffHz: Double, inRate: Double, numTaps: Int = 64) {
    private val taps = lowpassTaps(cutoffHz, inRate, numTaps)
    private val hist = FloatArray(numTaps - 1)
    private var phase = 0

    fun process(x: FloatArray): FloatArray {
        val nt = taps.size; val h = nt - 1
        val buf = FloatArray(h + x.size)
        System.arraycopy(hist, 0, buf, 0, h); System.arraycopy(x, 0, buf, h, x.size)
        val outN = if (x.size > phase) (x.size - phase + factor - 1) / factor else 0
        val out = FloatArray(outN)
        var o = 0; var idx = phase
        while (idx < x.size) {
            var s = 0f
            for (t in 0 until nt) s += buf[idx + t] * taps[t]
            out[o++] = s; idx += factor
        }
        phase = idx - x.size
        System.arraycopy(buf, buf.size - h, hist, 0, h)
        return out
    }
}

/** Phase-continuous frequency shifter: moves a signal at [offsetHz] to 0 Hz. */
class Nco(offsetHz: Double, private val sampleRate: Double) {
    private var stepRe = cos(-2 * PI * offsetHz / sampleRate)
    private var stepIm = sin(-2 * PI * offsetHz / sampleRate)
    var offsetHz = offsetHz
        private set

    /** Retune without a phase jump (used for fine tuning / AFC). */
    fun setOffset(hz: Double) {
        if (hz == offsetHz) return
        offsetHz = hz
        stepRe = cos(-2 * PI * hz / sampleRate); stepIm = sin(-2 * PI * hz / sampleRate)
    }
    private var pRe = 1.0
    private var pIm = 0.0

    fun process(x: Iq): Iq {
        val out = Iq(x.size)
        for (i in 0 until x.size) {
            val r = x.re[i]; val q = x.im[i]
            out.re[i] = (r * pRe - q * pIm).toFloat()
            out.im[i] = (r * pIm + q * pRe).toFloat()
            val nr = pRe * stepRe - pIm * stepIm
            pIm = pRe * stepIm + pIm * stepRe
            pRe = nr
        }
        val mag = hypot(pRe, pIm) // renormalise to stop amplitude drift
        pRe /= mag; pIm /= mag
        return out
    }
}

/** Linear-interpolating streaming resampler. */
class Resampler(private val inRate: Double, private val outRate: Double) {
    private var pos = 0.0
    private var last = 0f

    fun process(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val step = inRate / outRate
        val out = ArrayList<Float>((x.size / step).toInt() + 2)
        // Sample index -1 refers to the last sample of the previous block.
        while (pos < x.size - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val f = (pos - i).toFloat()
            val a = if (i < 0) last else x[i]
            val b = x[i + 1]
            out += a + (b - a) * f
            pos += step
        }
        pos -= x.size
        last = x[x.size - 1]
        return out.toFloatArray()
    }
}

// --------------------------------------------------------- demodulators ---

/**
 * Turns wideband IQ into 48 kHz audio for a channel [offsetHz] from centre.
 * Stage 1 decimates to ~256 kHz, stage 2 (narrow modes) to ~50 kHz.
 */
class Demodulator(
    val mode: Mode,
    val sampleRate: Double,
    offsetHz: Double,
    bandwidthHz: Double = mode.bandwidthHz,
    /** Squelch threshold in dB (channel power); null = open. */
    var squelchDb: Float? = null,
    deemphasisUs: Double = 75.0,
) {
    init { require(mode != Mode.NONE) { "no audio for data mode" } }

    private val baseOffsetHz = offsetHz
    private val nco = Nco(offsetHz, sampleRate)

    /** Manual fine tuning (clarifier / RIT), Hz. Can change while running. */
    @Volatile var clarifierHz = 0.0
    /** Automatic frequency control: follow a drifting FM/AM carrier. */
    @Volatile var afcEnabled = false
    /** Only let AFC move while a real signal is present. */
    @Volatile var afcGate = true
    /** Correction AFC has applied so far, Hz. */
    var afcHz = 0.0
        private set
    /** Impulse-noise blanker (applied before the channel filter's final stage). */
    val blanker = NoiseBlanker()
    @Volatile var blankerEnabled = false
    private val d1 = max(1, (sampleRate / 256e3).toInt())
    private val rate1 = sampleRate / d1
    private val stage1 = ComplexDecimator(
        d1, if (mode == Mode.WFM) min(100e3, rate1 * 0.45) else min(rate1 * 0.2, 40e3), sampleRate, 64,
    )
    private val d2 = if (mode == Mode.WFM) 1 else max(1, (rate1 / 50e3).toInt())
    val ifRate = rate1 / d2
    private val stage2 = if (d2 > 1) ComplexDecimator(d2, bandwidthHz / 2, rate1, 96) else null
    private val bw = bandwidthHz

    // WFM: audio low-pass + decimation to ~51 kHz
    private val audioDec = if (mode == Mode.WFM) RealDecimator(max(1, (ifRate / 50e3).toInt()), 15e3, ifRate, 64) else null
    private val audioRate = if (audioDec != null) ifRate / audioDec.factor else ifRate
    private val resampler = Resampler(audioRate, AUDIO_RATE.toDouble())

    private var prevRe = 1f
    private var prevIm = 0f
    private val deemphAlpha = (1 - exp(-1 / (ifRate * deemphasisUs * 1e-6))).toFloat()
    private var deemph = 0f
    private var dc = 0f
    private var agc = 1e-3f
    private val ssbShift = if (mode == Mode.USB) bw / 2 else -bw / 2
    private val ssbDown = Nco(ssbShift, ifRate)
    private val ssbFilter = ComplexDecimator(1, bw / 2, ifRate, 96)
    private val ssbUp = Nco(-ssbShift, ifRate)

    /** Channel power of the last block, dBFS. */
    var levelDb = -150f
        private set

    // Channel envelope (magnitude) at ~10 kHz, for spotting on/off keying.
    private val envFactor = max(1, (ifRate / 10e3).toInt())
    val envelopeRate = ifRate / envFactor
    private var envAcc = 0f
    private var envCount = 0

    /** Channel envelope for the last block, at [envelopeRate]. */
    var envelope = FloatArray(0)
        private set

    fun process(iq: Iq): FloatArray {
        if (!afcEnabled) afcHz = 0.0
        nco.setOffset(baseOffsetHz + clarifierHz + afcHz)
        var x = stage1.process(nco.process(iq))
        if (blankerEnabled) x = blanker.process(x)
        if (stage2 != null) x = stage2.process(x)
        if (x.size == 0) return FloatArray(0)
        if (afcEnabled && afcGate && (mode == Mode.NFM || mode == Mode.WFM || mode == Mode.AM)) trackCarrier(x)
        var p = 0.0
        for (i in 0 until x.size) p += x.re[i] * x.re[i] + x.im[i] * x.im[i]
        levelDb = (10 * log10(p / x.size + 1e-20)).toFloat()
        val env = FloatArray((envCount + x.size) / envFactor)
        var e = 0
        for (i in 0 until x.size) {
            envAcc += sqrt(x.re[i] * x.re[i] + x.im[i] * x.im[i]); envCount++
            if (envCount == envFactor) { env[e++] = envAcc / envFactor; envAcc = 0f; envCount = 0 }
        }
        envelope = env

        var audio = when (mode) {
            Mode.WFM, Mode.NFM -> fm(x)
            Mode.AM -> am(x)
            else -> ssb(x)
        }
        if (audioDec != null) audio = audioDec.process(audio)
        audio = resampler.process(audio)
        val sq = squelchDb
        if (sq != null && levelDb < sq) audio.fill(0f)
        for (i in audio.indices) audio[i] = audio[i].coerceIn(-1f, 1f)
        return audio
    }

    /** Mean phase rotation per sample = how far the carrier is off centre. */
    private fun trackCarrier(x: Iq) {
        var sr = 0.0; var si = 0.0
        for (i in 1 until x.size) {
            val r = x.re[i]; val q = x.im[i]; val pr = x.re[i - 1]; val pq = x.im[i - 1]
            sr += r * pr + q * pq; si += q * pr - r * pq
        }
        val errHz = atan2(si, sr) * ifRate / (2 * PI)
        val limit = max(bw, 5e3)
        afcHz = (afcHz + 0.5 * errHz).coerceIn(-limit, limit)
    }

    private fun fm(x: Iq): FloatArray {
        val dev = if (mode == Mode.WFM) 75e3 else 5e3
        val gain = (ifRate / (2 * PI * dev)).toFloat()
        val out = FloatArray(x.size)
        var pr = prevRe; var pi = prevIm
        for (i in 0 until x.size) {
            val r = x.re[i]; val q = x.im[i]
            // angle(x[i] * conj(x[i-1]))
            val d = atan2(q * pr - r * pi, r * pr + q * pi)
            out[i] = d * gain
            pr = r; pi = q
        }
        prevRe = pr; prevIm = pi
        if (mode == Mode.WFM) {
            var s = deemph
            for (i in out.indices) { s += deemphAlpha * (out[i] - s); out[i] = s }
            deemph = s
        }
        for (i in out.indices) out[i] *= 0.8f
        return out
    }

    private fun am(x: Iq): FloatArray {
        val env = FloatArray(x.size) { sqrt(x.re[it] * x.re[it] + x.im[it] * x.im[it]) }
        return agc(env, removeDc = true)
    }

    private fun ssb(x: Iq): FloatArray {
        val y = ssbUp.process(ssbFilter.process(ssbDown.process(x)))
        return agc(y.re.copyOf(), removeDc = false)
    }

    private fun agc(a: FloatArray, removeDc: Boolean): FloatArray {
        if (removeDc) {
            val m = a.average().toFloat()
            dc = if (dc == 0f) m else 0.95f * dc + 0.05f * m
            for (i in a.indices) a[i] -= dc
        }
        var peak = 0f
        for (v in a) peak = max(peak, abs(v))
        agc = max(0.9f * agc + 0.1f * peak, 1e-6f)
        val g = 1f / (agc * 2)
        for (i in a.indices) a[i] *= g
        return a
    }
}
