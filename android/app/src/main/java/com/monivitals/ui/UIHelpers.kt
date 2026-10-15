//
//  UIHelpers.kt
//  MoniVitals (Android)
//
//  Shared formatting helpers and small reusable composables (metric tiles, status pills,
//  section cards, labeled rows) plus the share-sheet helper. Colors are driven from the
//  (always-light) MaterialTheme.colorScheme + the MV brand object. Deliberately avoids any
//  diagnostic/clinical language — values are labeled as estimates and the app is a research
//  tool.
//

package com.monivitals.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.monivitals.source.ConnectionState
import java.io.File
import java.util.Locale

// MARK: - Formatting

object Fmt {
    fun opt(v: Double?, decimals: Int = 0, suffix: String = ""): String {
        if (v == null) return "—"
        return String.format(Locale.US, "%.${decimals}f", v) + suffix
    }

    fun bpm(v: Double?): String = opt(v, 0)
    fun pct(v: Double?): String = opt(v, 0)

    /** Nullable integer — renders "—" (not "0") while a metric is still warming up. */
    fun int(v: Int?, suffix: String = ""): String = if (v == null) "—" else "$v$suffix"

    fun duration(ms: Double): String {
        val totalSec = (ms / 1000).toInt()
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }
}

// MARK: - Shared card treatment

/**
 * The MoniVitals card look, matching iOS `mvCard`: a soft elevation shadow under a
 * [MV.surface] fill with 16dp rounded corners and a 1dp hairline separator border.
 * Apply this to any surface that should read as a card; wrap your own padding inside.
 */
fun Modifier.mvCard(radius: Dp = 18.dp): Modifier = this
    .shadow(6.dp, RoundedCornerShape(radius), clip = false, ambientColor = MV.navy.copy(alpha = 0.10f), spotColor = MV.navy.copy(alpha = 0.12f))
    .background(MV.surface, RoundedCornerShape(radius))
    .border(1.dp, MV.separator, RoundedCornerShape(radius))

// MARK: - Icon badge

/** Tinted, rounded icon chip used at the top-left of metric tiles (mirrors iOS IconBadge). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 26.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

// MARK: - Empty state

/**
 * A friendly empty-state block: a tinted [IconBadge], a short title, and a supporting line,
 * all inside an [mvCard]. Used when a list/section has nothing to show yet so the screen
 * never reads as broken or blank.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    tint: Color = MV.navy,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mvCard()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconBadge(icon = icon, tint = tint, size = 40.dp)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// MARK: - Shared top bar

/**
 * The one polished top bar used across every screen: a bold navy title on the app
 * background, an optional leading badge/back slot, and an optional trailing actions slot
 * (usually the [ConnectionPill]). Centralising it keeps headers pixel-consistent.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MvTopBar(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Text(
                title,
                color = MV.navy,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = { navigation?.invoke() },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = {
            actions?.invoke(this)
            Spacer(Modifier.size(8.dp))
        },
    )
}

// MARK: - Section card

/**
 * A titled card container used by the settings-style list screens. The optional [icon]
 * renders as a small tinted badge beside the header — used on Settings so each section
 * is scannable at a glance without changing any copy.
 */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = MV.navy,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .mvCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) IconBadge(icon = icon, tint = iconTint, size = 22.dp)
            Text(
                title.uppercase(Locale.US),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MV.inkMuted,
            )
        }
        content()
    }
}

// MARK: - Labeled rows

@Composable
fun LabeledRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        trailing()
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    LabeledRow(label) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - Metric tile

@Composable
fun MetricTile(
    title: String,
    value: String,
    unit: String = "",
    note: String? = null,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    // One coherent spoken label for the whole tile (TalkBack reads a single sentence
    // instead of four disjoint fragments). "—" is read as "no reading yet".
    val spoken = buildString {
        append(title)
        append(": ")
        append(if (value == "—") "no reading yet" else "$value $unit".trim())
        if (note != null) { append(", "); append(note) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 118.dp)
            .mvCard()
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) IconBadge(icon = icon, tint = tint, size = 26.dp)
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            // Animate the numeric value so a warm-up "—" → live reading transition (and every
            // subsequent update) reads as a gentle roll rather than a hard flash: the new value
            // slides up + fades in while the old slides up + fades out.
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                },
                label = "metric-value",
            ) { v ->
                Text(
                    v,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    // Tint the live value in the tile's own hue for stronger hierarchy; keep the
                    // warm-up placeholder muted so it does not read as a real reading.
                    color = if (v == "—") MaterialTheme.colorScheme.onSurfaceVariant else tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(0.dp))
    }
}

// MARK: - Connection pill

@Composable
fun ConnectionPill(state: ConnectionState) {
    // User-facing status only: Live / Starting / Paused / Unavailable. The intermediate
    // capture states are surfaced as "Starting" so no technical jargon leaks to the user.
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> "Live" to MV.good
        ConnectionState.SCANNING -> "Starting" to MV.warn
        ConnectionState.CONNECTING -> "Starting" to MV.warn
        ConnectionState.RECONNECTING -> "Starting" to MV.warn
        ConnectionState.DISCONNECTED -> "Paused" to MV.inkMuted
        ConnectionState.UNSUPPORTED -> "Unavailable" to MV.bad
    }
    // Gently pulse the status dot while Live so the pill feels alive (a heartbeat cue), and
    // stays static otherwise so "Paused"/"Unavailable" don't imply activity.
    val pulsing = state == ConnectionState.CONNECTED
    val transition = rememberInfiniteTransition(label = "pill-pulse")
    val dotAlpha = if (pulsing) {
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dot-alpha",
        ).value
    } else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            // Hairline border so the pill separates from the white top bar (the surfaceVariant
            // fill is nearly the same value as the background).
            .border(1.dp, MV.separator, CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "Stream status: $label" },
    ) {
        Box(modifier = Modifier.size(8.dp).background(color.copy(alpha = dotAlpha), CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// MARK: - Label chip

/** A small tinted chip for categorical labels (session label on Review rows, etc.). */
@Composable
fun LabelChip(text: String, tint: Color = MV.navy) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// MARK: - Reduced motion

/**
 * True when the user has disabled animations system-wide (animator duration scale = 0).
 * Decorative loops (the hero heartbeat pulse, the recording dot) respect this and render
 * statically; functional transitions are left to Compose's own handling.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    return remember {
        try {
            android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

// MARK: - Haptics

/**
 * Lightweight haptic cues for key actions. `light` for confirmations (add pair, marker),
 * `success` for consequential completions (save calibration, accept onboarding, stop
 * recording). Wraps Compose's [androidx.compose.ui.hapticfeedback.HapticFeedback] so call
 * sites stay terse. All are best-effort — silent on devices without a vibrator.
 */
@Composable
fun rememberHaptics(): Haptics {
    val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(hf) { Haptics(hf) }
}

class Haptics(private val hf: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun light() = hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    fun success() = hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
}

// MARK: - Share sheet

/** Share an exported bundle .zip via the system share sheet, using FileProvider. */
fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share reading"))
}
