@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.raretango.signaldetector.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raretango.signaldetector.AppViewModel
import com.raretango.signaldetector.BuildConfig
import com.raretango.signaldetector.SourceKind
import com.raretango.signaldetector.core.BandPlan
import com.raretango.signaldetector.core.BearingSweep
import com.raretango.signaldetector.core.Geo
import com.raretango.signaldetector.core.KnownFrequencies
import com.raretango.signaldetector.core.KnownStation
import com.raretango.signaldetector.core.LatLon
import com.raretango.signaldetector.core.Mode
import com.raretango.signaldetector.core.SourceLocator
import com.raretango.signaldetector.core.UpdateState

// ---------------------------------------------------------------- shared

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Accent, fontSize = 15.sp)
            content()
        }
    }
}

@Composable
fun Small(text: String, color: Color = Muted) = Text(text, color = color, fontSize = 13.sp)

@Composable
fun Banner(text: String, color: Color, onClose: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onClose != null) TextButton(onClick = onClose) { Text("OK") }
    }
}

private fun distText(km: Double) = if (km < 1) "%.0f m".format(km * 1000) else "%.1f km".format(km)

// --------------------------------------------------------------- scanner

@Composable
fun ScannerScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val frame by vm.spectrum.collectAsStateWithLifecycle()
    val tuned by vm.tunedHz.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val blocked by vm.audioBlocked.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val band = BandPlan.bandFor(tuned)
    val perm = BandPlan.audioPermission(tuned, mode.bandwidthHz)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!conn.connected) {
            Banner(conn.status + " — tap to set up a receiver", Amber)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings) { Text("Receiver settings") }
            }
        }
        error?.let { Banner(it, Danger) { vm.dismissError() } }

        SpectrumWaterfall(frame, vm.waterfall, onTap = { vm.tune(snap(it, band?.stepHz ?: 0.0)) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

        Section("Tuned") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mhz(tuned), fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = Color.White,
                    modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Small(band?.name ?: "Unallocated / unknown")
                    KnownFrequencies.match(tuned)?.let { Small(it.name, Amber) }
                }
            }
            frame?.let { SignalMeter(it.channelDb, it.noiseFloorDb) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val step = band?.stepHz?.takeIf { it > 0 } ?: 5e3
                OutlinedButton(onClick = { vm.tune(tuned - step) }) { Text("◀") }
                OutlinedButton(onClick = { vm.tune(tuned + step) }) { Text("▶") }
                FrequencyEntry { vm.tune(it) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in Mode.entries.filter { it != Mode.NONE }) {
                    FilterChip(selected = mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.setListening(!listening) },
                    enabled = conn.connected && perm.allowed,
                    colors = if (listening) ButtonDefaults.buttonColors(containerColor = Danger) else ButtonDefaults.buttonColors(),
                ) { Text(if (listening) "Stop audio" else "Listen") }
                Column(Modifier.weight(1f)) {
                    Small("Volume")
                    Slider(value = settings.volume, onValueChange = { v -> vm.updateSettings { it.copy(volume = v) } })
                }
            }
            if (!perm.allowed) Small("🔒 " + perm.reason, Danger)
            blocked?.let { Small("🔒 $it", Danger) }
        }

        Section("Detected signals") {
            val peaks = frame?.peaks.orEmpty().sortedByDescending { it.snrDb }
            if (peaks.isEmpty()) Small("No signals above the threshold in this window.")
            for (p in peaks.take(15)) {
                val b = BandPlan.bandFor(p.freqHz)
                val known = KnownFrequencies.match(p.freqHz)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.tune(snap(p.freqHz, b?.stepHz ?: 0.0)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(mhz(p.freqHz), fontFamily = FontFamily.Monospace, color = Color.White, modifier = Modifier.width(130.dp))
                    Column(Modifier.weight(1f)) {
                        Small(known?.name ?: b?.service ?: "Unknown", if (b?.audioAllowed == false) Danger else Muted)
                    }
                    Text("+%.0f dB".format(p.snrDb), color = Good, fontSize = 13.sp)
                }
            }
        }

        Section("Bands  (${BandPlan.all.size})") {
            Small("Tap a band to tune, or Scan to sweep the whole band for active signals.")
            scan?.let { s ->
                Small("Scanning ${s.band.name}: ${(s.progress * 100).toInt()}%  · ${s.hits.size} signals")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                if (s.running) OutlinedButton(onClick = { vm.stopScan() }) { Text("Stop scan") }
                for (h in s.hits.take(20)) {
                    Row(Modifier.fillMaxWidth().clickable { vm.tune(h.peak.freqHz) }.padding(vertical = 3.dp)) {
                        Text(mhz(h.peak.freqHz), fontFamily = FontFamily.Monospace, color = Color.White, modifier = Modifier.width(130.dp))
                        Small(h.known?.name ?: h.band?.service ?: "", Modifier.weight(1f))
                        Text("+%.0f dB".format(h.peak.snrDb), color = Good, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = Grid)
            }
            for (b in BandPlan.all) {
                Row(Modifier.fillMaxWidth().clickable { vm.tuneBand(b) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, color = if (b.audioAllowed) Color.White else Danger, fontSize = 14.sp)
                        Small("${mhz(b.startHz, 3)} – ${mhz(b.endHz, 3)} · ${b.service}")
                    }
                    if (b.widthHz > 1e6) TextButton(onClick = { vm.scanBand(b) }) { Text("Scan") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Small(text: String, modifier: Modifier) = Text(text, color = Muted, fontSize = 13.sp, modifier = modifier)

private fun snap(f: Double, step: Double) = if (step > 0) Math.round(f / step) * step else Math.round(f / 1000.0) * 1000.0

@Composable
fun FrequencyEntry(onTune: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text("MHz") }, modifier = Modifier.width(120.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        TextButton(onClick = { text.toDoubleOrNull()?.let { onTune(it * 1e6) } }) { Text("Go") }
    }
}

// ------------------------------------------------------------- direction

@Composable
fun DirectionScreen(vm: AppViewModel) {
    val heading by vm.heading.collectAsStateWithLifecycle()
    val sweep by vm.sweepUi.collectAsStateWithLifecycle()
    val kraken by vm.kraken.collectAsStateWithLifecycle()
    val krakenErr by vm.krakenError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val frame by vm.spectrum.collectAsStateWithLifecycle()
    val tuned by vm.tunedHz.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val fixes by vm.fixes.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val bearing = vm.bestBearing()
    val candidates = SourceLocator.candidates(tuned, stations, location, bearing)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        error?.let { Banner(it, Danger) { vm.dismissError() } }
        Section("Direction to ${mhz(tuned)}") {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
                CompassRose(heading, bearing, Modifier.fillMaxSize(),
                    secondaryBearings = candidates.mapNotNull { it.bearingDeg })
            }
            if (bearing != null) {
                Text("Signal is coming from ${bearingText(bearing)}", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                heading?.let { h ->
                    val turn = Geo.diff(bearing, h.toDouble())
                    Small(if (kotlin.math.abs(turn) < 8) "You are facing it." else
                        "Turn %s %.0f°".format(if (turn > 0) "right" else "left", kotlin.math.abs(turn)))
                }
            } else {
                Small("No bearing yet. Do a sweep below or connect a KrakenSDR.")
            }
            Small("Heading: " + (heading?.let { bearingText(it.toDouble()) } ?: if (vm.compassAvailable) "calibrating…" else "no compass on this phone") +
                (if (location == null) " (magnetic; true north once GPS fixes)" else " (true north)"))
            frame?.let {
                Small("Signal level: %.1f dB (noise %.1f dB)".format(it.channelDb, it.noiseFloorDb))
                SignalMeter(it.channelDb, it.noiseFloorDb)
            }
        }

        Section("Rotate-and-listen sweep") {
            Small("Hold a directional antenna (Yagi / log-periodic / tape-measure Yagi) lined up with the phone " +
                (if (settings.phoneUpright) "camera" else "top edge") +
                ", start the sweep, then turn slowly through a full circle. Loop antennas: choose 'null' — the signal is where it fades out.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sweep.active) Button(onClick = { vm.stopSweep() }) { Text("Finish sweep") }
                else Button(onClick = { vm.startSweep() }) { Text("Start sweep") }
                OutlinedButton(onClick = { vm.saveFix() }) { Text("Save bearing fix") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.antenna == BearingSweep.AntennaType.DIRECTIONAL_PEAK,
                    { vm.updateSettings { it.copy(antenna = BearingSweep.AntennaType.DIRECTIONAL_PEAK) } })
                Small("Directional (peak)")
                RadioButton(settings.antenna == BearingSweep.AntennaType.LOOP_NULL,
                    { vm.updateSettings { it.copy(antenna = BearingSweep.AntennaType.LOOP_NULL) } })
                Small("Loop (null)")
            }
            if (sweep.pattern.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                    PolarPattern(sweep.pattern, sweep.result?.bearingDeg, Modifier.fillMaxSize())
                }
            }
            sweep.result?.let {
                Small("Sweep: ${bearingText(it.bearingDeg)} ±%.0f° · contrast %.0f dB · %d%% of circle covered"
                    .format(it.spreadDeg / 2, it.contrastDb, (it.coverage * 100).toInt()),
                    if (it.contrastDb > 6) Good else Amber)
                if (it.contrastDb < 6) Small("Low contrast: move away from buildings / reflections, or reduce gain.", Amber)
            }
        }

        Section("Triangulate the transmitter") {
            Small("Save a bearing fix, move a few hundred metres sideways, take another. Two fixes give a location.")
            for (f in fixes) Small("• ${"%.5f, %.5f".format(f.position.lat, f.position.lon)} → ${bearingText(f.bearingDeg)} @ ${mhz(f.freqHz, 3)}")
            val tri = vm.triangulated()
            if (tri != null) {
                Text("Estimated transmitter: %.5f, %.5f".format(tri.lat, tri.lon), color = Amber, fontWeight = FontWeight.SemiBold)
                location?.let { Small("${distText(Geo.distanceKm(it, tri))} away, ${bearingText(Geo.bearingDeg(it, tri))}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${tri.lat},${tri.lon}?q=${tri.lat},${tri.lon}(Transmitter)")))
                    }) { Text("Open in Maps") }
                    OutlinedButton(onClick = {
                        vm.addStation(KnownStation(tuned, "Triangulated %.3f MHz".format(tuned / 1e6), tri))
                    }) { Text("Save as station") }
                }
            }
            if (fixes.isNotEmpty()) TextButton(onClick = { vm.clearFixes() }) { Text("Clear fixes") }
        }

        Section("KrakenSDR (5-antenna direction finder)") {
            Small("A KrakenSDR gives instant bearings without rotating anything. Run its DoA software and point this at its web output.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.krakenEnabled, { on -> vm.updateSettings { it.copy(krakenEnabled = on) } })
                Spacer(Modifier.width(8.dp)); Small(if (settings.krakenEnabled) "Polling" else "Off")
            }
            if (settings.krakenEnabled) {
                var url by remember { mutableStateOf(settings.krakenUrl) }
                OutlinedTextField(url, { url = it }, label = { Text("DoA URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.updateSettings { it.copy(krakenUrl = url.trim()) } }) { Text("Save URL") }
                kraken?.let {
                    Text("Kraken: ${bearingText(it.bearingDeg)} · confidence %.1f · %.3f MHz".format(it.confidence, it.freqHz / 1e6), color = Good)
                }
                krakenErr?.let { Small("Kraken: $it", Danger) }
            }
        }

        Section("Who could it be?") {
            if (candidates.isEmpty()) Small("No saved stations on this frequency. Add them on the Who tab (or import a CSV).")
            for (c in candidates) {
                Text(c.station.name, color = Color.White)
                Small(listOfNotNull(
                    c.bearingDeg?.let { "at ${bearingText(it)}" },
                    c.distanceKm?.let { distText(it) },
                    c.bearingErrorDeg?.let { if (it < 20) "✔ matches measured bearing" else "%.0f° off measured bearing".format(it) },
                ).joinToString(" · "), if ((c.bearingErrorDeg ?: 99.0) < 20) Good else Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------- who

@Composable
fun WhoScreen(vm: AppViewModel) {
    val tuned by vm.tunedHz.collectAsStateWithLifecycle()
    val aircraft by vm.aircraft.collectAsStateWithLifecycle()
    val adsbCount by vm.adsbCount.collectAsStateWithLifecycle()
    val aprs by vm.aprs.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val band = BandPlan.bandFor(tuned)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            vm.importStations(text)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("This signal: ${mhz(tuned)}") {
            Text(band?.service ?: "Unknown service", color = Color.White)
            band?.let { Small("${it.name} · ${mhz(it.startHz, 3)} – ${mhz(it.endHz, 3)}") }
            KnownFrequencies.match(tuned)?.let { Text(it.name, color = Amber) }
            val matches = SourceLocator.candidates(tuned, stations, location, vm.bestBearing())
            for (m in matches) Small("Saved: ${m.station.name}" + (m.bearingDeg?.let { " · ${bearingText(it)}" } ?: ""), Good)
            Small("Voice users rarely identify themselves digitally. Amateur stations must say their callsign at least every 10 minutes; " +
                "broadcast stations give call letters at the top of the hour. Aircraft (ADS-B) and APRS stations identify automatically — see below.")
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.radioreference.com/db/browse/")))
            }) { Text("Look up local frequencies (RadioReference)") }
        }

        Section("Aircraft (ADS-B 1090 MHz)") {
            Small("Tune to 1090 MHz with a 1090-capable antenna. Each plane broadcasts its ID, callsign, altitude and position.")
            Button(onClick = { vm.tune(1090e6, Mode.NONE) }) { Text("Tune 1090 MHz") }
            Small("$adsbCount messages decoded · ${aircraft.size} aircraft")
            for (a in aircraft) {
                val pos = a.position
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text((a.callsign ?: "—") + "  ·  " + a.icaoHex, color = Color.White, fontFamily = FontFamily.Monospace)
                        Small(listOfNotNull(
                            a.altitudeFt?.let { "$it ft" },
                            a.speedKt?.let { "%.0f kt".format(it) },
                            a.trackDeg?.let { "hdg %.0f°".format(it) },
                        ).joinToString(" · "))
                    }
                    if (pos != null && location != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(bearingText(Geo.bearingDeg(location!!, pos)), color = Amber, fontSize = 13.sp)
                            Small(distText(Geo.distanceKm(location!!, pos)))
                        }
                    }
                }
                HorizontalDivider(color = Grid)
            }
        }

        Section("Ham radio stations (APRS)") {
            Small("APRS packets carry the operator's callsign and usually their GPS position.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.tune(144.39e6, Mode.NFM) }) { Text("144.390 (Americas)") }
                OutlinedButton(onClick = { vm.tune(144.8e6, Mode.NFM) }) { Text("144.800 (Europe)") }
            }
            if (aprs.isEmpty()) Small("No packets yet. They're decoded automatically while tuned to an APRS frequency.")
            for (s in aprs) {
                val p = s.packet
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.source, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Small(p.comment.ifBlank { p.info }.take(80))
                        val pos = p.position
                        if (pos != null && location != null) {
                            Small("${bearingText(Geo.bearingDeg(location!!, pos))} · ${distText(Geo.distanceKm(location!!, pos))}", Amber)
                        }
                    }
                    TextButton(onClick = {
                        val call = p.source.substringBefore('-')
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qrz.com/db/$call")))
                    }) { Text("Who?") }
                }
                HorizontalDivider(color = Grid)
            }
        }

        Section("Saved stations (${stations.size})") {
            Small("Known transmitters with their location. Matched by frequency; with GPS and a bearing, the app shows which one you're hearing.")
            AddStationForm(tuned) { vm.addStation(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) }) { Text("Import CSV") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, vm.exportStations()), "Export stations"))
                }) { Text("Export") }
            }
            Small("CSV columns: frequency_mhz,name,lat,lon,notes")
            for (s in stations) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { vm.tune(s.freqHz) }) {
                        Text("${mhz(s.freqHz)}  ${s.name}", color = Color.White, fontSize = 14.sp)
                        s.position?.let { p -> Small("%.4f, %.4f".format(p.lat, p.lon) + (location?.let { " · ${bearingText(Geo.bearingDeg(it, p))}" } ?: "")) }
                    }
                    TextButton(onClick = { vm.removeStation(s) }) { Text("Delete") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddStationForm(tunedHz: Double, onAdd: (KnownStation) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name for ${mhz(tunedHz)}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("Lat (optional)") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(lon, { lon = it }, label = { Text("Lon") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(enabled = name.isNotBlank(), onClick = {
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
            onAdd(KnownStation(tunedHz, name.trim(), if (la != null && lo != null) LatLon(la, lo) else null))
            name = ""; lat = ""; lon = ""
        }) { Text("Save station") }
    }
}

// -------------------------------------------------------------- settings

@Composable
fun SettingsScreen(vm: AppViewModel, onConnect: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section("App updates") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.onUpdateButton(context) },
                    enabled = !update.checking && update.downloading == null,
                ) {
                    Text(if (update.state == UpdateState.UPDATE_AVAILABLE) "Update" else "Check for update")
                }
                Text(
                    update.statusText,
                    color = when {
                        update.error != null -> Danger
                        update.state == UpdateState.UPDATE_AVAILABLE -> Amber
                        update.state == UpdateState.CURRENT -> Good
                        else -> Muted
                    },
                    fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
            }
            update.downloading?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Small("Installed: Build ${BuildConfig.BUILD_NUMBER} · sent by ${BuildConfig.BUILD_AUTHOR} · ${BuildConfig.BUILD_COMMIT.take(7)}")
            update.latest?.takeIf { it.message.isNotBlank() }?.let { Small("Latest change: ${it.message}") }
        }

        Section("Receiver") {
            for (k in SourceKind.entries) {
                Row(Modifier.fillMaxWidth().clickable { vm.updateSettings { it.copy(source = k) } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.source == k, { vm.updateSettings { it.copy(source = k) } })
                    Text(k.label, color = Color.White)
                }
            }
            when (s.source) {
                SourceKind.SIMULATOR -> Small("Fake stations for trying the app. Rotate the phone and the signal strength changes as if you had a directional antenna.")
                SourceKind.USB -> Small("Plug an RTL-SDR (e.g. RTL-SDR Blog V4, Nooelec) into the phone with a USB-OTG adapter. Requires the free \"RTL-SDR driver\" app (Martin Marinov), which this app opens automatically.")
                SourceKind.NETWORK -> {
                    Small("Run `rtl_tcp -a 0.0.0.0` on a PC/Raspberry Pi with the dongle, on the same Wi-Fi.")
                    var host by remember { mutableStateOf(s.host) }
                    var port by remember { mutableStateOf(s.port.toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    TextButton(onClick = { vm.updateSettings { it.copy(host = host.trim(), port = port.toIntOrNull() ?: 1234) } }) { Text("Save") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (conn.connected) Button(onClick = { vm.disconnect() }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Disconnect") }
                else Button(onClick = onConnect, enabled = !conn.connecting) { Text("Connect") }
                Small(conn.status, if (conn.connected) Good else Muted)
            }
        }

        Section("Tuner") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.gainDb == null, { auto -> vm.updateSettings { it.copy(gainDb = if (auto) null else 30f) } })
                Spacer(Modifier.width(8.dp)); Small("Automatic gain")
            }
            s.gainDb?.let { g ->
                Small("Gain %.0f dB (lower it if strong signals distort, or for direction finding up close)".format(g))
                Slider(g, { v -> vm.updateSettings { it.copy(gainDb = v) } }, valueRange = 0f..49f)
            }
            Small("Detection threshold: %.0f dB above noise".format(s.thresholdDb))
            Slider(s.thresholdDb, { v -> vm.updateSettings { it.copy(thresholdDb = v) } }, valueRange = 3f..30f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.squelchDb != null, { on -> vm.updateSettings { it.copy(squelchDb = if (on) -60f else null) } })
                Spacer(Modifier.width(8.dp)); Small("Squelch (mute when no signal)")
            }
            s.squelchDb?.let { q -> Slider(q, { v -> vm.updateSettings { it.copy(squelchDb = v) } }, valueRange = -110f..-10f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.directSampling, { on -> vm.updateSettings { it.copy(directSampling = on) } })
                Spacer(Modifier.width(8.dp)); Small("HF direct sampling (RTL-SDR V3/V4: 0.5–28.8 MHz, AM/shortwave/ham HF). Reconnect to apply.")
            }
            var ppm by remember { mutableStateOf(s.ppm.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ppm, { ppm = it }, label = { Text("PPM correction") }, singleLine = true, modifier = Modifier.width(160.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(onClick = { vm.updateSettings { it.copy(ppm = ppm.toIntOrNull() ?: 0) } }) { Text("Save") }
            }
        }

        Section("Compass") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.phoneUpright, { on -> vm.updateSettings { it.copy(phoneUpright = on) } })
                Spacer(Modifier.width(8.dp))
                Small(if (s.phoneUpright) "Phone upright — camera points at the antenna direction" else "Phone flat — top edge points at the antenna direction")
            }
            Small("Wave the phone in a figure-8 if bearings look wrong (compass calibration).")
        }

        Section("Legal") {
            Small("This app only RECEIVES — it cannot transmit. In the US you may listen to broadcast, amateur, CB, FRS/GMRS, " +
                "marine, aviation, weather and unencrypted analog public-safety radio. Audio is locked on cellular, cordless-phone " +
                "and pager frequencies (ECPA / 47 U.S.C. 302a), and the app has no decryption. Don't share or act on the content of " +
                "private communications you overhear (47 U.S.C. 605). Some states restrict scanners in vehicles, and other countries " +
                "have different rules — check yours.")
        }
        Spacer(Modifier.height(16.dp))
    }
}
