package com.raretango.signaldetector.core

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A receive-only IQ source. There is intentionally no transmit API anywhere in
 * this project.
 */
interface IqSource : AutoCloseable {
    val name: String
    val minHz: Double
    val maxHz: Double
    var centerHz: Double
    var sampleRate: Double
    fun tune(freqHz: Double)
    fun applySampleRate(rate: Double)
    /** Gain in dB, or null for automatic gain. */
    fun setGain(gainDb: Double?)
    fun setPpm(ppm: Int) {}
    /** HF direct sampling (RTL-SDR v3/v4): 0 = off, 1 = I branch, 2 = Q branch. */
    fun setDirectSampling(mode: Int) {}
    /** Blocks until [n] complex samples are available. */
    fun read(n: Int): Iq
}

// -------------------------------------------------------------- rtl_tcp ---

/**
 * Client for the rtl_tcp protocol. On Android this talks to the
 * "RTL-SDR driver" app (rtl_tcp_andro) which owns the USB dongle and serves
 * rtl_tcp on 127.0.0.1; it also works with rtl_tcp on a PC or Raspberry Pi.
 */
class RtlTcpSource(
    val host: String = "127.0.0.1",
    val port: Int = 1234,
    sampleRate: Double = 2.048e6,
    centerHz: Double = 100e6,
    connectTimeoutMs: Int = 5000,
) : IqSource {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: OutputStream
    val tunerType: Int
    val gainCount: Int
    override val name: String
    override val minHz = 24e6
    override val maxHz = 1.766e9
    override var centerHz = centerHz
    override var sampleRate = sampleRate
    private var buf = ByteArray(0)

    init {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.tcpNoDelay = true
        socket.receiveBufferSize = 1 shl 20
        input = DataInputStream(socket.getInputStream().buffered(1 shl 18))
        output = socket.getOutputStream()
        val hdr = ByteArray(12)
        input.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr)
        val magic = ByteArray(4).also { bb.get(it) }
        if (String(magic) != "RTL0") throw IOException("Not an rtl_tcp server at $host:$port")
        tunerType = bb.int
        gainCount = bb.int
        name = "RTL-SDR (${TUNERS.getOrElse(tunerType) { "tuner $tunerType" }}) @ $host:$port"
        applySampleRate(sampleRate)
        tune(centerHz)
        setGain(null)
    }

    private fun command(cmd: Int, value: Int) {
        synchronized(output) { output.write(encodeCommand(cmd, value)); output.flush() }
    }

    override fun tune(freqHz: Double) { centerHz = freqHz; command(0x01, freqHz.toLong().toInt()) }
    override fun applySampleRate(rate: Double) { sampleRate = rate; command(0x02, rate.toInt()) }
    override fun setGain(gainDb: Double?) {
        if (gainDb == null) {
            command(0x03, 0); command(0x08, 1)
        } else {
            command(0x03, 1); command(0x08, 0); command(0x04, (gainDb * 10).toInt())
        }
    }
    override fun setPpm(ppm: Int) = command(0x05, ppm)
    override fun setDirectSampling(mode: Int) = command(0x09, mode)

    override fun read(n: Int): Iq {
        if (buf.size != 2 * n) buf = ByteArray(2 * n)
        input.readFully(buf)
        return convertU8(buf)
    }

    override fun close() = socket.close()

    companion object {
        val TUNERS = listOf("unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        fun encodeCommand(cmd: Int, value: Int): ByteArray =
            ByteBuffer.allocate(5).put(cmd.toByte()).putInt(value).array()

        private val LUT = FloatArray(256) { (it - 127.5f) / 127.5f }

        fun convertU8(raw: ByteArray): Iq {
            val n = raw.size / 2
            val iq = Iq(n)
            for (i in 0 until n) {
                iq.re[i] = LUT[raw[2 * i].toInt() and 0xFF]
                iq.im[i] = LUT[raw[2 * i + 1].toInt() and 0xFF]
            }
            return iq
        }
    }
}

// ------------------------------------------------------------ simulator ---

data class SimEmitter(
    val freqHz: Double,
    val mode: Mode,
    val powerDb: Double,
    /** True bearing from the receiver, degrees clockwise from north. */
    val bearingDeg: Double,
    val toneHz: Double,
    val label: String,
)

