package com.raretango.signaldetector.core

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BandsTest {
    @Test fun cellularAudioBlocked() {
        val p = BandPlan.audioPermission(851e6, 12.5e3)
        assertFalse(p.allowed)
        assertTrue(p.reason.contains("illegal"))
    }

    @Test fun broadcastAndAmateurAllowed() {
        assertTrue(BandPlan.audioPermission(96.1e6, 200e3).allowed)
        assertTrue(BandPlan.audioPermission(146.52e6, 12.5e3).allowed)
        assertTrue(BandPlan.audioPermission(119.1e6, 10e3).allowed)
    }

    @Test fun channelOverlappingRestrictedEdgeBlocked() {
        assertFalse(BandPlan.audioPermission(823.995e6, 12.5e3).allowed)
    }

    @Test fun mostSpecificBand() {
        assertEquals("NOAA Weather Radio", BandPlan.bandFor(162.55e6)?.name)
        assertEquals(Mode.WFM, BandPlan.defaultMode(100.1e6))
        assertEquals("NOAA Weather Radio WX1", KnownFrequencies.match(162.551e6)?.name)
    }
}

class DspTest {
    private fun tone(n: Int, f: Double, fs: Double, amp: Double = 0.5) = Iq(
        FloatArray(n) { (amp * kotlin.math.cos(2 * PI * f * it / fs)).toFloat() },
        FloatArray(n) { (amp * sin(2 * PI * f * it / fs)).toFloat() },
    )

    @Test fun fftFindsTone() {
        val fs = 2.048e6
        val iq = tone(2048 * 8, 300e3, fs)
        val rnd = kotlin.random.Random(1)
        for (i in 0 until iq.size) { iq.re[i] += (rnd.nextFloat() - 0.5f) * 1e-3f; iq.im[i] += (rnd.nextFloat() - 0.5f) * 1e-3f }
        val psd = SpectrumAnalyzer(2048).compute(iq)
        val peaks = PeakFinder.find(psd, 100e6, fs)
        assertEquals(1, peaks.size)
        assertTrue(abs(peaks[0].freqHz - 100.3e6) < 2e3, "got ${peaks[0].freqHz}")
        assertTrue(peaks[0].snrDb > 40)
    }

    @Test fun nfmDemodRecoversTone() {
        val fs = 2.048e6
        val n = 204800
        val off = 250e3
        val tone = 1000.0
        var ph = 0.0
        val iq = Iq(n)
        for (i in 0 until n) {
            ph += 2 * PI * (off + 3e3 * sin(2 * PI * tone * i / fs)) / fs
            iq.re[i] = (0.3 * kotlin.math.cos(ph)).toFloat(); iq.im[i] = (0.3 * sin(ph)).toFloat()
        }
        val d = Demodulator(Mode.NFM, fs, off)
        val a = d.process(iq)
        assertTrue(abs(a.size - 4800) < 20, "audio length ${a.size}")
        assertEquals(1000.0, dominantFreq(a.copyOfRange(1000, a.size), 48000.0), 30.0)
    }

    @Test fun wfmAndAmFromSimulator() {
        val sim = SimulatedSource(centerHz = 96.1e6 + 250e3, realtime = false)
        val d = Demodulator(Mode.WFM, sim.sampleRate, -250e3)
        val a = (0 until 3).map { d.process(sim.read(204800)) }.last()
        assertEquals(660.0, dominantFreq(a, 48000.0), 30.0)

        sim.tune(119.1e6 + 250e3)
        val am = Demodulator(Mode.AM, sim.sampleRate, -250e3)
        val b = (0 until 3).map { am.process(sim.read(204800)) }.last()
        assertEquals(800.0, dominantFreq(b, 48000.0), 30.0)
    }

    private fun dominantFreq(a: FloatArray, fs: Double): Double {
        var bestF = 0.0; var best = 0.0
        var f = 100.0
        while (f < 5000) {
            var re = 0.0; var im = 0.0
            for (i in a.indices) { re += a[i] * kotlin.math.cos(2 * PI * f * i / fs); im += a[i] * sin(2 * PI * f * i / fs) }
            val m = sqrt(re * re + im * im)
            if (m > best) { best = m; bestF = f }
            f += 10
        }
        return bestF
    }
}

