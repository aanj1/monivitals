//
//  ClipsScreen.kt
//  MoniVitals (Android)
//
//  List saved fingertip readings, show each one's pulse trace + summary, and export it as a
//  CSV bundle via the system share sheet. Cloud-restored readings carry metadata only (the
//  pulse trace stays on the phone that took it) and degrade warmly.
//

package com.monivitals.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.monivitals.model.MetricsRecord
import com.monivitals.model.Session
import com.monivitals.model.SessionStreams
import com.monivitals.model.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsScreen(vm: AppViewModel) {
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    fun reload() { sessions = Store.shared.listSessions() }
    LaunchedEffect(Unit) { reload() }

    val id = selectedId
    if (id != null) {
        SessionDetail(vm, id, onBack = { selectedId = null; reload() })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MvTopBar("Clips") },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sessions.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.List,
                    title = "No readings yet",
                    message = "Your saved readings will appear here. Take one from the Measure tab.",
                )
            }
            sessions.forEach { s ->
                SessionRow(s) { selectedId = s.id }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mvCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(s.subjectCode, style = MaterialTheme.typography.titleSmall)
                LabelChip(s.label.rawValue)
            }
            Text(
                formatSessionDate(s.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sessionDuration(s)?.let { d ->
            Text(
                d,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MV.inkMuted)
    }
}

/** ISO instant → "Aug 10, 2026 · 14:32" in the device time zone; falls back to the raw string. */
private fun formatSessionDate(iso: String): String = try {
    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", java.util.Locale.US)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(iso))
} catch (e: Exception) {
    iso
}

private fun sessionDuration(s: Session): String? {
    val end = s.endedAt ?: return null
    return try {
        val ms = java.time.Duration.between(java.time.Instant.parse(s.startedAt), java.time.Instant.parse(end)).toMillis()
        if (ms < 0) null else Fmt.duration(ms.toDouble())
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetail(vm: AppViewModel, sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<Session?>(null) }
    var streams by remember { mutableStateOf(SessionStreams()) }
    var metrics by remember { mutableStateOf<List<MetricsRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val result = withContext(Dispatchers.IO) {
            Triple(
                Store.shared.getSession(sessionId),
                Store.shared.readSessionStreams(sessionId),
                Store.shared.listMetrics(sessionId),
            )
        }
        session = result.first
        streams = result.second
        metrics = result.third
        loaded = true
    }

    val haptics = rememberHaptics()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MvTopBar(
                title = "Reading",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MV.navy) } },
                actions = {
                    IconButton(onClick = {
                        vm.exportSession(sessionId)?.let { haptics.success(); shareFile(context, it) }
                    }) { Icon(Icons.Filled.Share, "Export reading", tint = MV.magenta) }
                },
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
            session?.let { s ->
                Column(modifier = Modifier.fillMaxWidth().mvCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${s.subjectCode} · ${s.label.rawValue}", style = MaterialTheme.typography.titleSmall)
                    Text("Taken ${formatSessionDate(s.startedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Average HR across the reading, when we have it.
                    val hrs = metrics.mapNotNull { it.hrBpm }
                    if (hrs.isNotEmpty()) {
                        Text("Average heart rate ${Fmt.bpm(hrs.average())} bpm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val hasWaveform = streams.ppg.red.isNotEmpty()
            if (hasWaveform) {
                StaticChart("Pulse", streams.ppg.red.take(4000), MV.ppg)
            } else if (loaded) {
                EmptyState(
                    icon = Icons.Filled.Cloud,
                    title = "Pulse trace stays on the other phone",
                    message = "The full pulse trace for this reading lives on the phone that took it. Its summary is still here.",
                )
            }

            if (metrics.isNotEmpty()) {
                MetricsSummary(metrics)
            }
        }
    }
}

@Composable
private fun MetricsSummary(metrics: List<MetricsRecord>) {
    val hr = metrics.mapNotNull { it.hrBpm }
    val resp = metrics.mapNotNull { it.respRateBpm }
    val hrv = metrics.mapNotNull { it.hrvMs }
    Column(modifier = Modifier.fillMaxWidth().mvCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBadge(Icons.Filled.Favorite, MV.magenta, 22.dp)
            Text("Summary", style = MaterialTheme.typography.titleSmall, color = MV.navy)
        }
        if (hr.isNotEmpty()) LabeledValue("Heart rate", "${Fmt.bpm(hr.average())} bpm (${Fmt.bpm(hr.min())}–${Fmt.bpm(hr.max())})")
        if (resp.isNotEmpty()) LabeledValue("Breathing", "${Fmt.opt(resp.average(), 0)} br/min")
        if (hrv.isNotEmpty()) LabeledValue("HRV (RMSSD)", "${Fmt.opt(hrv.average(), 0)} ms")
    }
}

@Composable
private fun StaticChart(title: String, values: List<Double>, color: Color) {
    val border = MaterialTheme.colorScheme.outline
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (values.size <= 1) {
            Text("No trace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MV.surface, RoundedCornerShape(12.dp))
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .padding(vertical = 4.dp),
        ) {
            var lo = values.min(); var hi = values.max()
            if (hi <= lo) hi = lo + 1
            val margin = (hi - lo) * 0.1
            lo -= margin; hi += margin
            val w = size.width; val h = size.height
            val n = values.size.toDouble()
            fun x(i: Int) = (i / maxOf(1.0, n - 1) * w).toFloat()
            fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
            val path = Path()
            path.moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) path.lineTo(x(i), y(values[i]))
            val fillPath = Path().apply {
                addPath(path); lineTo(x(values.size - 1), h); lineTo(x(0), h); close()
            }
            drawPath(
                fillPath,
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0f)), 0f, h,
                ),
            )
            drawPath(
                path, color = color,
                style = Stroke(
                    width = 1.6f * density,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}
