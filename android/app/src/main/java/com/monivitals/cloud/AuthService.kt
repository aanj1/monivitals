//
//  AuthService.kt
//  MoniVitals (Android)
//
//  Email/password accounts against the Firebase Identity Toolkit REST API — no Firebase
//  SDK (docs/CLOUD_SYNC.md, same pattern as ClearDive). Tokens persist in an
//  EncryptedSharedPreferences file (AES-256, key in the Android Keystore) and are
//  refreshed proactively before any Firestore call. Legacy plaintext prefs
//  ("monivitals.auth") are migrated once and deleted.
//

package com.monivitals.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Signed-in identity + tokens (persisted; [expiresAt] is epoch millis for the idToken). */
data class AuthState(
    val email: String,
    val uid: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAt: Long,
)

/** Carries a user-ready message (already mapped to the warm copy in CloudLogic). */
class CloudException(message: String) : Exception(message)

/**
 * Single shared prefs file for auth tokens + the last-sync stamp, encrypted at rest
 * (androidx.security-crypto; the master key never leaves the Android Keystore).
 *
 * On first access any legacy plaintext "monivitals.auth" file is migrated into the
 * encrypted file and then deleted, so plaintext refresh tokens don't linger on disk.
 * If the Keystore is unusable (rare, e.g. corrupted keystore after a botched restore)
 * we fall back to a plain private prefs file rather than crashing — tokens are still
 * app-private and excluded from backups either way.
 */
internal object AuthPrefs {
    private const val LEGACY_NAME = "monivitals.auth"
    private const val ENCRYPTED_NAME = "monivitals.auth.enc"