class DirectionTest {
    @Test fun sweepFindsSimulatedBearing() {
        val sim = SimulatedSource(centerHz = 146.52e6 + 250e3, realtime = false)
        val sweep = BearingSweep()
        val an = SpectrumAnalyzer(2048)
        var h = 0.0
        while (h < 360) {
            sim.antennaHeadingDeg = h
            val psd = an.compute(sim.read(2048 * 4))
            sweep.add(h, PeakFinder.channelPower(psd, sim.centerHz, sim.sampleRate, 146.52e6, 12.5e3))
            h += 7.5
        }
        val r = sweep.estimate()!!
        assertTrue(abs(Geo.diff(r.bearingDeg, 75.0)) < 10, "bearing ${r.bearingDeg}")
        assertTrue(r.contrastDb > 10)
    }

    @Test fun geoBearing() {
        val nyc = LatLon(40.7128, -74.0060); val boston = LatLon(42.3601, -71.0589)
        assertEquals(54.0, Geo.bearingDeg(nyc, boston), 2.0)
        assertEquals(306.0, Geo.distanceKm(nyc, boston), 5.0)
        assertEquals("NE", Geo.compassPoint(45.0))
    }

    @Test fun triangulation() {
        val target = LatLon(40.0, -105.0)
        val a = LatLon(39.9, -105.1); val b = LatLon(39.95, -104.85)
        val p = SourceLocator.triangulate(a, Geo.bearingDeg(a, target), b, Geo.bearingDeg(b, target))!!
        assertTrue(Geo.distanceKm(p, target) < 0.3, "off by ${Geo.distanceKm(p, target)} km")
    }

    @Test fun krakenParse() {
        val spec = (0 until 360).joinToString(",") { "0.5" }
        val line = "1700000000000, 123.5, 8.2, -45.0, 433.92, UCA, 120, KRAKEN1, 40.1, -105.2, 0, 0, GPS, R, R, R, R, $spec"
        val k = KrakenDoa.parse(line)!!
        assertEquals(123.5, k.bearingDeg)
        assertEquals(433.92e6, k.freqHz, 1.0)
        assertEquals(360, k.spectrum.size)
        assertEquals(40.1, k.position!!.lat)
    }

    @Test fun knownStationsCsvAndMatching() {
        val csv = "frequency_mhz,name,lat,lon,notes\n96.1,\"KXYZ, Top 40\",40.0,-105.0,\n162.55,NOAA KEC76,,,\n"
        val list = KnownStation.parseCsv(csv)
        assertEquals(2, list.size)
        assertEquals("KXYZ, Top 40", list[0].name)
        assertNull(list[1].position)
        assertEquals(list, KnownStation.parseCsv(KnownStation.toCsv(list)))
        val me = LatLon(39.9, -105.0)
        val m = SourceLocator.candidates(96.101e6, list, me, measuredBearing = 2.0)
        assertEquals(1, m.size)
        assertTrue(m[0].bearingErrorDeg!! < 5)
    }
}

class AdsbTest {
    @Test fun callsign() {
        val b = AdsbDecoder.hexToBytes("8D4840D6202CC371C32CE0576098")
        assertEquals(0, AdsbDecoder.crc(b, 112))
        val m = AdsbDecoder.Message(b)
        assertEquals(17, m.df); assertEquals("4840D6", "%06X".format(m.icao)); assertEquals(4, m.typeCode)
        assertEquals("KLM1023", AdsbDecoder.callsign(b))
    }

    @Test fun positionAndAltitude() {
        val even = AdsbDecoder.hexToBytes("8D40621D58C382D690C8AC2863A7")
        val odd = AdsbDecoder.hexToBytes("8D40621D58C386435CC412692AD6")
        assertEquals(38000, AdsbDecoder.altitudeFt(even))
        val p = AdsbDecoder.globalPosition(even, odd, oddIsNewer = false)!!
        assertEquals(52.2572, p.lat, 0.001); assertEquals(3.91937, p.lon, 0.001)
        val local = AdsbDecoder.localPosition(even, LatLon(52.258, 3.918))
        assertEquals(52.2572, local.lat, 0.001); assertEquals(3.91937, local.lon, 0.001)
    }

    @Test fun velocity() {
        val v = AdsbDecoder.velocity(AdsbDecoder.hexToBytes("8D485020994409940838175B284F"))!!
        assertEquals(159.2, v.first, 0.5); assertEquals(182.88, v.second, 0.5)
    }

