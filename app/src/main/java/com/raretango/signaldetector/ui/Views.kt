package com.raretango.signaldetector.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.raretango.signaldetector.Waterfall
import com.raretango.signaldetector.core.Geo
import com.raretango.signaldetector.core.SpectrumFrame
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val Accent = Color(0xFF38BDF8)
val Amber = Color(0xFFF59E0B)
val Danger = Color(0xFFF87171)
val Good = Color(0xFF4ADE80)
val Muted = Color(0xFF94A3B8)
val Panel = Color(0xFF111A2E)
val Grid = Color(0xFF1E293B)

fun mhz(hz: Double, decimals: Int = 4) = "%.${decimals}f MHz".format(hz / 1e6)

private fun textPaint(color: Color, sizePx: Float) = Paint().apply {
    this.color = color.toArgb(); textSize = sizePx; isAntiAlias = true
}

/** Live spectrum (top) and waterfall history (bottom). Tap anywhere to tune. */
@Composable
fun SpectrumWaterfall(
    frame: SpectrumFrame?,
    waterfall: Waterfall,
    onTap: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(320.dp)
            .pointerInput(frame?.centerHz, frame?.sampleRate) {
                detectTapGestures { pos ->
                    val f = frame ?: return@detectTapGestures
                    onTap(f.centerHz + (pos.x / size.width - 0.5) * f.sampleRate)
                }
            },
    ) {
        drawRect(Panel)
        val specH = size.height * 0.45f
        if (frame == null) {
            drawContext.canvas.nativeCanvas.drawText(
                "No receiver connected - open Settings", 24f, specH / 2, textPaint(Muted, 34f),
            )
            return@Canvas
        }
        val top = frame.noiseFloorDb + 60
        val bottom = frame.noiseFloorDb - 10
        fun y(db: Float) = ((top - db) / (top - bottom)).coerceIn(0f, 1f) * specH

        // grid: every 10 dB
        var g = bottom
        while (g <= top) { drawLine(Grid, Offset(0f, y(g)), Offset(size.width, y(g))); g += 10 }

        // spectrum trace (max-hold per pixel)
        val n = frame.psd.size
        val w = size.width.toInt().coerceAtLeast(1)
        val path = Path()
        for (x in 0 until w) {
            val a = x * n / w; val b = max(a + 1, (x + 1) * n / w)
            var m = -200f
            for (i in a until min(b, n)) m = max(m, frame.psd[i])
            if (x == 0) path.moveTo(0f, y(m)) else path.lineTo(x.toFloat(), y(m))
        }
        drawPath(path, Accent, style = Stroke(2f))
        drawLine(Muted.copy(alpha = 0.5f), Offset(0f, y(frame.noiseFloorDb)), Offset(size.width, y(frame.noiseFloorDb)))

        val label = textPaint(Muted, 26f)
        for (p in frame.peaks) {
            val x = ((p.freqHz - frame.centerHz) / frame.sampleRate + 0.5).toFloat() * size.width
            drawCircle(Amber, 5f, Offset(x, y(p.powerDb)))
        }
        // tuned marker
        val tx = ((frame.tunedHz - frame.centerHz) / frame.sampleRate + 0.5).toFloat() * size.width
        drawLine(Danger, Offset(tx, 0f), Offset(tx, size.height), strokeWidth = 2f)

        // axis labels
        val nc = drawContext.canvas.nativeCanvas
        nc.drawText(mhz(frame.centerHz - frame.sampleRate / 2, 3), 6f, specH - 6, label)
        val right = mhz(frame.centerHz + frame.sampleRate / 2, 3)
        nc.drawText(right, size.width - label.measureText(right) - 6, specH - 6, label)
        nc.drawText("%.0f dB".format(top), 6f, 26f, label)

        drawWaterfall(waterfall, specH)
    }
}

private fun DrawScope.drawWaterfall(wf: Waterfall, top: Float) {
    val bmp = wf.render()
    drawImage(
        bmp.asImageBitmap(),
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset(0, top.toInt()),
        dstSize = IntSize(size.width.toInt(), (size.height - top).toInt()),
    )
}

/**
 * Compass rose that rotates with the phone so N/E/S/W point at the real
 * directions; the amber arrow points at the signal.
 */