    @Volatile private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appContext = context.applicationContext
            val prefs = create(appContext)
            migrateLegacy(appContext, prefs)
            cached = prefs
            return prefs
        }
    }

    /**
     * Prime the encrypted prefs off the main thread. The first [get] does a Keystore-backed
     * EncryptedSharedPreferences.create (50–300 ms), so call this on a background dispatcher at
     * startup; the later on-main [get] then hits the warm cache and returns instantly.
     */
    fun warm(context: Context) {
        get(context)
    }

    // Tink keyset prefs file backing EncryptedSharedPreferences (androidx.security-crypto
    // default). Wiped alongside the encrypted file when the keyset is corrupted.
    private const val KEYSET_PREFS_NAME = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    private fun create(context: Context): SharedPreferences {
        return try {
            openEncrypted(context)
        } catch (e: java.security.GeneralSecurityException) {
            // A corrupted Tink keyset (e.g. after a botched backup/restore) surfaces here and
            // would otherwise silently downgrade to plaintext on EVERY launch. Wipe the bad
            // keyset + encrypted file once and re-create fresh (any stored tokens are lost, so
            // the user re-signs-in — a one-time cost, not a permanent plaintext downgrade).
            recreateAfterCorruption(context)
        } catch (e: java.io.IOException) {
            // IOException here is also keyset/format corruption (not Keystore hardware trouble).
            recreateAfterCorruption(context)
        } catch (e: Exception) {
            // Genuine Keystore-unavailable (rare, e.g. hardware keystore down) — fall back to a
            // private plaintext file so the app still works; tokens stay app-private + no-backup.
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun openEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Delete the corrupted encrypted file + its keyset, then re-create fresh; plaintext only
     *  if even the clean re-create fails (then it's a real Keystore problem, not corruption). */
    private fun recreateAfterCorruption(context: Context): SharedPreferences {
        context.deleteSharedPreferences(ENCRYPTED_NAME)
        context.deleteSharedPreferences(KEYSET_PREFS_NAME)
        return try {
            openEncrypted(context)
        } catch (e: Exception) {
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }
    }

    /** One-time move of any plaintext tokens into the encrypted file; deletes the old file. */
    private fun migrateLegacy(context: Context, target: SharedPreferences) {
        try {
            val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
            if (target === legacy) return // encrypted prefs unavailable; nothing to migrate into
            val entries = legacy.all
            if (entries.isEmpty()) return
            // If the encrypted file already holds a session, migration has run (or the user
            // signed in since) — copying the stale legacy entries would clobber NEWER tokens,
            // which is exactly what happens on a re-run when the delete below silently fails.
            if (target.getString("refreshToken", null) == null) {
                val editor = target.edit()
                for ((key, value) in entries) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Int -> editor.putInt(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Float -> editor.putFloat(key, value)
                    }
                }
                editor.apply()
            }
            // Remove the plaintext file itself. If the OS refuses to delete the file, at least
            // clear its entries so a future launch can't re-copy stale tokens over newer ones.
            if (!context.deleteSharedPreferences(LEGACY_NAME)) {
                legacy.edit().clear().apply()
            }
        } catch (e: Exception) {
            // Migration is best-effort; worst case the user signs in again.
        }
    }
}

class AuthService(context: Context) {
    private val appContext = context.applicationContext
    // Lazy so simply constructing the service on the main thread doesn't trigger the
    // Keystore-backed EncryptedSharedPreferences create; the first actual use resolves it
    // (warmed off-main at startup via AuthPrefs.warm — see MainActivity).
    private val prefs: SharedPreferences by lazy { AuthPrefs.get(appContext) }

    // MARK: - Persistence

    fun load(): AuthState? {
        val email = prefs.getString("email", null) ?: return null
        val uid = prefs.getString("uid", null) ?: return null
        val refreshToken = prefs.getString("refreshToken", null) ?: return null
        val idToken = prefs.getString("idToken", null) ?: return null
        return AuthState(email, uid, refreshToken, idToken, prefs.getLong("expiresAt", 0L))
    }

    private fun save(s: AuthState) {
        prefs.edit()
            .putString("email", s.email)
            .putString("uid", s.uid)
            .putString("refreshToken", s.refreshToken)
            .putString("idToken", s.idToken)
            .putLong("expiresAt", s.expiresAt)
            .apply()
    }

    /** Forget tokens (and the last-sync stamp stored alongside). Local data is untouched. */
    fun signOut() {
        prefs.edit().clear().apply()
    }

    // MARK: - Accounts

    suspend fun signUp(email: String, password: String): AuthState =
        mapped { account("signUp", email, password) }

    suspend fun signIn(email: String, password: String): AuthState =
        mapped { account("signInWithPassword", email, password) }

    /**
     * Exchange a provider `id_token` (Google or Apple) for a Firebase session via
     * Identity Toolkit `accounts:signInWithIdp`, then persist the SAME [AuthState] the
     * password path saves. Both federated flows funnel through here.
     *
     * Apple may omit `email` on repeat logins; we fall back to the cached email (if the
     * device already knew it) and finally to a `user@localId` display label so the
     * signed-in UI always has something friendly to show.
     */
    suspend fun completeIdpSignIn(idToken: String, providerId: String, rawNonce: String? = null): AuthState =
        mapped {
            val body = buildJsonObject {
                put("postBody", signInWithIdpPostBody(idToken, providerId, rawNonce))
                put("requestUri", "https://${FirebaseConfig.authDomain}")
                put("returnSecureToken", true)
                put("returnIdpCredential", true)
            }.toString()
            val obj = CloudHttp.postJson(
                "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=${FirebaseConfig.apiKey}",
                body,
            )
            val expiresIn = obj["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            val uid = str(obj, "localId")
            val email = obj["email"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: load()?.email
                ?: "user@$uid"
            val state = AuthState(
                email = email,
                uid = uid,
                refreshToken = str(obj, "refreshToken"),
                idToken = str(obj, "idToken"),
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            )
            save(state)
            state
        }

    private suspend fun account(action: String, email: String, password: String): AuthState {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("returnSecureToken", true)
        }.toString()
        val obj = CloudHttp.postJson(
            "https://identitytoolkit.googleapis.com/v1/accounts:$action?key=${FirebaseConfig.apiKey}",
            body,
        )
        val expiresIn = obj["expiresIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        val state = AuthState(
            email = obj["email"]?.jsonPrimitive?.content ?: email,
            uid = str(obj, "localId"),
            refreshToken = str(obj, "refreshToken"),
            idToken = str(obj, "idToken"),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
        save(state)
        return state
    }

    /**
     * Current auth with a valid idToken, refreshing proactively when under five minutes
     * remain. Returns null when signed out — including when the refresh is rejected with
     * HTTP 400 (token revoked), which is treated as a clean sign-out.
     */
    suspend fun freshAuth(): AuthState? {
        val state = load() ?: return null
        if (state.expiresAt - System.currentTimeMillis() > 5 * 60 * 1000L) return state
        return try {
            val body = "grant_type=refresh_token&refresh_token=" +
                URLEncoder.encode(state.refreshToken, "UTF-8")
            val obj = CloudHttp.postForm(
                "https://securetoken.googleapis.com/v1/token?key=${FirebaseConfig.apiKey}",
                body,
            )
            // Refresh responses use snake_case keys.
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            val fresh = state.copy(
                idToken = str(obj, "id_token"),
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: state.refreshToken,
                uid = obj["user_id"]?.jsonPrimitive?.content ?: state.uid,
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            )
            save(fresh)
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudHttp.HttpError) {
            if (e.status == 400) {
                signOut()
                null
            } else {
                throw CloudException(CLOUD_OFFLINE_MESSAGE)
            }
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException(CLOUD_OFFLINE_MESSAGE)
        }
    }

    /** Delete the account itself (SyncService clears the cloud docs first), then sign out. */
    suspend fun deleteAccount() {
        val state = freshAuth() ?: return
        mapped {
            CloudHttp.postJson(
                "https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${FirebaseConfig.apiKey}",
                buildJsonObject { put("idToken", state.idToken) }.toString(),
            )
        }
        signOut()
    }

    // MARK: - helpers

    /** Convert transport errors into a single warm, user-ready [CloudException]. */
    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudException) {
        throw e
    } catch (e: CloudHttp.HttpError) {
        throw CloudException(friendlyAuthError(e.serverMessage))
    } catch (e: Exception) {
        throw CloudException(CLOUD_OFFLINE_MESSAGE)
    }

    private fun str(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.content ?: throw CloudException(CLOUD_OFFLINE_MESSAGE)
}

// MARK: - Minimal HTTPS helper

/**
 * Tiny HttpURLConnection wrapper shared by AuthService/SyncService — the project stays
 * dependency-light (no OkHttp). Every call hops to Dispatchers.IO.
 */
internal object CloudHttp {
    /** Non-2xx response; [serverMessage] is the decoded `error.message` when present. */
    class HttpError(val status: Int, val serverMessage: String?) :
        IOException("HTTP $status: ${serverMessage ?: "(no message)"}")

    /** App Check's own endpoints; the token provider is skipped here (no mint recursion). */
    private const val APP_CHECK_HOST = "https://firebaseappcheck.googleapis.com/"

    /**
     * Set once by [AppCheckService]; when present every non-App-Check request carries an
     * `X-Firebase-AppCheck` header. Null result (or any provider failure) means the
     * request goes out without the header — App Check only monitors, so it never blocks.
     */
    @Volatile var appCheckTokenProvider: (suspend () -> String?)? = null

    /**
     * `(packageName, signingCertSha1)` set once by [AppCheckService]. The Google API key
     * is application-restricted to this package + certificate, and REST callers must
     * identify themselves via `X-Android-Package` / `X-Android-Cert` for that check.
     */
    @Volatile var appIdentity: Pair<String, String>? = null

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun postJson(url: String, body: String): JsonObject =
        parse(request("POST", url, body, "application/json", null))

    suspend fun postForm(url: String, body: String): JsonObject =
        parse(request("POST", url, body, "application/x-www-form-urlencoded", null))

    suspend fun getJson(url: String, bearer: String): JsonObject =
        parse(request("GET", url, null, null, bearer))

    suspend fun patchJson(url: String, body: String, bearer: String): JsonObject =
        parse(request("PATCH", url, body, "application/json", bearer))

    suspend fun delete(url: String, bearer: String) {
        request("DELETE", url, null, null, bearer)
    }

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        bearer: String?,
    ): String = withContext(Dispatchers.IO) {
        // Fetched before the connection opens; a cached token returns instantly and a
        // mint hop is itself CloudHttp against APP_CHECK_HOST (provider skipped there).
        val appCheck = if (url.startsWith(APP_CHECK_HOST)) null else try {
            appCheckTokenProvider?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // attestation trouble must never block the request itself
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no native PATCH; Google APIs accept the override header.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            appCheck?.let { conn.setRequestProperty("X-Firebase-AppCheck", it) }
            appIdentity?.let { (pkg, cert) ->
                conn.setRequestProperty("X-Android-Package", pkg)
                if (cert.isNotEmpty()) conn.setRequestProperty("X-Android-Cert", cert)
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val message = try {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.content
                } catch (e: Exception) {
                    null
                }
                throw HttpError(status, message)
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}
