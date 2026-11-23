# Signal Detector (Android)

A **receive-only** radio scanner for Android. It finds and shows nearby radio signals across as many bands as your
receiver covers, plays the audio you're allowed to hear, shows **which direction** a signal is coming from, and
identifies **who** is transmitting wherever the signal carries an ID.

## What it does

| Tab | Features |
|---|---|
| **Scanner** | Live spectrum and waterfall (tap to tune). Automatic signal detection with band/service labels. 37 built-in bands from longwave to 1.6 GHz, and "Scan" sweeps a whole band for active signals. **Listen** in WFM / NFM / AM / USB / LSB, with volume and squelch. |
| **Clean up the signal** (Scanner) | **Lock onto signal** re-centres on the nearest transmission. **Auto-track (AFC)** follows drifting FM/AM carriers. **Fine-tune (clarifier)** ±3 kHz: the fix for SSB voices that sound like a duck. **Filter width** per mode. **Noise reduction** (spectral subtraction), **voice filter** (300–3000 Hz), **noise blanker** for clicks and pops, and squelch. |
| **Hidden codes & data** (Scanner) | Analyses the tuned channel every second. **Morse code** is decoded to text with its WPM, whether the carrier is keyed (CW) or a tone is keyed in FM/AM (repeater IDs). **On/off-keyed bursts** from remotes and sensors are shown as raw **binary + hex**. **AFSK** tone pairs (APRS/packet, RTTY, Bell 103) are identified. A **direct FSK** bit stream is shown with its baud rate and raw bits (telemetry, AIS, paging). **Digital voice** (4-level FSK: DMR/P25/NXDN) is identified but never decoded. Steady tones (NOAA alert tone, EAS, test tones), carriers and plain voice are recognised too. |
| **Direction** | Compass arrow pointing at the signal ("turn left 40°"). Rotate-and-listen sweep with a directional or loop antenna, shown as a polar plot. **KrakenSDR** support for instant bearings. **Triangulation** from two or more bearing fixes, with "Open in Maps". |
| **Who** | **ADS-B** aircraft (callsign, ICAO ID, altitude, speed, bearing and distance from you). **APRS** ham stations (callsign and position, plus a "Who?" lookup on QRZ). Saved-station list (import/export CSV) matched by frequency and bearing, so it can tell you which known transmitter you're hearing. |
| **Settings** | Receiver choice, gain, PPM, HF direct sampling, squelch, compass mode, legal notes, and **App updates**: the button shows the latest build number and who sent it, or "Current build" when you're up to date. |

## Hardware

| You have | Use it as |
|---|---|
| Nothing | **Simulator**: fake stations. Turn the phone and the signal strength changes as if you held a directional antenna. Code samples: Morse beacon 14.060 (USB), Morse repeater ID 146.400, sensor bursts 433.920 (AM), APRS 144.390, binary telemetry 152.480, digital voice 155.700. |
| RTL-SDR dongle (RTL-SDR Blog V4 recommended, ~$40) + USB-OTG adapter | **RTL-SDR on USB-OTG**. Install the free *RTL-SDR driver* app (Martin Marinov) from Google Play; Signal Detector opens it automatically. Covers 24 MHz to 1.766 GHz, and 0.5–28.8 MHz with "HF direct sampling" (V3/V4). |
| Dongle on a PC or Raspberry Pi | **rtl_tcp over Wi-Fi**: run `rtl_tcp -a 0.0.0.0` and enter the host's IP. |
| KrakenSDR | Turn on KrakenSDR in the Direction tab and point it at `http://<kraken-ip>:8081/DOA_value.html`. |

For direction finding without a KrakenSDR, use a directional antenna: a cheap Yagi, a log-periodic, or a
home-made tape-measure Yagi for 2 m. Line it up with the phone's top edge (or the camera, in "upright" mode), tap
**Start sweep**, and turn slowly through a full circle.

## Legal

The app **cannot transmit**. It follows US law:

* Spectrum viewing is allowed everywhere.
* Audio is allowed on broadcast, amateur, CB, FRS/GMRS/MURS, marine, aviation, weather, and unencrypted analog
  land-mobile and public-safety channels.
* Audio is **locked** on cellular bands, legacy cordless-phone channels and pager frequencies (ECPA, 47 U.S.C. 302a(d)).
* There is **no decryption**, and none will be added.
* Don't share or act on what you hear on private (non-broadcast) channels (47 U.S.C. 605). Some states restrict
  scanners in vehicles. Other countries have different rules, so check yours.

## Installing and updating

1. Open this repo's **Releases** page on your phone and download the newest `signal-detector-build-N.apk`.
2. Open it and allow "install unknown apps" for your browser when asked.
3. From then on, use **Settings → App updates**. It checks GitHub, shows `Build N · sent by <author>`, and installs
   the update in place. When you're up to date it says **Current build**.

Every push to `main` (or a `claude/**` branch) runs `.github/workflows/android.yml`. The workflow tests the core,
builds a signed APK with `versionCode = build number`, and publishes release `build-N` with the commit author in
the notes.

**Signing:** every build is signed with the same key (`app/signing/update.keystore`), so updates install over the old
version. That key is public in this repo. To use a private key, add the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Switching keys
means uninstalling the app once.

## Project layout

```
core/   Pure Kotlin, unit-tested on any JVM: band plan & legal rules, FFT/peak detection, demodulators,
        rtl_tcp client, simulator, direction finding (sweep, KrakenSDR, triangulation), ADS-B and APRS decoders,
        update logic.
app/    Android UI (Jetpack Compose), audio output, compass, GPS, updater/installer.
```

Run the core tests without the Android SDK: `./gradlew -PcoreOnly :core:test`.
Build the app (needs the Android SDK): `./gradlew :app:assembleDebug`.
