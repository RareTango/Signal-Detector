package com.raretango.signaldetector

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raretango.signaldetector.core.AdsbDecoder
import com.raretango.signaldetector.core.Aircraft
import com.raretango.signaldetector.core.AircraftTracker
import com.raretango.signaldetector.core.AprsPacket
import com.raretango.signaldetector.core.Band
import com.raretango.signaldetector.core.BandPlan
import com.raretango.signaldetector.core.BearingSweep
import com.raretango.signaldetector.core.Classification
import com.raretango.signaldetector.core.KnownStation
import com.raretango.signaldetector.core.KrakenDoa
import com.raretango.signaldetector.core.LatLon
import com.raretango.signaldetector.core.Mode
import com.raretango.signaldetector.core.Receiver
import com.raretango.signaldetector.core.ReceiverListener
import com.raretango.signaldetector.core.ReleaseInfo
import com.raretango.signaldetector.core.RtlTcpSource
import com.raretango.signaldetector.core.ScanHit
import com.raretango.signaldetector.core.SimulatedSource
import com.raretango.signaldetector.core.SourceLocator
import com.raretango.signaldetector.core.SpectrumFrame
import com.raretango.signaldetector.core.UpdateState
import com.raretango.signaldetector.core.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class SourceKind(val label: String) {
    SIMULATOR("Simulator (no hardware)"),
    USB("RTL-SDR on USB-OTG"),
    NETWORK("rtl_tcp over Wi-Fi"),
}

data class AppSettings(
    val source: SourceKind = SourceKind.SIMULATOR,
    val host: String = "192.168.1.50",
    val port: Int = 1234,
    val gainDb: Float? = null,
    val ppm: Int = 0,
    val directSampling: Boolean = false,
    val thresholdDb: Float = 10f,
    val squelchDb: Float? = null,
    val volume: Float = 0.8f,
    val phoneUpright: Boolean = false,
    val antenna: BearingSweep.AntennaType = BearingSweep.AntennaType.DIRECTIONAL_PEAK,
    val krakenEnabled: Boolean = false,
    val krakenUrl: String = "http://krakensdr.local:8081/DOA_value.html",
    val krakenOffsetDeg: Float = 0f,
    /** Look for Morse / binary data on the tuned channel. */
    val analyze: Boolean = true,
    val afc: Boolean = false,
    val noiseBlanker: Boolean = false,
    /** 0 = off .. 1 = strong */
    val noiseReduction: Float = 0f,
    val voiceFilter: Boolean = false,
)

data class Connection(val connected: Boolean = false, val connecting: Boolean = false, val status: String = "Not connected")

data class SweepUi(
    val active: Boolean = false,
    val pattern: FloatArray = FloatArray(0),
    val result: BearingSweep.Result? = null,
    val freqHz: Double = 0.0,
)

/** A bearing taken from a known spot; two or more triangulate the transmitter. */
data class BearingFix(val position: LatLon, val bearingDeg: Double, val freqHz: Double, val timeMs: Long)

data class AprsStation(val packet: AprsPacket, val lastHeardMs: Long, val count: Int)

data class ScanUi(val band: Band, val progress: Float, val hits: List<ScanHit>, val running: Boolean)

data class UpdateUi(
    val checking: Boolean = false,
    val latest: ReleaseInfo? = null,
    val state: UpdateState? = null,
    val error: String? = null,
    val downloading: Float? = null,
    val checked: Boolean = false,
) {
    val statusText: String
        get() = when {
            error != null -> "Couldn't check: $error"
            checking -> "Checking GitHub…"
            downloading != null -> "Downloading build ${latest?.buildNumber}… ${(downloading * 100).toInt()}%"
            checked && latest == null -> "No builds published on GitHub yet"
            else -> Updates.statusText(BuildConfig.BUILD_NUMBER, latest)
        }
}

