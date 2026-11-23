package com.raretango.signaldetector

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Scrolling waterfall: rows are added on the radio thread, rendered on the UI thread. */
class Waterfall(val width: Int, val height: Int) {
    private val pixels = IntArray(width * height) { 0xFF0B1220.toInt() }
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var dirty = true

    fun addRow(psd: FloatArray, floorDb: Float) {
        val n = psd.size
        val row = IntArray(width)
        for (x in 0 until width) {
            val a = x * n / width; val b = max(a + 1, (x + 1) * n / width)
            var m = -200f
            for (i in a until min(b, n)) m = max(m, psd[i])
            val t = ((m - (floorDb - 3)) / 45f).coerceIn(0f, 1f)
            row[x] = PALETTE[(t * 255).toInt()]
        }
        synchronized(pixels) {
            System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
            System.arraycopy(row, 0, pixels, 0, width)
            dirty = true
        }
    }

    fun render(): Bitmap {
        synchronized(pixels) {
            if (dirty) { bitmap.setPixels(pixels, 0, width, 0, 0, width, height); dirty = false }
        }
        return bitmap
    }

    companion object {
        // dark navy -> blue -> cyan -> yellow -> red
        private val STOPS = listOf(
            0.0f to 0x0B1220, 0.3f to 0x1E3A8A, 0.55f to 0x06B6D4, 0.75f to 0xFACC15, 1.0f to 0xEF4444,
        )
        val PALETTE = IntArray(256) { i ->
            val t = i / 255f
            val k = STOPS.indexOfLast { it.first <= t }.coerceAtMost(STOPS.size - 2)
            val (t0, c0) = STOPS[k]; val (t1, c1) = STOPS[k + 1]
            val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            val r = (ch(c0, 16) + (ch(c1, 16) - ch(c0, 16)) * f).toInt()
            val g = (ch(c0, 8) + (ch(c1, 8) - ch(c0, 8)) * f).toInt()
            val b = (ch(c0, 0) + (ch(c1, 0) - ch(c0, 0)) * f).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
