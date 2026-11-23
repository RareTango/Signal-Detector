package com.raretango.signaldetector.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassifierTest {
    private fun run(freqHz: Double, mode: Mode, seconds: Double = 6.5): Classification {
        val sim = SimulatedSource(centerHz = freqHz + 250e3, realtime = false)
        val d = Demodulator(mode, sim.sampleRate, -250e3)
        val c = SignalClassifier(d.envelopeRate)
        val an = SpectrumAnalyzer(2048)
        var snr = 0f
        repeat((seconds * 10).toInt()) {
            val iq = sim.read(204800)
            val psd = an.compute(iq)
            snr = maxOf(snr, PeakFinder.channelPower(psd, sim.centerHz, sim.sampleRate, freqHz, maxOf(mode.bandwidthHz, 5e3)) - PeakFinder.noiseFloor(psd))
            val audio = d.process(iq)
            c.feed(d.envelope, audio)
        }
        return c.classify(snr, freqHz)
    }

    @Test fun morseEncodeDecode() {
        val units = MorseCode.encode("CQ DE K1ABC")
        val segs = ArrayList<Segment>()
        var i = 0
        while (i < units.size) {
            var j = i
            while (j < units.size && units[j] == units[i]) j++
            segs += Segment(units[i], (j - i) * 60.0 * (0.9 + 0.2 * ((i * 7) % 5) / 5.0)) // sloppy hand keying
            i = j
        }
        val fit = MorseCode.decode(segs)!!
        assertEquals("CQ DE K1ABC", fit.text)
        assertEquals(20.0, fit.wpm, 2.0)
    }

    @Test fun cwMorseFromCarrierKeying() {
        val r = run(14.06e6, Mode.USB, 14.0)
        assertEquals(SignalKind.MORSE, r.kind, r.toString())
        assertTrue(r.text!!.contains("DE SIM1"), r.text)
    }

    @Test fun mcwMorseInFmAudio() {
        val r = run(146.4e6, Mode.NFM)
        assertEquals(SignalKind.MORSE, r.kind, r.toString())
        assertTrue(r.text!!.contains("W1SIM"), r.text)
    }

    @Test fun ookSensorBits() {
        val r = run(433.92e6, Mode.AM, 3.0)
        assertEquals(SignalKind.OOK_DATA, r.kind, r.toString())
        assertNotNull(r.bits)
        assertEquals(2000.0, r.baud!!, 300.0)
    }

    @Test fun afskPacket() {
        val r = run(144.39e6, Mode.NFM, 4.5)
        assertEquals(SignalKind.AFSK_DATA, r.kind, r.toString())
        assertTrue(r.summary.contains("APRS"))
    }

    @Test fun fskTelemetry() {
        val r = run(152.48e6, Mode.NFM, 3.0)
        assertEquals(SignalKind.FSK_DATA, r.kind, r.toString())
        assertEquals(1200.0, r.baud)
        assertTrue(r.bits!!.replace(" ", "").length >= 64)
    }

    @Test fun digitalVoice() {
        val r = run(155.7e6, Mode.NFM, 3.0)
        assertEquals(SignalKind.DIGITAL_VOICE, r.kind, r.toString())
        assertEquals(null, r.bits)
    }

    @Test fun voiceIsNotCode() {
        val r = run(96.1e6, Mode.WFM, 3.0)
        assertTrue(r.kind == SignalKind.VOICE || r.kind == SignalKind.TONE, r.toString())
        val am = run(119.1e6, Mode.AM, 3.0)
        assertTrue(am.kind != SignalKind.MORSE && am.kind != SignalKind.OOK_DATA, am.toString())
    }

    @Test fun emptyChannel() {
        val r = run(150.0e6, Mode.NFM, 2.0)
        assertEquals(SignalKind.NONE, r.kind, r.toString())
    }

    @Test fun steadyTone() {
        val c = SignalClassifier(10e3)
        val a = FloatArray(48000 * 2) { (0.5 * sin(2 * PI * 1050 * it / 48000)).toFloat() }
        c.feed(FloatArray(20000) { 1f }, a)
        val r = c.classify(30f)
        assertEquals(SignalKind.TONE, r.kind)
        assertTrue(r.summary.contains("NOAA"), r.summary)
    }
}
