//
//  RootScreen.kt
//  MoniVitals (Android)
//
//  The main bottom-navigation scaffold: Home (dashboard), Measure (live camera), Clips
//  (saved readings), and Settings.
//

package com.monivitals.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/** [icon] renders when idle; [selectedIcon] (the filled variant) when active. */
private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
fun RootScreen(vm: AppViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    val haptics = rememberHaptics()

    val tabs = listOf(
        Tab("Home", Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
        Tab("Measure", Icons.Outlined.Fingerprint, Icons.Filled.Fingerprint),
        Tab("Clips", Icons.AutoMirrored.Outlined.List, Icons.AutoMirrored.Filled.List),
        Tab("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { if (selected != i) haptics.light(); selected = i },
                        icon = { Icon(if (selected == i) tab.selectedIcon else tab.icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MV.magenta.copy(alpha = 0.12f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            Crossfade(targetState = selected, animationSpec = tween(220), label = "tab") { tab ->
                when (tab) {
                    0 -> DashboardScreen(vm)
                    1 -> MeasureScreen(vm)
                    2 -> ClipsScreen(vm)
                    3 -> SettingsScreen(vm)
                }
            }
        }
    }
}
