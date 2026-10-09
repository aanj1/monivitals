//
//  MeasureScreen.kt
//  MoniVitals (Android)
//
//  The live measurement screen. Asks for camera permission with a warm rationale, turns the
//  torch on, and guides the user to rest a fingertip over the rear camera. Shows the live pulse
//  trace, the current readings, and lets them save a reading. The camera runs only while this
//  screen is on and is released when leaving.
//

package com.monivitals.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.monivitals.model.SessionLabel
import com.monivitals.model.Subject
import com.monivitals.source.ConnectionState
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeasureScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()

    var label by remember { mutableStateOf(SessionLabel.REST) }
    var notes by remember { mutableStateOf("") }
    var showSubjectSheet by remember { mutableStateOf(false) }

    // Track camera permission. On grant, the measurement begins via the effect below.
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        vm.setCameraPermission(ok)
    }

    LaunchedEffect(Unit) { vm.setCameraPermission(granted) }

    // Bind the camera only while this screen is composed and the permission is granted (it releases
    // the torch on leave).
    DisposableEffect(granted, lifecycleOwner) {
        if (granted) vm.beginMeasurement(lifecycleOwner)
        onDispose { vm.endMeasurement() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MvTopBar("Measure", actions = { ConnectionPill(vm.connectionState) }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!granted) {
                PermissionCard { permissionLauncher.launch(Manifest.permission.CAMERA) }
                return@Column
            }
            when (vm.connectionState) {
                ConnectionState.UNSUPPORTED -> UnsupportedCard(vm.connectionMessage)
                else -> {
                    GuidanceCard(vm)
                    LivePulseCard(vm)
                    ReadingReadout(vm)
                    SaveCard(vm, label, notes, onLabel = { label = it }, onNotes = { notes = it }, onPickSubject = { showSubjectSheet = true })
                }
            }
        }
    }

    if (showSubjectSheet) {
        SubjectPickerDialog(vm, onDismiss = { showSubjectSheet = false })
    }
}

/** The live pulse-trace card fed from the camera. */
@Composable
private fun LivePulseCard(vm: AppViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().mvCard().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WaveformStrip("Pulse", vm.buffers.ppgRed, windowSec = 6.0, color = MV.ppg, heightDp = 150, framed = false)
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().mvCard().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = Icons.Filled.Fingerprint, tint = MV.magenta, size = 44.dp)
        Text("Measure from your fingertip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MV.navy)
        Text(
            "MoniVitals reads your heart rate from tiny colour changes in your fingertip. Allow the " +
                "camera so it can gently light your finger and take a reading — nothing is photographed " +
                "or saved as an image.",
            style = MaterialTheme.typography.bodyMedium,
            color = MV.inkMuted,
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Allow camera")
        }
    }
}

