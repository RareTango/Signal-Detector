package com.raretango.signaldetector.core

import kotlin.math.abs
import kotlin.math.max

data class SpectrumFrame(
    val psd: FloatArray,
    val centerHz: Double,
    val sampleRate: Double,
    val peaks: List<Peak>,
    val noiseFloorDb: Float,
    val tunedHz: Double,
    /** Power in the tuned channel, dBFS (drives the signal meter and DF). */
    val channelDb: Float,
    /** Correction applied by automatic frequency control, Hz. */
    val afcHz: Double = 0.0,
)

data class ScanHit(val peak: Peak, val band: Band?, val known: KnownFrequency?)

interface ReceiverListener {
    fun onSpectrum(frame: SpectrumFrame) {}
    fun onAudio(samples: FloatArray) {}
    fun onAudioBlocked(reason: String) {}
    fun onAprs(packet: AprsPacket) {}
    fun onAdsb(message: AdsbDecoder.Message) {}
    /** About once a second: what kind of signal / code is on the tuned channel. */
    fun onClassification(result: Classification) {}
    fun onScanProgress(band: Band, fraction: Float, hits: List<ScanHit>) {}
    fun onError(e: Throwable) {}
}

/**
 * Runs the receive loop on its own thread. All setters are thread-safe and
 * take effect on the next block.
 */
class Receiver(private val listener: ReceiverListener) {
    @Volatile private var source: IqSource? = null
    @Volatile var tunedHz = 96.1e6
        private set
    @Volatile var mode = Mode.WFM
        private set
    @Volatile var listening = false
    @Volatile var squelchDb: Float? = null
    @Volatile var thresholdDb = 10f
    @Volatile var aprsEnabled = true
    /** Analyse the tuned channel for Morse / binary data even when not listening. */
    @Volatile var analyzeEnabled = true

    // ---- clean-up controls (all take effect immediately, no retune) ----
    /** Fine tuning / clarifier offset, Hz. */
    @Volatile var clarifierHz = 0.0
    /** Follow a drifting carrier automatically (FM/AM). */
    @Volatile var afcEnabled = false
    /** Impulse noise blanker. */
    @Volatile var blankerEnabled = false
    /** Channel filter width; null = the mode's default. Changing it rebuilds the filter. */
    @Volatile var bandwidthHz: Double? = null
    /** Spectral noise reduction 0 (off) .. 1 (strong). */
    @Volatile var noiseReduction = 0f
    /** 300-3000 Hz speech band-pass. */
    @Volatile var voiceFilter = false
    private var voice = VoiceFilter()
    private val reducer = NoiseReducer(0f)
    @Volatile var fftSize = 2048

    @Volatile private var dirty = true
    @Volatile private var running = false
    @Volatile private var scanBand: Band? = null
    private var thread: Thread? = null
    private var demod: Demodulator? = null
    private var demodKey: List<Any>? = null
    private var classifier: SignalClassifier? = null
    private var lastClassifyMs = 0L
    private var lastSnrDb = 0f
    private var analyzer = SpectrumAnalyzer(fftSize)
    private val adsb = AdsbDecoder()
    private val afsk = AfskDemodulator { frame -> Ax25.decode(frame)?.let(listener::onAprs) }

    val sourceName get() = source?.name ?: "none"
    val isRunning get() = running

    /** Tune to a frequency. The hardware centre is offset so the signal avoids the DC spike. */
    fun tune(freqHz: Double, newMode: Mode? = null) {
        tunedHz = freqHz
        mode = newMode ?: mode
        dirty = true
    }

    fun setMode(m: Mode) { mode = m; dirty = true }

    fun startScan(band: Band) { scanBand = band }
    fun stopScan() { scanBand = null; dirty = true }

    fun start(src: IqSource) {
        stop()
        source = src
        running = true
        dirty = true
        thread = Thread({ loop(src) }, "receiver").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        try { source?.close() } catch (_: Exception) {}
        source = null
    }

    private fun isAdsb() = abs(tunedHz - 1090e6) < 1e6

    private fun applyTuning(src: IqSource) {
        val wantRate = if (isAdsb()) 2.0e6 else 2.048e6
        if (src.sampleRate != wantRate) src.applySampleRate(wantRate)
        val offset = if (isAdsb()) 0.0 else 250e3
        val center = tunedHz + offset
        if (abs(src.centerHz - center) > 1) src.tune(center)
        if (analyzer.fftSize != fftSize) analyzer = SpectrumAnalyzer(fftSize)
        dirty = false
    }

