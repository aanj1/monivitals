//
//  SettingsScreen.kt
//  MoniVitals (Android)
//
//  Movement sensitivity, appearance, your account (cloud backup), your data, and about.
//

package com.monivitals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.PaddingValues
import com.monivitals.BuildConfig
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ButtonDefaults
import com.monivitals.cloud.FirebaseConfig
import com.monivitals.cloud.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MvTopBar("Settings") },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Model", icon = Icons.Filled.Memory) {
                Text(vm.modelName, style = MaterialTheme.typography.bodyMedium, color = MV.inkMuted)
            }

            SectionCard("Movement sensitivity", icon = Icons.AutoMirrored.Filled.DirectionsWalk) {
                Text(
                    "How gently the movement meter responds. Lower is more sensitive to small hand movements.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Current: ${"%.1f".format(vm.settings.motionThresh)}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = vm.settings.motionThresh.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(motionThresh = v.toDouble()) } },
                    valueRange = 2f..15f,
                )
            }

            SectionCard("Appearance", icon = Icons.Filled.Palette) {
                LabeledValue("Theme", "Light (brand)")
                LabeledValue("Units", "bpm · br/min · ms")
            }

            AccountSection(vm)

            SectionCard("Your data", icon = Icons.Filled.Storage) {
                LabeledValue("People", "${vm.subjects.size}")
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Delete everything on this phone", color = MaterialTheme.colorScheme.error) }
                Text(
                    "Your readings stay on this phone. With an account, your people and clip list are " +
                        "backed up privately to your own space in the cloud.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("About", icon = Icons.Filled.Info) {
                Text(
                    "MoniVitals measures your heart rate, breathing, and heart-rate variability from your " +
                        "fingertip using the phone's camera. It is for everyday wellness and curiosity — not " +
                        "for diagnosis or any health decision.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { uriHandler.openUri("https://aranyoing.com/monivitals/privacy") },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Privacy Policy") }
                TextButton(
                    onClick = { uriHandler.openUri("https://aranyoing.com/monivitals/terms") },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Terms of Use") }
                LabeledValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete everything?") },
            text = { Text("Remove all people and saved readings from this phone? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { haptics.success(); vm.deleteAllData(); showDeleteConfirm = false }) {
                    Text("Delete everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Optional cloud account (docs/CLOUD_SYNC.md): signed out shows email/password + sign-in /
 * create-account; signed in shows the backup status with Sync now / Sign out / Delete
 * account. Readings themselves never leave the phone either way.
 */
@Composable
private fun AccountSection(vm: AppViewModel) {
    var showDeleteAccount by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    SectionCard("Account", icon = Icons.Filled.Cloud) {
        val auth = vm.authState
        if (auth == null) {
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !vm.authBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !vm.authBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.authError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { haptics.light(); vm.signIn(email, password) },
                    enabled = !vm.authBusy && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Sign in") }
                OutlinedButton(
                    onClick = { haptics.light(); vm.createAccount(email, password) },
                    enabled = !vm.authBusy && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Create account") }
                if (vm.authBusy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            Text("Or continue with", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val context = LocalContext.current
            OutlinedButton(
                onClick = { context.findActivity()?.let { haptics.light(); vm.signInWithGoogle(it) } },
                enabled = !vm.authBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Continue with Google") }
            Button(
                onClick = { context.findActivity()?.let { haptics.light(); vm.signInWithApple(it) } },
                enabled = !vm.authBusy,
                colors = ButtonDefaults.buttonColors(containerColor = MV.ink, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Continue with Apple") }
            if (!FirebaseConfig.APPLE_ANDROID_ENABLED) {
                Text(
                    "Sign in with Apple isn't set up for Android yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "Optional — an account backs up your people and clip list. Readings themselves always " +
                    "stay on your phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabeledValue("Signed in as", auth.email)
            when (val status = vm.syncStatus) {
                is SyncStatus.Syncing -> Text("Syncing…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is SyncStatus.Error -> Text(status.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                is SyncStatus.Idle -> Text(
                    if (vm.lastSyncAt > 0) "Backed up ${relativeTime(vm.lastSyncAt)}" else "Not backed up yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { haptics.light(); vm.syncNow() },
                enabled = vm.syncStatus != SyncStatus.Syncing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Sync now") }
            OutlinedButton(
                onClick = { haptics.light(); vm.signOut() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Sign out") }
            OutlinedButton(
                onClick = { showDeleteAccount = true },
                enabled = !vm.authBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Delete account…", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showDeleteAccount) {
        AlertDialog(
            onDismissRequest = { showDeleteAccount = false },
            title = { Text("Delete account?") },
            text = { Text("This removes your account and everything backed up to the cloud. Data on this phone stays.") },
            confirmButton = {
                TextButton(onClick = { haptics.success(); vm.deleteAccount(); showDeleteAccount = false }) {
                    Text("Delete account", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteAccount = false }) { Text("Cancel") } },
        )
    }
}

/** Walk the ContextWrapper chain to the hosting Activity (Credential Manager / Custom Tabs need it). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** "just now" / "5 min ago" / "3 h ago" / "2 d ago" for the backed-up line. */
private fun relativeTime(epochMs: Long): String {
    val s = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
