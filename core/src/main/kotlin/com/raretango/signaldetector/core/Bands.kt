package com.raretango.signaldetector.core

/**
 * Band plan with legality rules for a RECEIVE-ONLY scanner.
 *
 * This app never transmits. Receiving is lawful in most places when the signal
 * is "readily accessible to the general public", with important exceptions.
 * The rules here follow United States law (47 U.S.C. 302a(d), 47 U.S.C. 605,
 * and the ECPA, 18 U.S.C. 2510-2511):
 *
 *  - Viewing the spectrum (signal energy only) is allowed on every band.
 *  - Audio is allowed on broadcast, amateur, CB, FRS/GMRS/MURS, marine,
 *    aviation, weather and unencrypted analog land-mobile/public-safety voice.
 *  - Audio is BLOCKED on cellular bands, legacy cordless-phone frequencies and
 *    pager frequencies, because intercepting those is prohibited.
 *  - There is no decryption capability and none will be added.
 *  - Even where listening is legal, 47 U.S.C. 605 forbids divulging or using the
 *    content of non-broadcast traffic. Some states restrict scanners in
 *    vehicles. Other countries differ (the UK, for example, only permits
 *    listening to broadcast and amateur traffic).
 */
enum class Mode(val label: String, val bandwidthHz: Double) {
    WFM("WFM", 200e3),
    NFM("NFM", 12.5e3),
    AM("AM", 10e3),
    USB("USB", 3e3),
    LSB("LSB", 3e3),
    NONE("Data", 0.0),
}

data class Band(
    val name: String,
    val startHz: Double,
    val endHz: Double,
    val service: String,
    val mode: Mode,
    val audioAllowed: Boolean = true,
    val note: String = "",
    val stepHz: Double = 0.0,
) {
    val centerHz get() = (startHz + endHz) / 2
    val widthHz get() = endHz - startHz
    operator fun contains(freqHz: Double) = freqHz in startHz..endHz
}

data class AudioPermission(val allowed: Boolean, val reason: String = "")

object BandPlan {
    private const val k = 1e3
    private const val M = 1e6
    private const val G = 1e9

    private const val CELL_NOTE =
        "Cellular / mobile-phone band: intercepting calls is illegal (ECPA, 47 U.S.C. 302a(d)). Spectrum view only."

