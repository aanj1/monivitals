//
//  CloudLogic.kt
//  MoniVitals (Android)
//
//  Pure, framework-free helpers behind the optional cloud layer (docs/CLOUD_SYNC.md):
//  Firestore doc ids, the sync merge planner, and the friendly auth error copy.
//  Deliberately no Android imports so everything here unit-tests on the JVM.
//

package com.monivitals.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

// MARK: - Document ids

/**
 * Firestore doc id for a natural key: base64url (no padding) of the UTF-8 bytes.
 * Subject codes are user-entered and may contain "/" etc.; this keeps ids
 * deterministic, collision-free and reversible.
 */
fun cloudDocId(naturalKey: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(naturalKey.toByteArray(Charsets.UTF_8))

/** Inverse of [cloudDocId]. */
fun cloudDocIdDecode(docId: String): String =
    String(Base64.getUrlDecoder().decode(docId), Charsets.UTF_8)

// MARK: - Merge planning

/** What a sync pass should do: write these records locally, push those to the cloud. */
data class MergePlan<T>(val toLocal: List<T>, val toPush: List<T>)

/**
 * Drop remote records whose natural key is tombstoned (deleted locally while offline). Without
 * this the union-merge would re-pull a record the user already deleted, because the remote delete
 * hadn't landed yet. Pure so the suppression is unit-testable without the network (mirrors iOS
 * `SyncMerge.filterTombstoned`).
 */
fun <T> filterTombstoned(
    remote: List<T>,
    tombstoned: Set<String>,
    key: (T) -> String,
): List<T> {
    if (tombstoned.isEmpty()) return remote
    return remote.filter { key(it) !in tombstoned }
}

/**
 * Union-merge of local and remote records by natural key. On a key collision the newer
 * record (per [compare]) wins; an exact tie keeps the local copy and pushes nothing.
 */
fun <T> planMerge(
    local: List<T>,
    remote: List<T>,
    key: (T) -> String,
    compare: (T, T) -> Int,
): MergePlan<T> {
    val localByKey = local.associateBy(key)
    val remoteByKey = remote.associateBy(key)
    val toLocal = mutableListOf<T>()
    val toPush = mutableListOf<T>()
    for (r in remote) {
        val l = localByKey[key(r)]
        if (l == null || compare(r, l) > 0) toLocal.add(r)
    }
    for (l in local) {
        val r = remoteByKey[key(l)]
        if (r == null || compare(l, r) > 0) toPush.add(l)
    }
    return MergePlan(toLocal, toPush)
}

/** [planMerge] ordered by a single ISO timestamp per record. */
fun <T> planMerge(
    local: List<T>,
    remote: List<T>,
    key: (T) -> String,
    stamp: (T) -> String,
): MergePlan<T> = planMerge(local, remote, key) { a, b -> compareIso(stamp(a), stamp(b)) }

/**
 * Composite ordering for session records: primary `startedAt`, tie-broken by
 * `endedAt ?? ""`. A copy whose endedAt was set after the first push therefore wins over
 * one still open (null → "" sorts before any ISO stamp); equal on both → 0, which
 * [planMerge] resolves as keep-local / push-nothing.
 */
fun compareSessionStamps(
    aStartedAt: String,
    aEndedAt: String?,
    bStartedAt: String,
    bEndedAt: String?,
): Int {
    val primary = compareIso(aStartedAt, bStartedAt)
    return if (primary != 0) primary else compareIso(aEndedAt ?: "", bEndedAt ?: "")
}

/**
 * Compare two ISO-8601 timestamps as instants (so "…00Z" vs "…00.500Z" order correctly
 * despite lexicographic order disagreeing); falls back to string order if either fails
 * to parse.
 */
fun compareIso(a: String, b: String): Int = try {
    Instant.parse(a).compareTo(Instant.parse(b))
} catch (e: Exception) {
    a.compareTo(b)
}

// MARK: - Federated sign-in (Google / Apple) helpers

/** The two Identity-Toolkit provider ids we exchange id_tokens for. */
const val PROVIDER_GOOGLE = "google.com"
const val PROVIDER_APPLE = "apple.com"

/**
 * base64url (no padding) of raw bytes — the encoding Apple/OAuth expect for nonces and
 * PKCE challenges. Shared with [cloudDocId]'s alphabet but kept separate for clarity.
 */
fun base64UrlNoPad(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** SHA-256 digest of a UTF-8 string, as raw bytes. */
fun sha256(input: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

/** Lowercase hex of a byte array (Apple's `nonce` claim in the JWT is hex sha256). */
fun toHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

/**
 * A fresh RAW nonce for Apple sign-in: 32 secure-random bytes as base64url. The RAW value
 * goes to Firebase in the postBody; the authorize URL carries `sha256Hex(raw)`, which the
 * Apple JWT then echoes so Firebase can re-hash-and-compare.
 */
fun newRawNonce(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return base64UrlNoPad(bytes)
}

/** The `nonce` value that goes in Apple's authorize URL: hex(sha256(rawNonce)). */
fun appleNonceHash(rawNonce: String): String = toHex(sha256(rawNonce))

/**
 * Build the `signInWithIdp` `postBody` string. Google needs only id_token + providerId;
 * Apple additionally carries the RAW nonce (Firebase re-hashes it and compares to the
 * JWT's `nonce` claim). [rawNonce] is url-encoded so it survives the form-encoded body.
 */
fun signInWithIdpPostBody(idToken: String, providerId: String, rawNonce: String? = null): String {
    val sb = StringBuilder("id_token=")
    sb.append(urlEncode(idToken))
    sb.append("&providerId=").append(urlEncode(providerId))
    if (rawNonce != null) sb.append("&nonce=").append(urlEncode(rawNonce))
    return sb.toString()
}

/** Minimal application/x-www-form-urlencoded encoder (kept pure; no android.net.Uri). */
internal fun urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

/**
 * Pull a single param out of a redirect URL's fragment OR query, without android.net.Uri
 * (so it unit-tests on the JVM). Apple returns the Apple `id_token` in the `#fragment`
 * (`response_mode=fragment`); some handlers pass it as a `?query` param, so we check both.
 * Returns null when the key is absent.
 */
fun redirectParam(redirect: String, key: String): String? {
    fun parse(section: String): String? =
        section.split("&").asSequence()
            .mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0] to urlDecode(kv[1]) else null
            }
            .firstOrNull { it.first == key }
            ?.second

    val fragment = redirect.substringAfter('#', "")
    val query = redirect.substringAfter('?', "").substringBefore('#')
    return parse(fragment) ?: parse(query)
}

internal fun urlDecode(s: String): String =
    try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

// MARK: - Friendly auth errors

/**
 * Sentinel [CloudException] message meaning "the user backed out" — the UI treats this as
 * a silent return to signed-out (no red error line). Never shown to the user.
 */
const val AUTH_CANCELLED = "__cancelled__"

/** Line shown when Sign in with Apple hasn't been wired up on Android yet (see docs). */
const val APPLE_NOT_SET_UP_MESSAGE = "Sign in with Apple isn't set up for Android yet."

/** Fallback line for network trouble / anything unrecognised. */
const val CLOUD_OFFLINE_MESSAGE = "Couldn't reach the cloud right now — your data is safe on this phone."

/**
 * Map an Identity Toolkit `error.message` to warm, plain copy (docs/CLOUD_SYNC.md).
 * Server messages sometimes carry suffixes ("WEAK_PASSWORD : Password should …"), so
 * only the leading code is matched.
 */
fun friendlyAuthError(raw: String?): String {
    val code = raw?.trim()?.substringBefore(" ")?.substringBefore(":") ?: ""
    return when {
        code == "FEDERATED_USER_ID_ALREADY_LINKED" ->
            "That email is already signed up a different way — try that instead."
        // Apple-on-Android isn't configured server-side yet (no Services ID / key); surface
        // the friendly gate rather than a raw Identity-Toolkit failure.
        code == "INVALID_IDP_RESPONSE" || code == "MISSING_OR_INVALID_NONCE" -> APPLE_NOT_SET_UP_MESSAGE
        code == "EMAIL_EXISTS" -> "That email already has an account — try signing in instead."
        code == "INVALID_LOGIN_CREDENTIALS" || code == "INVALID_PASSWORD" || code == "EMAIL_NOT_FOUND" ->
            "That email and password don't match — have another look and try again."
        code.startsWith("WEAK_PASSWORD") -> "Passwords need at least 6 characters."
        code == "INVALID_EMAIL" -> "That doesn't look like an email address yet."
        code == "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Lots of tries in a row — take a short break and try again."
        code == "CONFIGURATION_NOT_FOUND" -> "Cloud accounts aren't switched on for this build yet."
        else -> CLOUD_OFFLINE_MESSAGE
    }
}