class AppViewModel(app: Application) : AndroidViewModel(app), ReceiverListener {
    private val prefs = app.getSharedPreferences("signal-detector", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _connection = MutableStateFlow(Connection())
    val connection = _connection.asStateFlow()

    private val _spectrum = MutableStateFlow<SpectrumFrame?>(null)
    val spectrum = _spectrum.asStateFlow()
    val waterfall = Waterfall(512, 220)

    private val _tuned = MutableStateFlow(prefs.getFloat("tunedMhz", 96.1f).toDouble() * 1e6)
    val tunedHz = _tuned.asStateFlow()
    private val _mode = MutableStateFlow(BandPlan.defaultMode(_tuned.value))
    val mode = _mode.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()
    private val _audioBlocked = MutableStateFlow<String?>(null)
    val audioBlocked = _audioBlocked.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _heading = MutableStateFlow<Float?>(null)
    val heading = _heading.asStateFlow()
    private val _location = MutableStateFlow<LatLon?>(null)
    val location = _location.asStateFlow()

    private val sweep = BearingSweep(5)
    private val _sweep = MutableStateFlow(SweepUi())
    val sweepUi = _sweep.asStateFlow()

    private val _kraken = MutableStateFlow<KrakenDoa?>(null)
    val kraken = _kraken.asStateFlow()
    private val _krakenError = MutableStateFlow<String?>(null)
    val krakenError = _krakenError.asStateFlow()

    private val tracker = AircraftTracker()
    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft = _aircraft.asStateFlow()
    private val _adsbCount = MutableStateFlow(0L)
    val adsbCount = _adsbCount.asStateFlow()

    private val _aprs = MutableStateFlow<List<AprsStation>>(emptyList())
    val aprs = _aprs.asStateFlow()

    private val _stations = MutableStateFlow(KnownStation.parseCsv(prefs.getString("stations", "") ?: ""))
    val stations = _stations.asStateFlow()

    private val _fixes = MutableStateFlow<List<BearingFix>>(emptyList())
    val fixes = _fixes.asStateFlow()

    private val _classification = MutableStateFlow<Classification?>(null)
    val classification = _classification.asStateFlow()
    /** Morse text decoded so far on this frequency (keeps growing while you listen). */
    private val _morseLog = MutableStateFlow("")
    val morseLog = _morseLog.asStateFlow()

    private val _clarifier = MutableStateFlow(0.0)
    val clarifierHz = _clarifier.asStateFlow()
    /** Channel filter width; null = mode default. */
    private val _filter = MutableStateFlow<Double?>(null)
    val filterHz = _filter.asStateFlow()

    private val _scan = MutableStateFlow<ScanUi?>(null)
    val scan = _scan.asStateFlow()

    private val _update = MutableStateFlow(UpdateUi())
    val update = _update.asStateFlow()

    private val receiver = Receiver(this)
    private val audio = AudioPlayer()
    private val compass = Compass(app) { h ->
        _heading.value = h
        sim?.antennaHeadingDeg = h.toDouble()
    }
    val compassAvailable get() = compass.available
    private var sim: SimulatedSource? = null
    private var krakenJob: Job? = null
    private var lastAircraftPush = 0L

    init {
        compass.upright = _settings.value.phoneUpright
        compass.start()
        applyReceiverSettings()
        restartKraken()
        checkForUpdate()
        // The simulator needs no hardware, so start it right away to show something live.
        if (_settings.value.source == SourceKind.SIMULATOR) connect()
    }

    // ------------------------------------------------------------ settings

    private fun loadSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            source = runCatching { SourceKind.valueOf(prefs.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            host = prefs.getString("host", d.host)!!,
            port = prefs.getInt("port", d.port),
            gainDb = prefs.getFloat("gain", -1f).takeIf { it >= 0 },
            ppm = prefs.getInt("ppm", 0),
            directSampling = prefs.getBoolean("direct", false),
            thresholdDb = prefs.getFloat("threshold", d.thresholdDb),
            squelchDb = prefs.getFloat("squelch", 1f).takeIf { it <= 0 },
            volume = prefs.getFloat("volume", d.volume),
            phoneUpright = prefs.getBoolean("upright", false),
            antenna = runCatching { BearingSweep.AntennaType.valueOf(prefs.getString("antenna", d.antenna.name)!!) }.getOrDefault(d.antenna),
            krakenEnabled = prefs.getBoolean("kraken", false),
            krakenUrl = prefs.getString("krakenUrl", d.krakenUrl)!!,
            krakenOffsetDeg = prefs.getFloat("krakenOffset", 0f),
            analyze = prefs.getBoolean("analyze", true),
            afc = prefs.getBoolean("afc", false),
            noiseBlanker = prefs.getBoolean("nb", false),
            noiseReduction = prefs.getFloat("nr", 0f),
            voiceFilter = prefs.getBoolean("voiceFilter", false),
        )
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val s = f(old)
        _settings.value = s
        prefs.edit()
            .putString("source", s.source.name).putString("host", s.host).putInt("port", s.port)
            .putFloat("gain", s.gainDb ?: -1f).putInt("ppm", s.ppm).putBoolean("direct", s.directSampling)
            .putFloat("threshold", s.thresholdDb).putFloat("squelch", s.squelchDb ?: 1f)
            .putFloat("volume", s.volume).putBoolean("upright", s.phoneUpright)
            .putString("antenna", s.antenna.name).putBoolean("kraken", s.krakenEnabled)
            .putString("krakenUrl", s.krakenUrl).putFloat("krakenOffset", s.krakenOffsetDeg)
            .putBoolean("analyze", s.analyze).putBoolean("afc", s.afc).putBoolean("nb", s.noiseBlanker)
            .putFloat("nr", s.noiseReduction).putBoolean("voiceFilter", s.voiceFilter)
            .apply()
        compass.upright = s.phoneUpright
        applyReceiverSettings()
        if (old.krakenEnabled != s.krakenEnabled || old.krakenUrl != s.krakenUrl) restartKraken()
        if (old.source != s.source && _connection.value.connected) disconnect()
    }

    private fun applyReceiverSettings() {
        val s = _settings.value
        receiver.thresholdDb = s.thresholdDb
        receiver.squelchDb = s.squelchDb
        receiver.analyzeEnabled = s.analyze
        receiver.afcEnabled = s.afc
        receiver.blankerEnabled = s.noiseBlanker
        receiver.noiseReduction = s.noiseReduction
        receiver.voiceFilter = s.voiceFilter
        audio.setVolume(s.volume)
    }

    // ---------------------------------------------------------- connection

    /** Connect to the rtl_tcp server (USB driver app, network) or the simulator. */
    fun connect() {
        val s = _settings.value
        _connection.value = Connection(connecting = true, status = "Connecting…")
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = when (s.source) {
                    SourceKind.SIMULATOR -> SimulatedSource(centerHz = _tuned.value).also {
                        sim = it; it.antennaHeadingDeg = _heading.value?.toDouble()
                    }
                    SourceKind.USB -> { sim = null; RtlTcpSource("127.0.0.1", 1234) }
                    SourceKind.NETWORK -> { sim = null; RtlTcpSource(s.host, s.port) }
                }
                src.setGain(s.gainDb?.toDouble())
                if (s.ppm != 0) src.setPpm(s.ppm)
                if (s.directSampling) src.setDirectSampling(2)
                receiver.tune(_tuned.value, _mode.value)
                receiver.start(src)
                _connection.value = Connection(connected = true, status = src.name)
            } catch (e: Exception) {
                _connection.value = Connection(status = "Connection failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        setListening(false)
        viewModelScope.launch(Dispatchers.IO) { receiver.stop() }
        _connection.value = Connection(status = "Disconnected")
    }

    fun onUsbDriverFailed(reason: String) {
        _connection.value = Connection(status = reason)
    }

    // -------------------------------------------------------------- tuning

    fun tune(freqHz: Double, mode: Mode? = null) {
        val f = freqHz.coerceIn(100e3, 6e9)
        val m = mode ?: if (BandPlan.bandFor(f) != BandPlan.bandFor(_tuned.value)) BandPlan.defaultMode(f) else _mode.value
        _tuned.value = f
        if (m != _mode.value) setFilter(null)
        _mode.value = m
        _audioBlocked.value = null
        setClarifier(0.0)
        _classification.value = null
        _morseLog.value = ""

        prefs.edit().putFloat("tunedMhz", (f / 1e6).toFloat()).apply()
        receiver.tune(f, m)
        if (_sweep.value.active && kotlin.math.abs(_sweep.value.freqHz - f) > 5e3) stopSweep()
    }

    fun tuneBand(b: Band) = tune(b.startHz + kotlin.math.min(b.widthHz / 2, 500e3), if (b.mode == Mode.NONE) null else b.mode)

    fun setMode(m: Mode) { _mode.value = m; setFilter(null); receiver.setMode(m) }

    /** Fine tuning (clarifier): shifts the receiver by a few Hz..kHz without retuning. */
    fun setClarifier(hz: Double) {
        val v = hz.coerceIn(-5000.0, 5000.0)
        _clarifier.value = v
        receiver.clarifierHz = v
    }

    fun setFilter(hz: Double?) {
        _filter.value = hz
        receiver.bandwidthHz = hz
    }

    /** Re-centre on the strongest signal close to where you're tuned. */
    fun snapToSignal(): Boolean {
        val f = _spectrum.value ?: return false
        val here = _tuned.value + _clarifier.value
        val reach = maxOf(_mode.value.bandwidthHz * 1.5, 15e3)
        val p = f.peaks.filter { kotlin.math.abs(it.freqHz - here) <= reach }.maxByOrNull { it.snrDb } ?: run {
            _error.value = "No signal close to ${"%.4f".format(here / 1e6)} MHz to lock onto."
            return false
        }
        val m = _mode.value
        if (m == Mode.USB || m == Mode.LSB) {
            // SSB has no carrier; move the clarifier so the voice band is centred.
            setClarifier(p.freqHz - _tuned.value + (if (m == Mode.USB) -1500.0 else 1500.0))
        } else {
            val step = BandPlan.bandFor(p.freqHz)?.stepHz ?: 0.0
            val snapped = if (step > 0) Math.round(p.freqHz / step) * step else p.freqHz
            tune(if (kotlin.math.abs(snapped - p.freqHz) < step / 4) snapped else p.freqHz, m)
        }
        return true
    }

    fun setListening(on: Boolean) {
        if (on) {
            val perm = BandPlan.audioPermission(_tuned.value, _mode.value.bandwidthHz)
            if (!perm.allowed) { _audioBlocked.value = perm.reason; return }
            audio.start()
        } else {
            audio.pause()
        }
        _audioBlocked.value = null
        _listening.value = on
        receiver.listening = on
    }

    fun scanBand(b: Band) {
        if (!_connection.value.connected) { _error.value = "Connect a receiver first (Settings)."; return }
        setListening(false)
        _scan.value = ScanUi(b, 0f, emptyList(), true)
        receiver.startScan(b)
    }

    fun stopScan() {
        receiver.stopScan()
        _scan.update { it?.copy(running = false) }
    }

    fun dismissError() { _error.value = null }

    // -------------------------------------------------- receiver callbacks

    override fun onSpectrum(frame: SpectrumFrame) {
        _spectrum.value = frame
        waterfall.addRow(frame.psd, frame.noiseFloorDb)
        val h = _heading.value
        if (_sweep.value.active && h != null && frame.channelDb > -149) {
            sweep.add(h.toDouble(), frame.channelDb)
            _sweep.update { it.copy(pattern = sweep.pattern(), result = sweep.estimate(_settings.value.antenna)) }
        }
    }

    override fun onAudio(samples: FloatArray) = audio.write(samples)

    override fun onClassification(result: Classification) {
        _classification.value = result
        val t = result.text
        if (result.kind == com.raretango.signaldetector.core.SignalKind.MORSE && t != null) {
            // The decoder sees a sliding 15 s window; keep a running transcript.
            _morseLog.update { log -> mergeTranscript(log, t) }
        }
    }

    private fun mergeTranscript(log: String, window: String): String {
        if (log.isEmpty()) return window
        if (log.endsWith(window) || log.contains(window)) return log
        // Find the longest overlap between the end of the log and the start of the window.
        for (k in minOf(log.length, window.length) downTo 3) {
            if (log.endsWith(window.substring(0, k))) return (log + window.substring(k)).takeLast(2000)
        }
        return (log + " " + window).takeLast(2000)
    }

    override fun onAudioBlocked(reason: String) {
        _audioBlocked.value = reason
        if (_listening.value) { _listening.value = false; receiver.listening = false; audio.pause() }
    }

    override fun onAprs(packet: AprsPacket) {
        val now = System.currentTimeMillis()
        _aprs.update { list ->
            val old = list.firstOrNull { it.packet.source == packet.source }
            val merged = AprsStation(
                if (packet.position == null && old?.packet?.position != null) packet.copy(position = old.packet.position) else packet,
                now, (old?.count ?: 0) + 1,
            )
            (listOf(merged) + list.filter { it.packet.source != packet.source }).take(200)
        }
    }

    override fun onAdsb(message: AdsbDecoder.Message) {
        val now = System.currentTimeMillis()
        tracker.update(message, now, _location.value)
        _adsbCount.update { it + 1 }
        if (now - lastAircraftPush > 500) { lastAircraftPush = now; _aircraft.value = tracker.snapshot(now) }
    }

    override fun onScanProgress(band: Band, fraction: Float, hits: List<ScanHit>) {
        _scan.value = ScanUi(band, fraction, hits, fraction < 1f)
    }

    override fun onError(e: Throwable) {
        _connection.value = Connection(status = "Receiver stopped: ${e.message}")
        _listening.value = false
        audio.pause()
    }

    // ---------------------------------------------- location & direction

    @SuppressLint("MissingPermission")
    fun startLocation() {
        val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc: Location -> onLocation(loc) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(p)) continue
            lm.getLastKnownLocation(p)?.let { onLocation(it) }
            lm.requestLocationUpdates(p, 3000L, 5f, listener, Looper.getMainLooper())
        }
    }