    val listenable: List<Band> = listOf(
        Band("LW broadcast", 148.5 * k, 283.5 * k, "Broadcast (Europe/Africa)", Mode.AM, stepHz = 9 * k),
        Band("NDB beacons", 190 * k, 535 * k, "Aviation navigation beacons (Morse IDs)", Mode.USB),
        Band("AM broadcast", 530 * k, 1700 * k, "Broadcast radio", Mode.AM, stepHz = 10 * k),
        Band("160 m amateur", 1.8 * M, 2.0 * M, "Amateur radio", Mode.LSB),
        Band("80 m amateur", 3.5 * M, 4.0 * M, "Amateur radio", Mode.LSB),
        Band("Shortwave 49 m", 5.9 * M, 6.2 * M, "International broadcast", Mode.AM, stepHz = 5 * k),
        Band("40 m amateur", 7.0 * M, 7.3 * M, "Amateur radio", Mode.LSB),
        Band("Shortwave 31 m", 9.4 * M, 9.9 * M, "International broadcast", Mode.AM, stepHz = 5 * k),
        Band("WWV 10 MHz", 9.99 * M, 10.01 * M, "NIST time signals (Fort Collins, CO)", Mode.AM),
        Band("20 m amateur", 14.0 * M, 14.35 * M, "Amateur radio", Mode.USB),
        Band("Shortwave 19 m", 15.1 * M, 15.8 * M, "International broadcast", Mode.AM, stepHz = 5 * k),
        Band("CB radio", 26.965 * M, 27.405 * M, "Citizens Band", Mode.AM, stepHz = 10 * k),
        Band("10 m amateur", 28.0 * M, 29.7 * M, "Amateur radio", Mode.USB),
        Band("6 m amateur", 50 * M, 54 * M, "Amateur radio", Mode.USB),
        Band("FM broadcast", 87.5 * M, 108 * M, "Broadcast radio", Mode.WFM, stepHz = 100 * k),
        Band("Airband (civil)", 108 * M, 137 * M, "Aviation voice (ATC, pilots)", Mode.AM, stepHz = 25 * k),
        Band("NOAA APT satellites", 137 * M, 138 * M, "Weather satellite imagery", Mode.WFM),
        Band("2 m amateur", 144 * M, 148 * M, "Amateur radio (APRS on 144.390)", Mode.NFM, stepHz = 5 * k),
        Band("VHF land mobile", 150.8 * M, 162.0 * M, "Business / public safety (unencrypted analog only)", Mode.NFM, stepHz = 12.5 * k),
        Band("MURS", 151.82 * M, 154.6 * M, "Multi-Use Radio Service", Mode.NFM, stepHz = 12.5 * k),
        Band("Marine VHF", 156 * M, 162.025 * M, "Marine voice & AIS", Mode.NFM, stepHz = 25 * k),
        Band("NOAA Weather Radio", 162.4 * M, 162.55 * M, "Weather broadcasts", Mode.NFM, stepHz = 25 * k),
        Band("1.25 m amateur", 222 * M, 225 * M, "Amateur radio", Mode.NFM),
        Band("Military airband", 225 * M, 400 * M, "Military aviation voice", Mode.AM, stepHz = 25 * k),
        Band("70 cm amateur", 420 * M, 450 * M, "Amateur radio", Mode.NFM, stepHz = 12.5 * k),
        Band("ISM 433", 433.05 * M, 434.79 * M, "Weather stations, key fobs, sensors", Mode.NFM),
        Band("UHF land mobile", 450 * M, 462.5 * M, "Business / public safety (unencrypted analog only)", Mode.NFM, stepHz = 12.5 * k),
        Band("FRS / GMRS", 462.55 * M, 467.725 * M, "Family / General Mobile Radio", Mode.NFM, stepHz = 12.5 * k),
        Band("UHF TV", 470 * M, 608 * M, "Digital TV broadcast (view only)", Mode.NONE),
        Band("ISM 868 (EU)", 863 * M, 870 * M, "LoRa / sensors", Mode.NFM),
        Band("ISM 900 / 33 cm", 902 * M, 928 * M, "LoRa, utility meters, amateur", Mode.NFM),
        Band("ADS-B 1090", 1089 * M, 1091 * M, "Aircraft transponders (decoded on the Who tab)", Mode.NONE),
        Band("23 cm amateur", 1240 * M, 1300 * M, "Amateur radio", Mode.NFM),
        Band("L-band satellite", 1525 * M, 1559 * M, "Satellite downlinks (view only)", Mode.NONE),
        Band("GPS L1", 1574 * M, 1577 * M, "Navigation satellites (view only)", Mode.NONE),
    )

    val restricted: List<Band> = listOf(
        Band("Cordless phones 43-50 MHz", 43.72 * M, 46.98 * M, "Legacy cordless phones", Mode.NONE, false,
            "Intercepting cordless-phone calls is illegal (ECPA). Spectrum view only."),
        Band("Cellular 600", 617 * M, 698 * M, "Mobile phone (LTE/5G)", Mode.NONE, false, CELL_NOTE),
        Band("Cellular 700", 698 * M, 806 * M, "Mobile phone (LTE/5G)", Mode.NONE, false, CELL_NOTE),
        Band("Cellular 850", 824 * M, 894 * M, "Mobile phone", Mode.NONE, false, CELL_NOTE),
        Band("Paging 929-932", 929 * M, 932 * M, "Display pagers", Mode.NONE, false,
            "Pager message content is protected by the ECPA. Spectrum view only."),
        Band("Cellular AWS uplink", 1710 * M, 1780 * M, "Mobile phone", Mode.NONE, false, CELL_NOTE),
        Band("Cellular PCS 1900", 1850 * M, 1995 * M, "Mobile phone / DECT 6.0 cordless", Mode.NONE, false, CELL_NOTE),
    )

