//
//  DashboardScreen.kt
//  MoniVitals (Android)
//
//  Home: a hero Heart-rate card with a sparkline, tiles for Respiratory rate, HRV, and
//  Movement / Signal quality, and one live pulse waveform card fed from the camera. Values are
//  wellness estimates, never diagnostic.
//

package com.monivitals.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monivitals.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: AppViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.padding(5.dp))
                        Text(
                            "MoniVitals",
                            fontWeight = FontWeight.Bold,
                            color = MV.navy,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = { ConnectionPill(vm.connectionState); Spacer(Modifier.padding(8.dp)) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (vm.liveMetrics == null) {
                Text(
                    "Head to Measure and rest a fingertip over the rear camera to see your heart rate here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .mvCard()
                        .padding(14.dp),
                )
            }

            HeroHeartRateCard(vm)

            metricTiles(vm)

            // Live pulse waveform (the camera signal).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .mvCard()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Live pulse",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MV.navy,
                    )
                    Text(
                        vm.modelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MV.inkMuted,
                    )
                }
                WaveformStrip("Pulse", vm.buffers.ppgRed, windowSec = 6.0, color = MV.ppg, heightDp = 132, framed = false)
            }
        }
    }
}

@Composable
private fun HeroHeartRateCard(vm: AppViewModel) {
    val hrText = Fmt.bpm(vm.liveMetrics?.hrBpm)
    val live = hrText != "—"
    val history = vm.hrHistory
    val reducedMotion = rememberReducedMotion()

    val transition = rememberInfiniteTransition(label = "hero-pulse")
    val heartScale = if (live && !reducedMotion) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "heart-scale",
        ).value
    } else {
        1f
    }

    val spoken = "Heart rate: " + if (live) "$hrText bpm" else "no reading yet"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mvCard()
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.graphicsLayer { scaleX = heartScale; scaleY = heartScale }) {
                IconBadge(icon = Icons.Filled.MonitorHeart, tint = MV.magenta, size = 30.dp)
            }
            Text(
                "Heart rate",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = hrText,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                },
                label = "hero-hr-value",
            ) { v ->
                Text(
                    v,
                    style = TextStyle(
                        fontSize = 40.sp,
                        lineHeight = 50.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = if (v == "—") MaterialTheme.colorScheme.onSurfaceVariant else MV.magenta,
                    maxLines = 1,
                )
            }
            Text(
                " bpm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            HrSparkline(
                values = history,
                color = MV.magenta,
                modifier = Modifier.width(120.dp).height(46.dp),
            )
        }
    }
}

@Composable
private fun HrSparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (values.size <= 1) {
            drawLine(
                color = color.copy(alpha = 0.25f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1.2f * density,
            )
            return@Canvas
        }
        var lo = values.min()
        var hi = values.max()
        if (hi <= lo) hi = lo + 1
        val margin = (hi - lo) * 0.15
        lo -= margin
        hi += margin
        val n = values.size
        fun x(i: Int) = (i / (n - 1).toDouble() * w).toFloat()
        fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
        val path = Path()
        path.moveTo(x(0), y(values[0]))
        for (i in 1 until n) path.lineTo(x(i), y(values[i]))
        val fillPath = Path().apply {
            addPath(path)
            lineTo(x(n - 1), h)
            lineTo(x(0), h)
            close()
        }
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0f)), 0f, h),
        )
        drawPath(
            path,
            color = color,
            style = Stroke(width = 1.8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun metricTiles(vm: AppViewModel) {
    val m = vm.liveMetrics
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Breathing", Fmt.opt(m?.respRateBpm, 0), "br/min", note = "Estimate", icon = Icons.Filled.Air, tint = MV.navy, modifier = Modifier.weight(1f))
            MetricTile("HRV", Fmt.opt(m?.hrvMs, 0), "ms", note = "RMSSD", icon = Icons.Filled.Favorite, tint = MV.magenta, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Signal quality", Fmt.int(m?.contactQuality), "/100", icon = Icons.Filled.Fingerprint, tint = MV.bioz, modifier = Modifier.weight(1f))
            MetricTile("Movement", Fmt.int(m?.movement), "/255", icon = Icons.AutoMirrored.Filled.DirectionsWalk, tint = MV.pink, modifier = Modifier.weight(1f))
        }
    }
}
