//
//  WaveformStrip.kt
//  MoniVitals (Android)
//
//  Live waveform strip drawn with a Compose Canvas on a frame tick. Reads directly from a
//  non-reactive WaveBuffer each frame — high-rate sample data never goes through reactive
//  state, so the UI is not recomposed per sample.
//
//  Used for the live ECG / PPG / BioZ strips.
//  Mirrors ios/MoniVitals/Views/WaveformStrip.swift.
//

package com.monivitals.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monivitals.dsp.DSPConstants

@Composable
fun WaveformStrip(
    title: String,
    buffer: WaveBuffer,
    windowSec: Double = 4.0,
    color: Color = MV.ecg,
    underlay: WaveBuffer? = null,
    underlayColor: Color = MV.inkMuted.copy(alpha = 0.4f),
    heightDp: Int = 120,
    /**
     * When false, the strip drops its own inset background + border so it can nest inside
     * a shared card (the Dashboard's single "Live signals" block) without double-framing.
     */
    framed: Boolean = true,
) {
    var tick by remember { mutableLongStateOf(0L) }
    // One-shot: flips to true once the ring buffer has real samples. Kept as reactive
    // state (unlike `buffer.length`, which is non-reactive) so the warm-up hint actually
    // clears when data arrives.
    var warmedUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { tick = it }
            if (!warmedUp && buffer.length > 1) warmedUp = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // A small colour dot ties the strip's header to its trace hue — clearer at a glance
        // than a lone label, and a stronger visual anchor for the section.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val grid = MaterialTheme.colorScheme.outline
        val baseline = color.copy(alpha = 0.28f)
        val border = MaterialTheme.colorScheme.outline
        // Warm-up state: while the ring buffer is filling, show a subtle hint instead of an
        // empty bordered box so the strip never reads as broken.
        val warming = !warmedUp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                // A faint tinted fill (lighter than the card, distinct from the white page) so
                // the strip reads as an inset "screen" the trace lives on. Skipped when the
                // strip is nested unframed inside a shared card.
                .then(
                    if (framed) {
                        Modifier
                            .background(MV.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, border, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                )
                .clearAndSetSemantics {
                    contentDescription = if (warming) "$title, warming up" else "$title live waveform"
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(heightDp.dp).padding(vertical = 4.dp)) {
                // Reference the frame tick so the canvas redraws each frame.
                tick.let {}
                drawGrid(grid, baseline)
                underlay?.let { draw(it, windowSec, underlayColor, 1f, fill = false) }
                draw(buffer, windowSec, color, 1.8f, fill = true)
            }
            if (warming) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A gently pulsing dot conveys "working, please wait" without a spinner.
                    val pulse = ((tick / 1_000_000L) % 1200L) / 1200f
                    val a = 0.3f + 0.5f * kotlin.math.abs(0.5f - pulse) * 2f
                    Box(Modifier.size(8.dp).background(color.copy(alpha = a), CircleShape))
                    Text(
                        "Warming up…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Faint grid with a slightly stronger centered baseline in the trace's own hue. */
private fun DrawScope.drawGrid(color: Color, baseline: Color) {
    val w = size.width
    val h = size.height
    val rows = 4
    val cols = 6
    for (i in 1 until rows) {
        val y = h * i / rows
        // The mid line is the signal baseline — draw it a touch stronger in the trace hue.
        if (i == rows / 2) {
            drawLine(baseline, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1.2f)
        } else {
            drawLine(color.copy(alpha = 0.6f), start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
        }
    }
    for (i in 1 until cols) {
        val x = w * i / cols
        drawLine(color.copy(alpha = 0.6f), start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
    }
}

private fun DrawScope.draw(
    buffer: WaveBuffer,
    windowSec: Double,
    color: Color,
    lineWidth: Float,
    fill: Boolean,
) {
    val latest = buffer.latestT() ?: return
    val windowUs = windowSec * DSPConstants.usPerS
    val from = latest - windowUs
    val (ts, vs) = buffer.window(from)
    if (ts.size <= 1) return

    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (v in vs) {
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (!(hi > lo)) hi = lo + 1
    val margin = (hi - lo) * 0.1
    lo -= margin
    hi += margin

    val w = size.width
    val h = size.height
    fun x(t: Double): Float = ((t - from) / windowUs * w).toFloat()
    fun y(v: Double): Float = (h - (v - lo) / (hi - lo) * h).toFloat()

    // Trace path.
    val path = Path()
    path.moveTo(x(ts[0]), y(vs[0]))
    for (i in 1 until ts.size) {
        path.lineTo(x(ts[i]), y(vs[i]))
    }

    // Soft gradient beneath the trace for depth (only for the primary trace).
    if (fill) {
        val fillPath = Path().apply {
            addPath(path)
            lineTo(x(ts[ts.size - 1]), h)
            lineTo(x(ts[0]), h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = h,
            ),
        )
    }

    // Anti-aliased trace with rounded caps/joins so the line reads crisp, not jagged.
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = lineWidth * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