/**
 * Generates realistic test signals so the whole app can be tried without
 * hardware. Signal strength also depends on [antennaHeadingDeg] (set it from
 * the phone compass) as if a directional antenna were attached, so the
 * direction-finding sweep produces real bearings.
 */
class SimulatedSource(
    override var sampleRate: Double = 2.048e6,
    override var centerHz: Double = 96e6,
    val emitters: List<SimEmitter> = DEFAULT,
    seed: Int = 1,
    private val realtime: Boolean = true,
) : IqSource {
    override val name = "Simulator (no hardware)"
    override val minHz = 100e3
    override val maxHz = 1.766e9
    @Volatile var antennaHeadingDeg: Double? = null
    private val rng = Random(seed)
    private var sampleIndex = 0L
    private val phases = DoubleArray(emitters.size)

    override fun tune(freqHz: Double) { centerHz = freqHz }
    override fun applySampleRate(rate: Double) { sampleRate = rate }
    override fun setGain(gainDb: Double?) {}

    override fun read(n: Int): Iq {
        val start = System.nanoTime()
        val fs = sampleRate
        val iq = Iq(n)
        val noise = Math.pow(10.0, -75 / 20.0).toFloat()
        for (i in 0 until n) {
            iq.re[i] = (rng.nextFloat() - 0.5f) * 2 * noise
            iq.im[i] = (rng.nextFloat() - 0.5f) * 2 * noise
        }
        val heading = antennaHeadingDeg
        emitters.forEachIndexed { e, em ->
            val off = em.freqHz - centerHz
            if (kotlin.math.abs(off) > fs * 0.45) return@forEachIndexed
            var amp = Math.pow(10.0, em.powerDb / 20)
            if (heading != null) {
                // Cardioid-ish Yagi pattern: up to 20 dB front-to-back.
                val d = Math.toRadians(em.bearingDeg - heading)
                val gainDb = -10 * (1 - cos(d))
                amp *= Math.pow(10.0, gainDb / 20)
            }
            var ph = phases[e]
            for (i in 0 until n) {
                val t = (sampleIndex + i) / fs
                val env = 0.6 + 0.4 * sin(2 * PI * 2.5 * t)
                val audio = env * sin(2 * PI * em.toneHz * t)
                var a = amp
                val inst = when (em.mode) {
                    Mode.WFM -> { ph += 2 * PI * 50e3 * audio / fs; 0.0 }
                    Mode.NFM -> { ph += 2 * PI * 3e3 * audio / fs; 0.0 }
                    Mode.AM -> { a *= (1 + 0.7 * audio); 0.0 }
                    Mode.USB -> { a *= 0.8 * env; 2 * PI * em.toneHz * t }
                    Mode.LSB -> { a *= 0.8 * env; -2 * PI * em.toneHz * t }
                    Mode.NONE -> 0.0
                }
                val total = ph + inst + 2 * PI * off * t
                iq.re[i] += (a * cos(total)).toFloat()
                iq.im[i] += (a * sin(total)).toFloat()
            }
            phases[e] = ph % (2 * PI)
        }
        sampleIndex += n
        if (realtime) {
            val wantNs = (n / fs * 1e9).toLong()
            val spent = System.nanoTime() - start
            if (spent < wantNs) Thread.sleep((wantNs - spent) / 1_000_000)
        }
        return iq
    }

    override fun close() {}

    companion object {
        val DEFAULT = listOf(
            SimEmitter(88.5e6, Mode.WFM, -20.0, 45.0, 440.0, "Sim FM station A"),
            SimEmitter(96.1e6, Mode.WFM, -28.0, 200.0, 660.0, "Sim FM station B"),
            SimEmitter(96.9e6, Mode.WFM, -35.0, 310.0, 550.0, "Sim FM station C"),
            SimEmitter(119.1e6, Mode.AM, -40.0, 120.0, 800.0, "Sim ATC tower"),
            SimEmitter(146.52e6, Mode.NFM, -38.0, 75.0, 1000.0, "Sim 2 m simplex"),
            SimEmitter(162.55e6, Mode.NFM, -32.0, 260.0, 1050.0, "Sim NOAA weather"),
            SimEmitter(462.5625e6, Mode.NFM, -42.0, 170.0, 900.0, "Sim FRS ch 1"),
            SimEmitter(851.0e6, Mode.NFM, -30.0, 20.0, 700.0, "Sim cellular (restricted)"),
        )
    }
}
