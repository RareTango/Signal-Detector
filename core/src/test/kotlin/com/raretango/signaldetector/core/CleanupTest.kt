package com.raretango.signaldetector.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupTest {
    private fun toneDb(a: FloatArray, f: Double, from: Int = 0): Double {
        var re = 0.0; var im = 0.0
        for (i in from until a.size) { re += a[i] * cos(2 * PI * f * i / 48000); im += a[i] * sin(2 * PI * f * i / 48000) }
        val n = a.size - from
        return 10 * log10((re * re + im * im) / n / n * 4 + 1e-20)
    }

    private fun powerDb(a: FloatArray, from: Int = 0): Double =
        10 * log10((from until a.size).sumOf { (a[it] * a[it]).toDouble() } / (a.size - from) + 1e-20)

    @Test fun noiseReductionImprovesSnr() {
        val rnd = Random(2)
        val n = 48000 * 3
        // Speech-like: the tone comes and goes like syllables (steady tones are what NR treats as noise).
        val tone = FloatArray(n) { if ((it / 12000) % 2 == 0) (0.3 * sin(2 * PI * 800 * it / 48000)).toFloat() else 0f }
        val noisy = FloatArray(n) { tone[it] + (rnd.nextFloat() - 0.5f) * 0.9f }
        val nr = NoiseReducer(0.8f)
        val out = noisy.toList().chunked(4800).flatMap { nr.process(it.toFloatArray()).toList() }.toFloatArray()
        assertEquals(n, out.size)
        val from = 48000 // let the noise estimate settle
        fun snr(a: FloatArray): Double {
            val t = toneDb(a, 800.0, from)
            val total = powerDb(a, from)
            val noise = 10 * log10(Math.pow(10.0, total / 10) - Math.pow(10.0, t / 10) / 2 + 1e-20)
            return t - 3 - noise
        }
        val before = snr(noisy); val after = snr(out)
        assertTrue(after - before > 6, "SNR before %.1f dB, after %.1f dB".format(before, after))
    }

    @Test fun voiceFilterBand() {
        fun gain(f: Double): Double {
            val x = FloatArray(48000) { (0.5 * sin(2 * PI * f * it / 48000)).toFloat() }
            val y = VoiceFilter().process(x)
            return toneDb(y, f, 4800) - toneDb(x, f, 4800)
        }
        assertTrue(abs(gain(1000.0)) < 1.5, "1 kHz ${gain(1000.0)}")
        assertTrue(gain(60.0) < -25, "60 Hz hum ${gain(60.0)}")
        assertTrue(gain(8000.0) < -25, "8 kHz hiss ${gain(8000.0)}")
    }

    @Test fun afcFindsOffCentreFmSignal() {
        // NFM signal 2 kHz above where we tuned.
        val sim = SimulatedSource(centerHz = 146.52e6 + 250e3 - 2e3, realtime = false)
        val d = Demodulator(Mode.NFM, sim.sampleRate, -250e3)
        d.afcEnabled = true
        repeat(10) { d.process(sim.read(204800)) }
        assertEquals(2000.0, d.afcHz, 150.0)
    }

    @Test fun clarifierShiftsSsbPitch() {
        val sim = SimulatedSource(centerHz = 14.2e6 + 250e3, realtime = false,
            emitters = listOf(SimEmitter(14.2e6, Mode.USB, -40.0, 0.0, 1000.0, "ssb")))
        val d = Demodulator(Mode.USB, sim.sampleRate, -250e3)
        d.clarifierHz = 300.0 // tuning 300 Hz high lowers the pitch by 300 Hz
        var a = FloatArray(0)
        repeat(3) { a = d.process(sim.read(204800)) }
        assertTrue(toneDb(a, 700.0) > toneDb(a, 1000.0) + 10, "expected 700 Hz tone")
    }

    @Test fun blankerRemovesClicks() {
        val n = 200_000
        val x = Iq(n)
        for (i in 0 until n) { x.re[i] = 0.1f; x.im[i] = 0f }
        for (k in 0 until 20) x.re[k * 9000 + 500] = 5f
        val b = NoiseBlanker()
        b.process(x)
        assertEquals(20L, b.blanked)
        assertTrue(x.re.max() <= 0.1f)
    }
}