@Composable
private fun UnsupportedCard(message: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().mvCard().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon = Icons.Filled.Fingerprint, tint = MV.inkMuted, size = 40.dp)
        Text("Camera not ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MV.navy)
        Text(
            message ?: "MoniVitals needs a real phone camera to take a reading. Try again on a phone with a rear camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MV.inkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The big "cover the camera / hold still" guidance state, driven by signal quality. */
@Composable
private fun GuidanceCard(vm: AppViewModel) {
    val quality = vm.liveMetrics?.contactQuality ?: 0
    val hasReading = vm.liveMetrics?.hrBpm != null
    val settling = quality >= 45 && !hasReading

    val (title, body) = when {
        hasReading -> "Reading you now" to "Hold still and keep your fingertip resting gently over the camera."
        settling -> "Getting your reading…" to "Nice — keep your fingertip still over the camera for a few seconds."
        quality in 20..44 -> "Almost there" to "Rest your fingertip flat over the rear camera so it fully covers the light."
        else -> "Rest a fingertip on the camera" to "Cover the rear camera and its light gently with the pad of your finger, then hold still."
    }

    val reducedMotion = rememberReducedMotion()
    val pulse = rememberInfiniteTransition(label = "guide-pulse")
    val scale = if (!reducedMotion && (settling || hasReading)) {
        pulse.animateFloat(
            initialValue = 1f, targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "guide-scale",
        ).value
    } else 1f

    Column(
        modifier = Modifier.fillMaxWidth().background(MV.brandGradient, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(52.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ReadingReadout(vm: AppViewModel) {
    val m = vm.liveMetrics
    Column(
        modifier = Modifier.fillMaxWidth().mvCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Heart rate", style = MaterialTheme.typography.labelMedium, color = MV.inkMuted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Fmt.bpm(m?.hrBpm),
                        style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
                        color = if (m?.hrBpm == null) MV.inkMuted else MV.magenta,
                    )
                    Text(" bpm", style = MaterialTheme.typography.labelMedium, color = MV.inkMuted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            SignalDots(m?.contactQuality ?: 0)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SmallStat("Breathing", Fmt.opt(m?.respRateBpm, 0), "br/min")
            SmallStat("HRV", Fmt.opt(m?.hrvMs, 0), "ms")
            SmallStat("Movement", Fmt.int(m?.movement), "/255")
        }
    }
}

@Composable
private fun SmallStat(title: String, value: String, unit: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MV.inkMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MV.navy)
            Text(" $unit", style = MaterialTheme.typography.labelSmall, color = MV.inkMuted)
        }
    }
}

@Composable
private fun SignalDots(quality: Int) {
    val active = (quality / 25).coerceIn(0, 4)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal", style = MaterialTheme.typography.labelSmall, color = MV.inkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(9.dp).background(
                        if (i < active) MV.good else MV.separator,
                        CircleShape,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveCard(
    vm: AppViewModel,
    label: SessionLabel,
    notes: String,
    onLabel: (SessionLabel) -> Unit,
    onNotes: (String) -> Unit,
    onPickSubject: () -> Unit,
) {
    val haptics = rememberHaptics()
    SectionCard("Save this reading") {
        OutlinedButton(onClick = onPickSubject, modifier = Modifier.fillMaxWidth()) {
            Text("For: ${vm.currentSubjectCode ?: "choose someone"}")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionLabel.allCases.forEach { l ->
                FilterChip(
                    selected = label == l,
                    onClick = { if (vm.recording == null) onLabel(l) },
                    label = { Text(l.rawValue) },
                    enabled = vm.recording == null,
                )
            }
        }
        OutlinedTextField(
            value = notes,
            onValueChange = onNotes,
            label = { Text("Notes (optional)") },
            enabled = vm.recording == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.recording == null) {
            Button(
                onClick = { vm.startRecording(label, notes); haptics.success() },
                enabled = vm.currentSubjectCode != null && vm.connectionState == ConnectionState.CONNECTED,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Start saving") }
            val hint = when {
                vm.currentSubjectCode == null -> "Choose who this reading is for to start saving."
                vm.connectionState != ConnectionState.CONNECTED -> "Rest a fingertip over the camera first."
                else -> null
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val reducedMotion = rememberReducedMotion()
            val pulse = rememberInfiniteTransition(label = "rec-pulse")
            val dotAlpha = if (reducedMotion) 1f else pulse.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec-dot",
            ).value
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(MV.bad.copy(alpha = dotAlpha), CircleShape))
                Text("Saving · ${Fmt.duration(vm.recordDurationMs)}", style = MaterialTheme.typography.titleMedium, color = MV.navy, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { vm.stopRecording(); haptics.success() },
                colors = ButtonDefaults.buttonColors(containerColor = MV.bad, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Finish & save", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubjectPickerDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var newCode by remember { mutableStateOf("") }
    var ageBand by remember { mutableStateOf("18-25") }
    var notes by remember { mutableStateOf("") }
    val ageBands = listOf("<18", "18-25", "26-35", "36-45", "46-55", "56-65", "65+")

    LaunchedEffect(Unit) { vm.refreshSubjects() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Who is this for?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (vm.subjects.isEmpty()) {
                    Text("No one added yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.subjects.forEach { s ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { vm.selectSubject(s.code); onDismiss() },
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                        ) {
                            Text("${s.code}  ·  ${s.ageBand}" + if (vm.currentSubjectCode == s.code) "  ✓" else "")
                        }
                    }
                }

                Text("Add someone (a short private label)", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = newCode, onValueChange = { newCode = it }, label = { Text("Label, e.g. Me") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ageBands.forEach { b ->
                        FilterChip(selected = ageBand == b, onClick = { ageBand = b }, label = { Text(b) })
                    }
                }
                Button(
                    onClick = {
                        val code = newCode.trim()
                        if (code.isNotEmpty()) {
                            vm.saveSubject(Subject(code = code, ageBand = ageBand, sex = null, notes = notes, createdAt = Instant.now().toString()))
                            vm.selectSubject(code)
                            onDismiss()
                        }
                    },
                    enabled = newCode.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add") }
            }
        },
    )
}