    private fun onLocation(l: Location) {
        val p = LatLon(l.latitude, l.longitude)
        _location.value = p
        compass.updateDeclination(p)
    }

    fun startSweep() {
        sweep.reset()
        _sweep.value = SweepUi(active = true, freqHz = _tuned.value)
    }

    fun stopSweep() { _sweep.update { it.copy(active = false) } }

    /** Best current bearing: KrakenSDR if recent and on frequency, else the sweep result. */
    fun bestBearing(): Double? {
        val k = _kraken.value
        if (k != null && System.currentTimeMillis() - k.epochMs < 10_000 &&
            (k.freqHz == 0.0 || kotlin.math.abs(k.freqHz - _tuned.value) < 50e3)) return k.bearingDeg
        return _sweep.value.result?.bearingDeg
    }

    fun saveFix() {
        val p = _location.value ?: run { _error.value = "Waiting for GPS location."; return }
        val b = bestBearing() ?: run { _error.value = "Take a bearing first (sweep or KrakenSDR)."; return }
        _fixes.update { (it + BearingFix(p, b, _tuned.value, System.currentTimeMillis())).takeLast(10) }
    }

    fun clearFixes() { _fixes.value = emptyList() }

    /** Triangulated transmitter location from the two most widely separated fixes. */
    fun triangulated(): LatLon? {
        val f = _fixes.value
        if (f.size < 2) return null
        var best: LatLon? = null
        var bestAngle = 0.0
        for (i in f.indices) for (j in i + 1 until f.size) {
            val ang = kotlin.math.abs(com.raretango.signaldetector.core.Geo.diff(f[i].bearingDeg, f[j].bearingDeg))
            val p = SourceLocator.triangulate(f[i].position, f[i].bearingDeg, f[j].position, f[j].bearingDeg)
            if (p != null && ang > bestAngle) { best = p; bestAngle = ang }
        }
        return best
    }

