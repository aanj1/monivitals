//
//  MainActivity.kt
//  MoniVitals (Android)
//
//  Single-activity Compose entry point. Hosts the shared AppViewModel and shows the friendly
//  first-run welcome + acknowledgement. Measurement is taken from the fingertip using the
//  phone's rear camera.
//
//  MoniVitals is for everyday wellness, not a medical device.
//

package com.monivitals

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monivitals.cloud.AuthPrefs
import com.monivitals.model.Store
import com.monivitals.ui.AppViewModel
import com.monivitals.ui.MoniVitalsTheme
import com.monivitals.ui.OnboardingScreen
import com.monivitals.ui.RootScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        // Prime the encrypted auth prefs off the main thread: the first EncryptedSharedPreferences
        // create is Keystore-backed and can stall 50–300 ms, which would otherwise jank the first
        // frame when the AppViewModel touches AuthService/SyncService during setContent.
        val appContext = applicationContext
        Thread { AuthPrefs.warm(appContext) }.start()

        setContent {
            val vm: AppViewModel = viewModel()

            // Cloud backup runs when the app returns to the foreground while signed in
            // (throttled inside the ViewModel; a no-op when signed out). On backgrounding we
            // also flush the store's in-memory write buffer so a low-memory kill can't lose up
            // to ~10 s of a recording in progress; the disk write hops off the main thread.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> vm.appForegrounded()
                        Lifecycle.Event.ON_STOP -> Thread { Store.shared.flushPending() }.start()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MoniVitalsTheme(theme = vm.settings.theme) {
                if (vm.needsOnboarding) {
                    OnboardingScreen(vm)
                } else {
                    RootScreen(vm)
                }
            }
        }
    }
}
