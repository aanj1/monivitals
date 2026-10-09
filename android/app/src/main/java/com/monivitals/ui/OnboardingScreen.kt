//
//  OnboardingScreen.kt
//  MoniVitals (Android)
//
//  First-run gate: a friendly welcome that explains the fingertip measurement plus a single
//  "for wellness, not diagnosis" acknowledgement. Acceptance is persisted (via
//  AppViewModel/AppSettings), so this screen is shown only once. Everything runs on-device.
//

package com.monivitals.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monivitals.R

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val haptics = rememberHaptics()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // Scrollable content.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Header: logo + welcome + tagline.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = "MoniVitals",
                        modifier = Modifier.size(110.dp),
                    )
                    Text(
                        "Welcome to MoniVitals",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MV.navy,
                    )
                    Text(
                        "Measure your heart rate, breathing, and heart-rate variability from your fingertip, " +
                            "using your phone's camera.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MV.inkMuted,
                    )
                }

                // "For wellness, not diagnosis" info card.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .mvCard()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconBadge(icon = Icons.Filled.HealthAndSafety, tint = MV.magenta, size = 30.dp)
                        Text(
                            "For wellness, not diagnosis",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MV.navy,
                        )
                    }
                    Text(
                        "Rest a fingertip over the rear camera and MoniVitals reads the gentle colour changes " +
                            "as your blood pulses. It works entirely on your phone and is meant for everyday " +
                            "wellness and curiosity — it can't diagnose anything, so please don't use it to make " +
                            "any health decision.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MV.inkMuted,
                    )
                }

            }

            // Pinned "Get started" button — tapping it is the acknowledgement, so
            // there's no gating toggle to get stuck on. The disclaimer is a caption.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { haptics.success(); vm.acceptOnboarding() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Get started", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    "For wellbeing and curiosity — not a substitute for medical care.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MV.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
