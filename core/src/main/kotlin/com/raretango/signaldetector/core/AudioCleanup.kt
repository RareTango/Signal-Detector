package com.raretango.signaldetector.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tools that make a weak or noisy signal easier to understand. None of them
 * can recover audio that is not there: they remove noise AROUND the signal.
 *
 *  - [VoiceFilter]: keeps the 300-3000 Hz speech band, removes rumble and hiss.
 *  - [NoiseReducer]: spectral noise reduction that learns the steady background
 *    hiss and subtracts it.
 *  - [NoiseBlanker]: removes short impulse noise (ignition, electric fences,
 *    switching power supplies) before demodulation.
 *  - AFC and the clarifier live in [Demodulator] (fine tuning).
 */

/** RBJ biquad filter. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }

    companion object {
        private fun make(type: String, f: Double, fs: Double, q: Double): Biquad {
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return if (type == "lp") {
                Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            } else {
                Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
        fun lowpass(f: Double, fs: Double, q: Double = 0.7071) = make("lp", f, fs, q)
        fun highpass(f: Double, fs: Double, q: Double = 0.7071) = make("hp", f, fs, q)
    }
}

/** 4th-order 300-3000 Hz band-pass for speech. */
class VoiceFilter(fs: Double = AUDIO_RATE.toDouble(), low: Double = 300.0, high: Double = 3000.0) {
    private val stages = listOf(
        Biquad.highpass(low, fs, 0.54), Biquad.highpass(low, fs, 1.31),
        Biquad.lowpass(high, fs, 0.54), Biquad.lowpass(high, fs, 1.31),
    )

    fun process(a: FloatArray): FloatArray {
        val out = FloatArray(a.size)
        for (i in a.indices) {
            var v = a[i]
            for (s in stages) v = s.process(v)
            out[i] = v
        }
        return out
    }
}

/**
 * Spectral-subtraction noise reduction (STFT, 512-point frames, 50% overlap).
 * The noise spectrum is tracked as a slowly rising minimum, so it adapts to
 * the background hiss but not to speech. [strength] 0 = off, 1 = strong.
 */
class NoiseReducer(@Volatile var strength: Float = 0.5f) {
    private val n = 512
    private val hop = n / 2
    private val fft = Fft(n)
    // sqrt-Hann analysis + synthesis windows sum to 1 at 50% overlap.
    private val win = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val inBuf = FloatArray(n)
    private var inFill = n - hop
    private val outAcc = FloatArray(n)
    private val noise = DoubleArray(n / 2 + 1) { -1.0 }
    private val smooth = DoubleArray(n / 2 + 1)
    private val gain = DoubleArray(n / 2 + 1) { 1.0 }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val pending = ArrayList<Float>()

    fun process(a: FloatArray): FloatArray {
        if (strength <= 0f) return a
        val out = ArrayList<Float>(a.size + hop)
        out.addAll(pending); pending.clear()
        for (v in a) {
            inBuf[inFill++] = v
            if (inFill == n) {
                frame(out)
                System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
                inFill = n - hop
            }
        }
        // Keep the output length equal to the input length (fixed small latency).
        val res = FloatArray(a.size)
        val take = min(out.size, a.size)
        val offset = a.size - take
        for (i in 0 until take) res[offset + i] = out[i]
        for (i in take until out.size) pending += out[i]
        return res
    }

    private fun frame(out: MutableList<Float>) {
        for (i in 0 until n) { re[i] = inBuf[i] * win[i]; im[i] = 0f }
        fft.transform(re, im)
        val over = 1.0 + 3.0 * strength      // over-subtraction factor
        val floor = 0.25 - 0.2 * strength      // spectral floor (keeps it natural)
        for (k in 0..n / 2) {
            val p = (re[k] * re[k] + im[k] * im[k]).toDouble()
            smooth[k] = if (smooth[k] == 0.0) p else 0.7 * smooth[k] + 0.3 * p
            noise[k] = when {
                noise[k] < 0 -> smooth[k]
                smooth[k] < noise[k] -> smooth[k]
                else -> noise[k] * 1.004 // slow rise (~ several seconds)
            }
            val g = max(floor, 1 - over * noise[k] / (smooth[k] + 1e-20))
            gain[k] = 0.6 * gain[k] + 0.4 * g // temporal smoothing against "musical" noise
        }
        for (k in 0 until n) {
            val g = gain[if (k <= n / 2) k else n - k].toFloat()
            re[k] *= g; im[k] *= g
        }
        // Inverse FFT via conjugation.
        for (k in 0 until n) im[k] = -im[k]
        fft.transform(re, im)
        for (i in 0 until n) outAcc[i] += re[i] / n * win[i]
        for (i in 0 until hop) out += outAcc[i]
        System.arraycopy(outAcc, hop, outAcc, 0, n - hop)
        java.util.Arrays.fill(outAcc, n - hop, n, 0f)
    }
}

/** Blanks impulses that rise far above the average signal level. */
class NoiseBlanker(@Volatile var thresholdFactor: Float = 6f) {
    private var avg = 0.0
    var blanked = 0L
        private set

    fun process(x: Iq): Iq {
        val n = x.size
        val mag = FloatArray(n) { x.re[it] * x.re[it] + x.im[it] * x.im[it] }
        val sorted = mag.copyOf(); sorted.sort()
        val med = sorted[n / 2].toDouble()
        avg = if (avg == 0.0) med else 0.9 * avg + 0.1 * med
        val limit = (avg * thresholdFactor * thresholdFactor).toFloat()
        var i = 0
        while (i < n) {
            if (mag[i] > limit) {
                for (j in max(0, i - 3)..min(n - 1, i + 3)) { x.re[j] = 0f; x.im[j] = 0f }
                blanked++
                i += 4
            } else i++
        }
        return x
    }
}