    @Test fun demodulatesPpmSamples() {
        val hex = "8D4840D6202CC371C32CE0576098"
        val bytes = AdsbDecoder.hexToBytes(hex)
        val n = 4000
        val iq = Iq(n)
        val start = 1234
        val noise = kotlin.random.Random(3)
        for (i in 0 until n) { iq.re[i] = noise.nextFloat() * 0.02f; iq.im[i] = noise.nextFloat() * 0.02f }
        for (p in listOf(0, 2, 7, 9)) iq.re[start + p] = 0.8f
        for (b in 0 until 112) {
            val bit = (bytes[b / 8].toInt() shr (7 - b % 8)) and 1
            iq.re[start + 16 + 2 * b + (1 - bit)] = 0.8f
        }
        val dec = AdsbDecoder()
        val msgs = dec.process(iq)
        assertEquals(1, msgs.size)
        assertEquals(hex, msgs[0].hex)
        val t = AircraftTracker()
        t.update(msgs[0], 0, null)
        assertEquals("KLM1023", t.snapshot(0)[0].callsign)
    }
}

class AprsTest {
    @Test fun afskRoundTrip() {
        val frame = Ax25.encode("N0CALL-9", "APRS", listOf("WIDE1-1", "WIDE2-1"), "!4903.50N/07201.75W>Mobile 146.52")
        val audio = AfskModulator.modulate(frame)
        val got = ArrayList<AprsPacket>()
        val demod = AfskDemodulator { f -> Ax25.decode(f)?.let(got::add) }
        // Add noise and feed in odd-sized chunks like real audio blocks.
        val rnd = kotlin.random.Random(5)
        val noisy = FloatArray(audio.size + 2000) { if (it in 1000 until 1000 + audio.size) audio[it - 1000] + (rnd.nextFloat() - 0.5f) * 0.2f else 0f }
        noisy.toList().chunked(1234).forEach { demod.process(it.toFloatArray()) }
        assertEquals(1, got.size)
        val p = got[0]
        assertEquals("N0CALL-9", p.source)
        assertEquals("APRS", p.destination)
        assertEquals(listOf("WIDE1-1", "WIDE2-1"), p.path)
        assertEquals(49.0583, p.position!!.lat, 0.001)
        assertEquals(-72.0292, p.position!!.lon, 0.001)
        assertEquals("Mobile 146.52", p.comment)
    }

    @Test fun timestampedPosition() {
        val (pos, _, _) = Aprs.parsePosition("@092345z4903.50N/07201.75W>")
        assertNotNull(pos)
    }
}

class UpdatesTest {
    private val json = """
        {"tag_name":"build-42","name":"Build 42","html_url":"https://github.com/x/y/releases/tag/build-42",
         "published_at":"2026-11-23T12:00:00Z","author":{"login":"github-actions[bot]"},
         "body":"Build: 42\nAuthor: Jane Doe\nCommit: abc1234\nMessage: Add waterfall",
         "assets":[{"name":"signal-detector-build-42.apk","size":12345,
           "url":"https://api.github.com/repos/x/y/releases/assets/1",
           "browser_download_url":"https://github.com/x/y/releases/download/build-42/signal-detector-build-42.apk"}]}
    """.trimIndent()

    @Test fun parsesRelease() {
        val r = Updates.parseRelease(json)
        assertEquals(42, r.buildNumber)
        assertEquals("Jane Doe", r.author)
        assertEquals("abc1234", r.commit)
        assertTrue(r.apkUrl!!.endsWith(".apk"))
    }

    @Test fun statusText() {
        val r = Updates.parseRelease(json)
        assertEquals(UpdateState.UPDATE_AVAILABLE, Updates.compare(41, r))
        assertEquals("Build 42 available · sent by Jane Doe", Updates.statusText(41, r))
        assertEquals("Current build (Build 42 · sent by Jane Doe)", Updates.statusText(42, r))
    }
}

class RtlTcpTest {
    @Test fun talksProtocol() {
        val server = ServerSocket(0)
        val commands = ArrayList<Pair<Int, Int>>()
        val t = thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write("RTL0".toByteArray()); out.write(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29))
                val inp = java.io.DataInputStream(s.getInputStream())
                // sample rate, freq, gain mode, agc = 4 commands
                repeat(4) { commands += inp.readUnsignedByte() to inp.readInt() }
                out.write(ByteArray(8) { if (it % 2 == 0) 255.toByte() else 0 })
                out.flush()
                Thread.sleep(200)
            }
        }
        val src = RtlTcpSource("127.0.0.1", server.localPort, centerHz = 100e6)
        val iq = src.read(4)
        t.join()
        src.close()
        assertTrue(src.name.contains("R820T"))
        assertEquals(0x02 to 2_048_000, commands[0])
        assertEquals(0x01 to 100_000_000, commands[1])
        assertEquals(1f, iq.re[0], 0.01f); assertEquals(-1f, iq.im[0], 0.01f)
    }
}
