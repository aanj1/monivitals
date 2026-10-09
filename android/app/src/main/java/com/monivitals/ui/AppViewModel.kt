//
//  AppViewModel.kt
//  MoniVitals (Android)
//
//  Central application store. Orchestrates the live camera measurement (pulse ring buffer +
//  MetricsEngine + motion), saving readings, settings, and the optional cloud account.
//
//  High-rate sample data lives in the non-reactive `buffers`/`engine` (read directly by the
//  Canvas); only throttled metrics, counts, and connection state are Compose state.
//

package com.monivitals.ui

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.monivitals.cloud.AppCheckService
import com.monivitals.cloud.AppleSignIn
import com.monivitals.cloud.AUTH_CANCELLED
import com.monivitals.cloud.AuthService
import com.monivitals.cloud.AuthState
import com.monivitals.cloud.CLOUD_OFFLINE_MESSAGE
import com.monivitals.cloud.CloudException
import com.monivitals.cloud.GoogleSignIn
import com.monivitals.cloud.PROVIDER_APPLE
import com.monivitals.cloud.PROVIDER_GOOGLE
import com.monivitals.cloud.SyncService
import com.monivitals.cloud.SyncStatus
import com.monivitals.dsp.MetricsEngine
import com.monivitals.dsp.MetricsSnapshot
import com.monivitals.model.Annotation
import com.monivitals.model.AnnotationType
import com.monivitals.model.AppSettings
import com.monivitals.model.MetricsRecord
import com.monivitals.model.Recorder
import com.monivitals.model.Session
import com.monivitals.model.SessionLabel
import com.monivitals.model.Store
import com.monivitals.model.Subject
import com.monivitals.source.CameraSource
import com.monivitals.source.ConnectionState
import com.monivitals.source.DataSource
import com.monivitals.source.MotionMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    // MARK: - Non-reactive runtime (mutated at full sample rate)
    val buffers = WaveBuffers()
    val engine = MetricsEngine()

    // MARK: - Settings
    var settings by mutableStateOf(loadSettings()); private set

    // MARK: - Connection / source
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED); private set
    var connectionMessage by mutableStateOf<String?>(null); private set
    var streaming by mutableStateOf(false); private set
    var hasCameraPermission by mutableStateOf(false); private set

    // MARK: - Metrics
    var liveMetrics by mutableStateOf<MetricsSnapshot?>(null); private set

    /**
     * Rolling window of the most recent HR readings (one per metrics tick, newest last),
     * powering the hero card's mini sparkline.
     */
    var hrHistory by mutableStateOf<List<Double>>(emptyList()); private set

    // MARK: - Subjects
    var subjects by mutableStateOf<List<Subject>>(emptyList()); private set
    var currentSubjectCode by mutableStateOf<String?>(null); private set

    // MARK: - Saving a reading
    var recording by mutableStateOf<Session?>(null); private set
    var recordDurationMs by mutableStateOf(0.0); private set

    // MARK: - Cloud account + backup (optional; docs/CLOUD_SYNC.md)
    var authState by mutableStateOf<AuthState?>(null); private set
    var authBusy by mutableStateOf(false); private set
    var authError by mutableStateOf<String?>(null); private set
    var syncStatus by mutableStateOf<SyncStatus>(SyncStatus.Idle); private set

    /** Epoch millis of the last completed backup (0 = never), mirrored from SyncService. */
    var lastSyncAt by mutableStateOf(0L); private set

    // MARK: - private
    private var source: DataSource? = null
    private var motion: MotionMonitor? = null
    private var recorder: Recorder? = null
    private var recordingStartedAt: Long? = null
    private val collectors = mutableListOf<Job>()
    private var tickJob: Job? = null
    private val store = Store.shared
    private val prefs = app.getSharedPreferences("monivitals", Application.MODE_PRIVATE)
    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Registers itself as CloudHttp's X-Firebase-AppCheck provider (App Check, monitor mode).
    private val appCheckService = AppCheckService(app)
    private val authService = AuthService(app)
    private val syncService = SyncService(app, authService)

    init {
        engine.setMotionThresh(settings.motionThresh)
        subjects = store.listSubjects()
        // Reading the auth tokens / last-sync stamp touches the encrypted prefs, whose first
        // access does a Keystore-backed create (50–300 ms). Do it off the main thread so it
        // never stalls the first frame; the reactive state updates when it resolves.
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { authService.load() to syncService.lastSyncAt }
            authState = loaded.first
            lastSyncAt = loaded.second
        }
    }

    // MARK: - Settings

    private fun loadSettings(): AppSettings {
        val p = getApplication<Application>().getSharedPreferences("monivitals", Application.MODE_PRIVATE)
        val s = p.getString("settings", null) ?: return AppSettings()
        return try {
            Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .decodeFromString(AppSettings.serializer(), s)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun updateSettings(mutate: (AppSettings) -> AppSettings) {
        val s = mutate(settings)
        prefs.edit().putString("settings", jsonCodec.encodeToString(AppSettings.serializer(), s)).apply()
        engine.setMotionThresh(s.motionThresh)
        settings = s
    }

    /** First-run consent / acknowledgement gate. */
    val needsOnboarding: Boolean
        get() = !(settings.consentAccepted && settings.disclaimerAcknowledged)

    fun acceptOnboarding() {
        updateSettings { it.copy(consentAccepted = true, disclaimerAcknowledged = true) }
    }

    fun setCameraPermission(granted: Boolean) {
        hasCameraPermission = granted
    }

    // MARK: - Measurement

    /** Name of the on-device model surfaced in the UI. */
    val modelName: String
        get() = engine.name

    /**
     * Begin a live camera measurement, binding it (torch on) to the given lifecycle. Safe to call
     * repeatedly (a no-op while already live).
     */
    fun beginMeasurement(lifecycleOwner: LifecycleOwner) {
        if (source != null) return
        resetRuntime()
        hrHistory = emptyList()
        connectionMessage = null
        engine.setMotionThresh(settings.motionThresh)

        val ctx = getApplication<Application>().applicationContext

        val cam = CameraSource(ctx, lifecycleOwner)
        source = cam
        wire(cam)
        val mm = MotionMonitor(ctx) { sample -> engine.ingestMotion(sample.magnitude) }
        motion = mm
        mm.start()
        cam.connect()
    }

    /** Stop the live measurement, turn the torch off, and release the camera + sensor. */
    fun endMeasurement() {
        if (recording != null) stopRecording()
        stopTick()
        collectors.forEach { it.cancel() }
        collectors.clear()
        motion?.stop()
        motion = null
        source?.disconnect()
        source = null
        streaming = false
        connectionState = ConnectionState.DISCONNECTED
    }

    // MARK: - Subjects

    fun refreshSubjects() {
        subjects = store.listSubjects()
    }

    fun saveSubject(s: Subject) {
        store.upsertSubject(s)
        refreshSubjects()
    }

    fun selectSubject(code: String?) {
        currentSubjectCode = code
    }

    // MARK: - Saving a reading

    fun startRecording(label: SessionLabel, notes: String) {
        if (recording != null) return // already saving a reading — don't orphan the recorder
        val subjectCode = currentSubjectCode ?: return
        val session = Session(
            id = UUID.randomUUID().toString(),
            subjectCode = subjectCode,
            label = label,
            startedAt = Instant.now().toString(),
            endedAt = null,
            notes = notes,
        )
        store.createSession(session)
        recorder = Recorder(session.id)
        recording = session
        recordingStartedAt = System.currentTimeMillis()
        recordDurationMs = 0.0
    }

    fun stopRecording() {
        val session = recording ?: return
        val rec = recorder ?: return
        recorder = null
        recording = null
        recordingStartedAt = null
        viewModelScope.launch(Dispatchers.IO) {
            rec.finish()
            store.endSession(session.id, Instant.now().toString())
        }
    }

    fun addMarker(type: AnnotationType, text: String? = null) {
        val session = recording ?: return
        store.addAnnotation(Annotation(sessionId = session.id, tUs = buffers.latestDeviceUs(), type = type, text = text))
    }

    /** Export a saved reading (.zip) to the app cache; returns its File for sharing. */
    fun exportSession(sessionId: String): java.io.File? =
        com.monivitals.model.CSVExport.exportSession(sessionId, getApplication(), store)

    // MARK: - Cloud account + backup

    fun signIn(email: String, password: String) =
        authRequest { authService.signIn(email.trim(), password) }

    fun createAccount(email: String, password: String) =
        authRequest { authService.signUp(email.trim(), password) }

    private fun authRequest(block: suspend () -> AuthState) {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            try {
                authState = withContext(Dispatchers.IO) { block() }
                authBusy = false
                syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                authError = e.message ?: CLOUD_OFFLINE_MESSAGE
                authBusy = false
            }
        }
    }

    fun signInWithGoogle(activity: Activity) = federatedRequest {
        val idToken = GoogleSignIn.idToken(activity)
        withContext(Dispatchers.IO) { authService.completeIdpSignIn(idToken, PROVIDER_GOOGLE) }
    }

    // TODO(apple-android): Sign in with Apple is gated OFF (FirebaseConfig.APPLE_ANDROID_ENABLED
    // = false) and the manifest deliberately declares NO redirect intent-filter. Before shipping:
    //   1. persist the AppleAuthAttempt state + rawNonce across process death (the Custom Tab
    //      round-trip can kill the app) and verify the echoed `state` (CSRF),
    //   2. re-add the VIEW/BROWSABLE intent-filter and handle the redirect in
    //      MainActivity.onNewIntent (launchMode-aware) → AppleSignIn.parseIdToken →
    //      completeAppleSignIn(idToken, rawNonce),
    //   3. prefer verified App Links over the raw custom scheme so another app can't
    //      intercept the token.
    fun signInWithApple(activity: Activity) = federatedRequest {
        AppleSignIn.launch(activity)
        throw CloudException(AUTH_CANCELLED)
    }

    fun completeAppleSignIn(idToken: String, rawNonce: String) = federatedRequest {
        withContext(Dispatchers.IO) { authService.completeIdpSignIn(idToken, PROVIDER_APPLE, rawNonce) }
    }

    private fun federatedRequest(block: suspend () -> AuthState) {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            try {
                authState = block()
                authBusy = false
                syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                authBusy = false
                authError = if (e.message == AUTH_CANCELLED) null else (e.message ?: CLOUD_OFFLINE_MESSAGE)
            }
        }
    }

    /** Forget the account on this phone only; local data and the cloud copy both stay. */
    fun signOut() {
        authService.signOut()
        // Drop offline-delete tombstones so a different account signed in on this device never
        // inherits this account's deleted-doc set (mirrors iOS clearLastSync).
        syncService.clearTombstones()
        authState = null
        authError = null
        syncStatus = SyncStatus.Idle
        lastSyncAt = 0L
    }

    fun deleteAccount() {
        if (authBusy) return
        authBusy = true
        authError = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    syncService.deleteAllRemote()
                    authService.deleteAccount()
                }
                authState = null
                syncStatus = SyncStatus.Idle
                lastSyncAt = 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                authError = e.message ?: CLOUD_OFFLINE_MESSAGE
            }
            authBusy = false
        }
    }

    fun syncNow() {
        if (authState == null || syncStatus == SyncStatus.Syncing) return
        syncStatus = SyncStatus.Syncing
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { syncService.syncNow() }
                lastSyncAt = syncService.lastSyncAt
                syncStatus = SyncStatus.Idle
                refreshSubjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncStatus = SyncStatus.Error(e.message ?: CLOUD_OFFLINE_MESSAGE)
            }
            authState = authService.load()
        }
    }

    fun appForegrounded() {
        if (authState == null) return
        if (System.currentTimeMillis() - syncService.lastSyncAt < 60_000) return
        syncNow()
    }

    private fun cloudDelete(block: suspend (SyncService) -> Unit) {
        if (authState == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(syncService)
            } catch (e: Exception) {
                // accepted: a later sync may bring the cloud copy back (documented in v1)
            }
        }
    }

    // MARK: - Data management

    fun deleteSession(id: String) {
        store.deleteSession(id)
        cloudDelete { it.deleteSessionRemote(id) }
    }

    fun deleteSubject(code: String) {
        val sessionIds = store.listSessions().filter { it.subjectCode == code }.map { it.id }
        store.deleteSubject(code)
        if (currentSubjectCode == code) selectSubject(null)
        refreshSubjects()
        cloudDelete { it.deleteSubjectRemote(code, sessionIds) }
    }

    fun deleteAllData() {
        store.deleteAllData()
        subjects = emptyList()
        currentSubjectCode = null
        cloudDelete { it.deleteAllRemote() }
    }

    // MARK: - wiring

    private fun wire(src: DataSource) {
        val ev = src.events
        val scope = viewModelScope

        collectors += scope.launch {
            ev.ppg.collect { p ->
                val dt = com.monivitals.dsp.DSPConstants.usPerS / p.sampleRateHz
                for (i in p.green.indices) {
                    val t = p.tUs + i * dt
                    buffers.ppgRed.push(t, p.red[i])
                    buffers.ppgGreen.push(t, p.green[i])
                }
                engine.ingestPpg(p)
                recorder?.ingestPpg(p)
            }
        }
        collectors += scope.launch {
            ev.connection.collect { (state, detail) ->
                connectionState = state
                connectionMessage = detail?.error
                // Start the metrics tick + sampling once the source is live.
                if (state == ConnectionState.CONNECTED && !streaming) startStreaming()
                if (state == ConnectionState.UNSUPPORTED) streaming = false
            }
        }
    }

    private fun startStreaming() {
        val src = source ?: return
        src.start()
        startTick()
        streaming = true
    }

    private fun resetRuntime() {
        buffers.clear()
        engine.reset()
    }

    private fun startTick() {
        stopTick()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                tick()
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun tick() {
        val latest = buffers.latestDeviceUs()
        val snap = engine.infer(latest)
        liveMetrics = snap
        snap.hrBpm?.let { hr -> hrHistory = (hrHistory + hr).takeLast(30) }
        val rec = recorder
        val start = recordingStartedAt
        if (rec != null && start != null) {
            recordDurationMs = (System.currentTimeMillis() - start).toDouble()
            val record = MetricsRecord(
                sessionId = rec.sessionId, tUs = snap.tUs,
                hrBpm = snap.hrBpm, respRateBpm = snap.respRateBpm,
                hrvMs = snap.hrvMs, hrvSdnnMs = snap.hrvSdnnMs,
                contactQuality = snap.contactQuality, movement = snap.movement,
            )
            viewModelScope.launch(Dispatchers.IO) { store.addMetrics(record) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        endMeasurement()
    }
}
