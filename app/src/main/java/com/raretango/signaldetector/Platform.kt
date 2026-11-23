package com.raretango.signaldetector

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.raretango.signaldetector.core.AUDIO_RATE
import com.raretango.signaldetector.core.Geo
import com.raretango.signaldetector.core.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Streams 48 kHz float audio to the speaker / headphones. */
class AudioPlayer {
    private val track: AudioTrack = run {
        val min = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(min, AUDIO_RATE * 4 / 2)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() { if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() }
    fun pause() { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush() } }
    fun setVolume(v: Float) { track.setVolume(v.coerceIn(0f, 1f)) }

    /** Non-blocking so a slow speaker never stalls the radio. */
    fun write(samples: FloatArray) {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING && samples.isNotEmpty()) {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    fun release() { track.release() }
}

/**
 * Phone compass (rotation-vector sensor) reporting TRUE-north heading once a
 * location is known for the magnetic declination.
 *
 * Flat: the top edge of the phone is the pointing direction.
 * Upright: the back camera is the pointing direction (phone held in front of
 * you, e.g. strapped to a Yagi boom).
 */
class Compass(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    val available get() = sensor != null
    @Volatile var upright = false
    @Volatile var declinationDeg = 0f
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)
    private var sx = 0.0
    private var sy = 1.0

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    fun updateDeclination(p: LatLon) {
        declinationDeg = GeomagneticField(p.lat.toFloat(), p.lon.toFloat(), 0f, System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val m = if (upright) {
            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped); remapped
        } else rot
        SensorManager.getOrientation(m, orient)
        val az = Math.toRadians(Math.toDegrees(orient[0].toDouble()) + declinationDeg)
        // Smooth on the unit circle so 359 -> 0 does not jump.
        sx = 0.8 * sx + 0.2 * sin(az); sy = 0.8 * sy + 0.2 * cos(az)
        onHeading(Geo.norm(Math.toDegrees(atan2(sx, sy))).toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