    val all: List<Band> get() = (listenable + restricted).sortedBy { it.startHz }

    /** Most specific band covering [freqHz]; restricted bands always win. */
    fun bandFor(freqHz: Double): Band? {
        restricted.firstOrNull { freqHz in it }?.let { return it }
        return listenable.filter { freqHz in it }.minByOrNull { it.widthHz }
    }

    /** Whether audio may be demodulated for a channel at [freqHz] of [bandwidthHz]. */
    fun audioPermission(freqHz: Double, bandwidthHz: Double = 0.0): AudioPermission {
        val lo = freqHz - bandwidthHz / 2
        val hi = freqHz + bandwidthHz / 2
        restricted.firstOrNull { lo <= it.endHz && hi >= it.startHz }?.let {
            return AudioPermission(false, it.note)
        }
        val b = bandFor(freqHz)
        if (b != null && b.mode == Mode.NONE) {
            return AudioPermission(false, "${b.name}: digital/data signal with no audio to play.")
        }
        return AudioPermission(true)
    }

    fun defaultMode(freqHz: Double): Mode {
        val b = bandFor(freqHz) ?: return if (freqHz < 30e6) Mode.AM else Mode.NFM
        return if (b.mode == Mode.NONE) Mode.NFM else b.mode
    }
}

/** Well-known frequencies that identify the service even without a location. */
data class KnownFrequency(val freqHz: Double, val name: String, val toleranceHz: Double = 5e3)

object KnownFrequencies {
    val builtIn = listOf(
        KnownFrequency(2.5e6, "WWV/WWVH time signal"),
        KnownFrequency(5.0e6, "WWV/WWVH time signal"),
        KnownFrequency(10.0e6, "WWV/WWVH time signal"),
        KnownFrequency(15.0e6, "WWV/WWVH time signal"),
        KnownFrequency(20.0e6, "WWV time signal"),
        KnownFrequency(121.5e6, "Aviation emergency (guard)"),
        KnownFrequency(144.39e6, "APRS (North America)"),
        KnownFrequency(144.8e6, "APRS (Europe)"),
        KnownFrequency(145.8e6, "ISS downlink"),
        KnownFrequency(146.52e6, "2 m national simplex calling"),
        KnownFrequency(156.8e6, "Marine Ch 16 distress/calling"),
        KnownFrequency(161.975e6, "AIS ship tracking A"),
        KnownFrequency(162.025e6, "AIS ship tracking B"),
        KnownFrequency(162.4e6, "NOAA Weather Radio WX2"),
        KnownFrequency(162.425e6, "NOAA Weather Radio WX4"),
        KnownFrequency(162.45e6, "NOAA Weather Radio WX5"),
        KnownFrequency(162.475e6, "NOAA Weather Radio WX3"),
        KnownFrequency(162.5e6, "NOAA Weather Radio WX6"),
        KnownFrequency(162.525e6, "NOAA Weather Radio WX7"),
        KnownFrequency(162.55e6, "NOAA Weather Radio WX1"),
        KnownFrequency(446.0e6, "70 cm national simplex calling"),
        KnownFrequency(462.5625e6, "FRS/GMRS ch 1"),
        KnownFrequency(462.5875e6, "FRS/GMRS ch 2"),
        KnownFrequency(462.6125e6, "FRS/GMRS ch 3"),
        KnownFrequency(462.6375e6, "FRS/GMRS ch 4"),
        KnownFrequency(462.6625e6, "FRS/GMRS ch 5"),
        KnownFrequency(462.6875e6, "FRS/GMRS ch 6"),
        KnownFrequency(462.7125e6, "FRS/GMRS ch 7"),
        KnownFrequency(1090e6, "ADS-B aircraft transponders", 1e6),
    )

    fun match(freqHz: Double): KnownFrequency? =
        builtIn.filter { kotlin.math.abs(it.freqHz - freqHz) <= it.toleranceHz }
            .minByOrNull { kotlin.math.abs(it.freqHz - freqHz) }
}