@Composable
fun CompassRose(headingDeg: Float?, bearingDeg: Double?, modifier: Modifier = Modifier, secondaryBearings: List<Double> = emptyList()) {
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2 * 0.9f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Panel, r + 12, c)
        drawCircle(Grid, r, c, style = Stroke(3f))
        val h = headingDeg ?: 0f
        rotate(-h, c) {
            val p = textPaint(Muted, 34f)
            for ((i, name) in listOf("N", "E", "S", "W").withIndex()) {
                val a = Math.toRadians(i * 90.0)
                val x = c.x + (r - 30) * sin(a).toFloat(); val yy = c.y - (r - 30) * cos(a).toFloat()
                p.color = (if (name == "N") Danger else Muted).toArgb()
                drawContext.canvas.nativeCanvas.drawText(name, x - p.measureText(name) / 2, yy + 12, p)
            }
            for (d in 0 until 360 step 15) {
                val a = Math.toRadians(d.toDouble())
                val inner = if (d % 45 == 0) r - 14 else r - 7
                drawLine(
                    Muted, Offset(c.x + inner * sin(a).toFloat(), c.y - inner * cos(a).toFloat()),
                    Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat()), strokeWidth = 2f,
                )
            }
            for (b in secondaryBearings) arrow(c, r * 0.8f, b, Accent.copy(alpha = 0.6f), 4f)
            if (bearingDeg != null) arrow(c, r * 0.85f, bearingDeg, Amber, 10f)
        }
        // phone pointing direction
        drawLine(Color.White, Offset(c.x, c.y - r - 10), Offset(c.x, c.y - r + 18), strokeWidth = 6f)
    }
}

private fun DrawScope.arrow(c: Offset, len: Float, deg: Double, color: Color, width: Float) {
    val a = Math.toRadians(deg)
    val tip = Offset(c.x + len * sin(a).toFloat(), c.y - len * cos(a).toFloat())
    drawLine(color, c, tip, strokeWidth = width)
    val head = len * 0.15f
    for (s in listOf(-25.0, 25.0)) {
        val b = Math.toRadians(deg + 180 + s)
        drawLine(color, tip, Offset(tip.x + head * sin(b).toFloat(), tip.y - head * cos(b).toFloat()), strokeWidth = width)
    }
    drawCircle(color, width, c)
}

/** Polar plot of signal level vs compass heading from a sweep. */
@Composable
fun PolarPattern(pattern: FloatArray, bearingDeg: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2 * 0.9f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Panel, r, c)
        for (k in 1..3) drawCircle(Grid, r * k / 3, c, style = Stroke(2f))
        val valid = pattern.filter { !it.isNaN() }
        if (valid.isEmpty()) return@Canvas
        val hi = valid.max(); val lo = min(valid.min(), hi - 10)
        val step = 360.0 / pattern.size
        for (i in pattern.indices) {
            val v = pattern[i]
            if (v.isNaN()) continue
            val rr = r * ((v - lo) / (hi - lo)).coerceIn(0.05f, 1f)
            val a0 = Math.toRadians(i * step); val a1 = Math.toRadians((i + 1) * step)
            val path = Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x + rr * sin(a0).toFloat(), c.y - rr * cos(a0).toFloat())
                lineTo(c.x + rr * sin(a1).toFloat(), c.y - rr * cos(a1).toFloat())
                close()
            }
            drawPath(path, Accent.copy(alpha = 0.55f))
        }
        if (bearingDeg != null) arrow(c, r, bearingDeg, Amber, 6f)
        drawContext.canvas.nativeCanvas.drawText("N", c.x - 8, c.y - r + 28, textPaint(Danger, 30f))
    }
}

/** Horizontal signal-strength bar (S-meter style). */
@Composable
fun SignalMeter(levelDb: Float, floorDb: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        drawRect(Grid)
        val frac = ((levelDb - floorDb) / 50f).coerceIn(0f, 1f)
        val col = when {
            frac > 0.6f -> Good
            frac > 0.25f -> Amber
            else -> Muted
        }
        drawRect(col, size = size.copy(width = size.width * frac))
    }
}

fun bearingText(deg: Double) = "%.0f° %s".format(deg, Geo.compassPoint(deg))