    private fun restartKraken() {
        krakenJob?.cancel()
        _kraken.value = null
        _krakenError.value = null
        if (!_settings.value.krakenEnabled) return
        krakenJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val c = URL(_settings.value.krakenUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 3000
                    val text = c.inputStream.bufferedReader().readText()
                    c.disconnect()
                    val k = KrakenDoa.parse(text) ?: throw IllegalStateException("Unrecognised DoA data")
                    val off = _settings.value.krakenOffsetDeg
                    _kraken.value = k.copy(
                        bearingDeg = com.raretango.signaldetector.core.Geo.norm(k.bearingDeg + off),
                        epochMs = System.currentTimeMillis(),
                    )
                    _krakenError.value = null
                } catch (e: Exception) {
                    _krakenError.value = e.message ?: e.javaClass.simpleName
                }
                delay(1000)
            }
        }
    }

    // ---------------------------------------------------- known stations

    fun addStation(s: KnownStation) = saveStations(_stations.value + s)
    fun removeStation(s: KnownStation) = saveStations(_stations.value - s)
    fun importStations(csv: String): Int {
        val list = KnownStation.parseCsv(csv)
        saveStations(_stations.value + list)
        return list.size
    }
    fun exportStations(): String = KnownStation.toCsv(_stations.value)

    private fun saveStations(list: List<KnownStation>) {
        _stations.value = list.distinct().sortedBy { it.freqHz }
        prefs.edit().putString("stations", KnownStation.toCsv(_stations.value)).apply()
    }

    // ------------------------------------------------------------ updates

    fun checkForUpdate() {
        if (_update.value.checking) return
        _update.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                _update.value = UpdateUi(
                    latest = latest, checked = true,
                    state = latest?.let { Updates.compare(BuildConfig.BUILD_NUMBER, it) },
                )
            } catch (e: Exception) {
                _update.value = UpdateUi(checked = true, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The Update button: install the newer build if there is one, else re-check. */
    fun onUpdateButton(context: Context) {
        val u = _update.value
        val latest = u.latest
        if (latest == null || u.state != UpdateState.UPDATE_AVAILABLE) { checkForUpdate(); return }
        if (!Updater.ensureInstallPermission(context)) return
        _update.update { it.copy(downloading = 0f, error = null) }
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(getApplication<Application>(), latest) { p -> _update.update { it.copy(downloading = p) } }
                }
                _update.update { it.copy(downloading = null) }
                Updater.install(getApplication<Application>(), apk)
            } catch (e: Exception) {
                _update.update { it.copy(downloading = null, error = e.message ?: "download failed") }
            }
        }
    }

    override fun onCleared() {
        compass.stop()
        receiver.stop()
        audio.release()
    }
}
