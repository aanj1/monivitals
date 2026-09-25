//
//  AppCheckService.kt
//  MoniVitals (Android)
//
//  Firebase App Check tokens over plain REST — no Firebase SDK, matching the rest of the
//  cloud layer (docs/CLOUD_SYNC.md). Primary path is the Play Integrity "classic" flow
//  (challenge → Play Integrity attestation → exchange); debug builds fall back to the
//  console-issued debug token compiled in via BuildConfig.APP_CHECK_DEBUG_TOKEN.
//
//  App Check is in MONITOR mode server-side (unenforced), so every failure here is soft:
//  currentToken() returns null and the request simply goes out without the
//  X-Firebase-AppCheck header. Sync must never be blocked by attestation trouble.
//

package com.monivitals.cloud

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.monivitals.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// MARK: - Pure helpers (JVM-testable; no Android/network — mirrors CloudLogic.kt)

/** A minted App Check token; [expiresAt] is epoch millis. Never logged. */
data class AppCheckToken(val token: String, val expiresAt: Long)

/** Refresh margin: a cached token with under five minutes left is treated as stale. */
const val APP_CHECK_REFRESH_MARGIN_MS = 5 * 60 * 1000L

/**
 * After a failed mint, skip re-minting for this long so one sync pass (many serialized
 * requests) doesn't re-run a doomed challenge → Play Integrity → exchange per request
 * (mirrors iOS AppCheckService.failureCooldownSeconds = 60 s).
 */
const val APP_CHECK_FAILURE_COOLDOWN_MS = 60 * 1000L

/** True while a recent mint failure should suppress another attempt (pure; JVM-testable). */
fun appCheckInFailureCooldown(lastFailureAt: Long, now: Long): Boolean =
    lastFailureAt != 0L && now - lastFailureAt < APP_CHECK_FAILURE_COOLDOWN_MS

/** True while the cached token has comfortably more than the refresh margin left. */
fun appCheckTokenFresh(token: AppCheckToken?, now: Long): Boolean =
    token != null && token.expiresAt - now > APP_CHECK_REFRESH_MARGIN_MS

/**
 * Parse an App Check `ttl` ("3600s", sometimes fractional "3599.5s") to millis; anything
 * missing or unparseable falls back to one hour, matching the API's usual grant.
 */
fun parseAppCheckTtlMillis(ttl: String?, defaultMillis: Long = 3_600_000L): Long {
    val seconds = ttl?.trim()?.removeSuffix("s")?.toDoubleOrNull() ?: return defaultMillis
    if (seconds <= 0.0) return defaultMillis
    return (seconds * 1000.0).toLong()
}

/**
 * Parse an exchange response (`exchangePlayIntegrityToken` / `exchangeDebugToken`) into
 * an [AppCheckToken]. v1 returns `token`; older/beta shapes used `attestationToken`, so
 * both are accepted. Null when neither is present (caller degrades to no header).
 */
fun parseAppCheckExchange(obj: JsonObject, now: Long): AppCheckToken? {
    val token = obj["token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: obj["attestationToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: return null
    return AppCheckToken(token, now + parseAppCheckTtlMillis(obj["ttl"]?.jsonPrimitive?.content))
}

/** The nonce out of a `generatePlayIntegrityChallenge` response; null when absent. */
fun parseAppCheckChallenge(obj: JsonObject): String? =
    obj["challenge"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

// MARK: - Service

/**
 * Mints and caches App Check tokens. Constructing the service registers it as
 * [CloudHttp]'s token provider, so every Identity Toolkit / Firestore request picks up
 * the `X-Firebase-AppCheck` header automatically (CloudHttp skips the provider for App
 * Check's own endpoints, so minting can't recurse).
 */
class AppCheckService(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var cached: AppCheckToken? = null
    private var lastFailureAt = 0L

    init {
        CloudHttp.appCheckTokenProvider = { currentToken() }
        CloudHttp.appIdentity = appContext.packageName to signingCertSha1(appContext)
    }

    /** Uppercase colon-free SHA-1 of the first signing certificate — the value the
     *  Google API key's Android application restriction matches against. */
    private fun signingCertSha1(context: Context): String = try {
        val pm = context.packageManager
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(
                context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(
                context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        // No signer means we can't derive a real cert digest — return "" rather than the
        // SHA-1 of nothing (da39a3ee…), which would pass isNotEmpty() and ship a bogus
        // X-Android-Cert header (CloudHttp only sends the header when this is non-empty).
        val first = certs?.firstOrNull()?.toByteArray() ?: return ""
        java.security.MessageDigest.getInstance("SHA-1").digest(first)
            .joinToString("") { "%02X".format(it) }
    } catch (e: Exception) {
        ""
    }

    private val base =
        "https://firebaseappcheck.googleapis.com/v1/projects/${FirebaseConfig.projectId}" +
            "/apps/${FirebaseConfig.appId}"

    /**
     * A valid App Check token, minting a fresh one when the cache has under five minutes
     * left. Null on any failure — the caller sends the request without the header (the
     * backend only monitors, never rejects). Serialized behind a mutex so concurrent
     * requests share one mint instead of racing Play Integrity.
     */
    suspend fun currentToken(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { appCheckTokenFresh(it, now) }?.let { return@withLock it.token }
        // Skip re-minting right after a failure so a whole sync pass doesn't re-run a
        // doomed attestation per request (mirrors iOS failureCooldownSeconds).
        if (appCheckInFailureCooldown(lastFailureAt, now)) return@withLock null
        val minted = try {
            fetchToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // soft-fail by contract; details are never logged (they'd sit next to tokens)
        }
        if (minted != null) {
            cached = minted
        } else {
            lastFailureAt = System.currentTimeMillis()
        }
        minted?.token
    }

    /**
     * Play Integrity classic flow first — the provider registered server-side. Debug
     * builds (debug-signed, often emulator) can't pass attestation, so any failure there
     * falls through to the console-issued debug token; release builds return null.
     */
    private suspend fun fetchToken(): AppCheckToken? {
        val viaIntegrity = try {
            playIntegrityToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (viaIntegrity != null) return viaIntegrity
        return if (BuildConfig.DEBUG) debugTokenExchange() else null
    }

    /** Steps a–c: challenge → Play Integrity attestation → exchange. Throws on failure. */
    private suspend fun playIntegrityToken(): AppCheckToken? {
        val challenge = parseAppCheckChallenge(
            CloudHttp.postJson("$base:generatePlayIntegrityChallenge?key=${FirebaseConfig.apiKey}", "{}"),
        ) ?: return null
        val integrityToken = IntegrityManagerFactory.create(appContext)
            .requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(challenge).build())
            .awaitResult()
            .token()
        val body = buildJsonObject { put("playIntegrityToken", integrityToken) }.toString()
        val obj = CloudHttp.postJson("$base:exchangePlayIntegrityToken?key=${FirebaseConfig.apiKey}", body)
        return parseAppCheckExchange(obj, System.currentTimeMillis())
    }

    /** Debug builds only: exchange the console-issued debug token. Null when unset/failed. */
    private suspend fun debugTokenExchange(): AppCheckToken? {
        val debugToken = BuildConfig.APP_CHECK_DEBUG_TOKEN
        if (debugToken.isBlank()) return null
        return try {
            val body = buildJsonObject { put("debugToken", debugToken) }.toString()
            val obj = CloudHttp.postJson("$base:exchangeDebugToken?key=${FirebaseConfig.apiKey}", body)
            parseAppCheckExchange(obj, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** Await a Play Services [Task] inside a coroutine (kept local; no coroutines-play-services dep). */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