    private fun loop(src: IqSource) {
        try {
            while (running) {
                val band = scanBand
                if (band != null) { scan(src, band); continue }
                if (dirty) applyTuning(src)
                val n = max(fftSize * 8, (src.sampleRate / 10).toInt() / fftSize * fftSize)
                val iq = src.read(n)
                if (dirty) continue // retuned mid-read: discard stale samples
                val psd = analyzer.compute(iq)
                val floor = PeakFinder.noiseFloor(psd)
                val peaks = PeakFinder.find(psd, src.centerHz, src.sampleRate, thresholdDb)
                val chan = PeakFinder.channelPower(psd, src.centerHz, src.sampleRate, tunedHz + clarifierHz + (demod?.afcHz ?: 0.0),
                    max(bandwidthHz ?: mode.bandwidthHz, 5e3))
                listener.onSpectrum(SpectrumFrame(psd, src.centerHz, src.sampleRate, peaks, floor, tunedHz, chan, demod?.afcHz ?: 0.0))

                if (isAdsb()) {
                    adsb.process(iq).forEach(listener::onAdsb)
                    continue
                }
                lastSnrDb = chan - floor
                if (listening || analyzeEnabled || (aprsEnabled && isAprsFreq())) demodulate(src, iq)
            }
        } catch (e: Throwable) {
            if (running) listener.onError(e)
        } finally {
            running = false
        }
    }

    private fun isAprsFreq() = abs(tunedHz - 144.39e6) < 3e3 || abs(tunedHz - 144.8e6) < 3e3

    private fun demodulate(src: IqSource, iq: Iq) {
        val perm = BandPlan.audioPermission(tunedHz, mode.bandwidthHz)
        if (!perm.allowed || mode == Mode.NONE) {
            if (listening) listener.onAudioBlocked(perm.reason.ifEmpty { "No audio in data mode." })
            maybeClassify {
                Classification(SignalKind.NONE, 1f, "Content analysis is off here: " + perm.reason.ifEmpty { "data-only band." })
            }
            return
        }
        val bw = bandwidthHz ?: mode.bandwidthHz
        val offset = tunedHz - src.centerHz
        val key = listOf(offset, src.sampleRate, mode, bw)
        if (demod == null || demodKey != key) {
            val retuned = demodKey == null || demodKey!![0] != offset || demodKey!![2] != mode
            demod = Demodulator(mode, src.sampleRate, offset, bw)
            demodKey = key
            if (retuned) {
                classifier = SignalClassifier(demod!!.envelopeRate)
                voice = VoiceFilter()
            }
        }
        val d = demod!!
        d.clarifierHz = clarifierHz
        d.afcEnabled = afcEnabled
        d.afcGate = lastSnrDb > 8
        d.blankerEnabled = blankerEnabled
        val audio = d.process(iq) // unsquelched, so analysis still works while muted
        if (aprsEnabled && mode == Mode.NFM) afsk.process(audio)
        if (analyzeEnabled) {
            val c = classifier!!
            c.feed(d.envelope, audio)
            maybeClassify { c.classify(lastSnrDb, tunedHz) }
        }
        if (listening) {
            var out = audio
            if (voiceFilter && mode != Mode.WFM) out = voice.process(out)
            reducer.strength = noiseReduction
            out = reducer.process(out)
            val sq = squelchDb
            listener.onAudio(if (sq != null && d.levelDb < sq) FloatArray(out.size) else out)
        }
    }

    private inline fun maybeClassify(make: () -> Classification) {
        val now = System.currentTimeMillis()
        if (now - lastClassifyMs >= 1000) {
            lastClassifyMs = now
            listener.onClassification(make())
        }
    }

    private fun scan(src: IqSource, band: Band) {
        val rate = 2.048e6
        if (src.sampleRate != rate) src.applySampleRate(rate)
        val usable = rate * 0.8
        val steps = max(1, kotlin.math.ceil(band.widthHz / usable).toInt())
        val hits = ArrayList<ScanHit>()
        for (s in 0 until steps) {
            if (scanBand !== band || !running) return
            val center = band.startHz + usable * (s + 0.5)
            src.tune(center)
            src.read(fftSize * 4) // settle
            val iq = src.read(fftSize * 16)
            val psd = analyzer.compute(iq)
            val floor = PeakFinder.noiseFloor(psd)
            val peaks = PeakFinder.find(psd, center, rate, thresholdDb, max(band.stepHz, 10e3))
                .filter { abs(it.freqHz - center) <= usable / 2 && it.freqHz in band }
            hits += peaks.map { ScanHit(it, BandPlan.bandFor(it.freqHz), KnownFrequencies.match(it.freqHz)) }
            listener.onSpectrum(SpectrumFrame(psd, center, rate, peaks, floor, tunedHz, -150f))
            listener.onScanProgress(band, (s + 1f) / steps, hits.sortedByDescending { it.peak.snrDb })
        }
        scanBand = null
        dirty = true
    }
}
