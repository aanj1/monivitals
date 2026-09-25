//
//  SyncService.kt
//  MoniVitals (Android)
//
//  Firestore REST sync of subjects, sessions (metadata) and calibrations
//  (docs/CLOUD_SYNC.md). Raw waveform chunks and per-session metrics never leave the
//  device. Union-merge by natural key; the newer record wins; ties keep local.
//

package com.monivitals.cloud

import android.content.Context
import android.content.SharedPreferences
import com.monivitals.model.Session
import com.monivitals.model.Store
import com.monivitals.model.Subject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** UI-facing sync state: idle / syncing / a friendly error line. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Local record of remote docs deleted while the remote delete may not have landed (offline /
 * transient failure). [SyncService.syncNow]'s union-merge would otherwise re-pull a session/subject
 * the user already deleted, because the remote copy is still present. Keyed by the record's natural
 * key (session `id` / subject `code`), per collection, persisted in the same EncryptedSharedPreferences
 * as the auth tokens. A tombstone is cleared once the remote delete is confirmed (or the doc is
 * already gone). Mirrors iOS `CloudTombstones`.
 */
internal class CloudTombstones(private val prefs: SharedPreferences) {
    private fun prefsKey(collection: String) = "monivitals.cloud.tombstones.$collection"

    /** The set of tombstoned natural keys for a collection. */
    fun keys(collection: String): Set<String> =
        prefs.getStringSet(prefsKey(collection), emptySet())?.toSet() ?: emptySet()

    /** Mark a natural key deleted so it is suppressed from re-pull until the remote delete lands. */
    fun add(key: String, collection: String) {
        val s = keys(collection)
        if (key in s) return
        prefs.edit().putStringSet(prefsKey(collection), s + key).apply()
    }

    /** Clear a tombstone once the remote delete has landed (or the doc is already gone). */
    fun remove(key: String, collection: String) {
        val s = keys(collection)
        if (key !in s) return
        prefs.edit().putStringSet(prefsKey(collection), s - key).apply()
    }

    /** Drop every tombstone (a full local/remote wipe or sign-out makes them moot). */
    fun clearAll() {
        val e = prefs.edit()
        for (collection in COLLECTIONS) e.remove(prefsKey(collection))
        e.apply()
    }

    companion object {
        val COLLECTIONS = listOf("subjects", "sessions")
    }
}

class SyncService(context: Context, private val auth: AuthService) {
    private val appContext = context.applicationContext
    private val store: Store get() = Store.shared
    // Same encrypted file as AuthService (lastSyncAt lives beside the tokens). Lazy so
    // construction doesn't do the Keystore-backed create on the main thread (warmed off-main
    // at startup via AuthPrefs.warm — see MainActivity).
    private val prefs by lazy { AuthPrefs.get(appContext) }
    // Offline-delete tombstones live beside the auth tokens (same encrypted file). Lazy for the
    // same reason as `prefs` — avoid the Keystore-backed create on the main thread.
    private val tombstones by lazy { CloudTombstones(prefs) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val inFlight = AtomicBoolean(false)

    /** Epoch millis of the last completed sync (0 = never); lives beside the auth tokens. */
    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0L)
        private set(value) { prefs.edit().putLong("lastSyncAt", value).apply() }

    private val base =
        "https://firestore.googleapis.com/v1/projects/${FirebaseConfig.projectId}/databases/(default)/documents"

    // MARK: - Sync

    /**
     * Pull + merge + push all three collections. Serialized: a call while another sync is
     * in flight returns false immediately (never two syncs at once). Also false when
     * signed out (including a revoked refresh token).
     */
    suspend fun syncNow(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        try {
            val a = auth.freshAuth() ?: return false
            mapped {
                syncCollection(a, "subjects", Subject.serializer(), store.listSubjects(),
                    key = { it.code }, compare = { x, y -> compareIso(x.createdAt, y.createdAt) }) { store.upsertSubject(it) }
                // Sessions gain an endedAt after their first push; the composite stamp
                // (startedAt, then endedAt) lets the closed copy win the collision.
                syncCollection(a, "sessions", Session.serializer(), store.listSessions(),
                    key = { it.id },
                    compare = { x, y -> compareSessionStamps(x.startedAt, x.endedAt, y.startedAt, y.endedAt) }) { store.createSession(it) }
            }
            lastSyncAt = System.currentTimeMillis()
            return true
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun <T> syncCollection(
        a: AuthState,
        coll: String,
        serializer: KSerializer<T>,
        local: List<T>,
        key: (T) -> String,
        compare: (T, T) -> Int,
        writeLocal: (T) -> Unit,
    ) {
        val docs = listDocs(a, coll)
        // "Present" is derived from the raw remote doc-id list (decoded to natural keys), not from
        // the decoded records — so an undecodable-but-present doc still keeps its tombstone rather
        // than looking gone and clearing it prematurely.
        val presentKeys = docs.mapNotNull { it.docKey }.toSet()
        val remote = docs.mapNotNull { doc ->
            // Records travel as one JSON blob; skip anything this build can't decode.
            val blob = doc.fields["json"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            try {
                json.decodeFromString(serializer, blob)
            } catch (e: Exception) {
                null
            }
        }
        // Suppress any record the user deleted while offline from being re-pulled, and retry its
        // remote delete so the tombstone can eventually clear.
        val tomb = tombstones.keys(coll)
        reconcileTombstones(a, coll, tomb, presentKeys)
        val filtered = filterTombstoned(remote, tomb, key)
        val plan = planMerge(local, filtered, key, compare)
        for (rec in plan.toLocal) writeLocal(rec)
        for (rec in plan.toPush) {
            upsert(a, coll, cloudDocId(key(rec)), json.encodeToString(serializer, rec))
        }
    }

    /**
     * For each tombstoned key: if the doc is no longer present remotely the delete already landed →
     * clear the tombstone; if it is still present, retry the remote delete and clear the tombstone on
     * success. Keys that stay present (delete failed again) keep their tombstone so re-pull stays
     * suppressed until the next pass. Mirrors iOS `reconcileTombstones`.
     */
    private suspend fun reconcileTombstones(
        a: AuthState,
        coll: String,
        tomb: Set<String>,
        present: Set<String>,
    ) {
        for (key in tomb) {
            if (key !in present) {
                tombstones.remove(key, coll)
                continue
            }
            try {
                CloudHttp.delete("$base/users/${a.uid}/$coll/${cloudDocId(key)}", a.idToken)
                tombstones.remove(key, coll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Still couldn't delete — keep the tombstone so the merge below keeps suppressing it.
            }
        }
    }

    /** One remote doc: its natural key (decoded doc id, null if undecodable) plus its `fields`. */
    private data class RemoteDoc(val docKey: String?, val fields: JsonObject)

    /** All docs in a collection, following nextPageToken; missing collection → empty. */
    private suspend fun listDocs(a: AuthState, coll: String): List<RemoteDoc> {
        val out = mutableListOf<RemoteDoc>()
        var pageToken: String? = null
        do {
            var url = "$base/users/${a.uid}/$coll?pageSize=300"
            pageToken?.let { url += "&pageToken=" + URLEncoder.encode(it, "UTF-8") }
            val obj = CloudHttp.getJson(url, a.idToken)
            obj["documents"]?.jsonArray?.forEach { doc ->
                val fields = doc.jsonObject["fields"]?.jsonObject ?: return@forEach
                // Natural key comes from the raw doc id (last path segment), decoded — independent of
                // whether this build can decode the blob, so a "present" doc keeps its tombstone.
                val docId = doc.jsonObject["name"]?.jsonPrimitive?.content?.substringAfterLast('/')
                val docKey = docId?.let { runCatching { cloudDocIdDecode(it) }.getOrNull() }
                out.add(RemoteDoc(docKey, fields))
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    private suspend fun upsert(a: AuthState, coll: String, docId: String, blob: String) {
        val body = buildJsonObject {
            putJsonObject("fields") {
                putJsonObject("json") { put("stringValue", blob) }
                putJsonObject("updatedAt") { put("stringValue", Instant.now().toString()) }
            }
        }.toString()
        CloudHttp.patchJson("$base/users/${a.uid}/$coll/$docId", body, a.idToken)
    }

    // MARK: - Remote deletes (best effort). A local tombstone suppresses the doc from being
    // re-pulled by the merge until the remote delete is confirmed (see [reconcileTombstones] +
    // [CloudTombstones]), so an offline delete can't bring a cloud copy back on the next sync.

    suspend fun deleteSessionRemote(sessionId: String) {
        // Tombstone first: if the network delete below fails (offline / transient), the next sync
        // still won't re-pull this session.
        tombstones.add(sessionId, "sessions")
        val a = auth.freshAuth() ?: return
        try {
            CloudHttp.delete("$base/users/${a.uid}/sessions/${cloudDocId(sessionId)}", a.idToken)
            tombstones.remove(sessionId, "sessions")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Leave the tombstone; a later sync retries via reconcileTombstones.
        }
    }

    /** Subject delete cascades: its sessions go too (ids captured before the local delete). */
    suspend fun deleteSubjectRemote(code: String, sessionIds: List<String>) {
        // Tombstone the subject and each of its sessions first so a failed delete isn't re-pulled.
        tombstones.add(code, "subjects")
        for (id in sessionIds) tombstones.add(id, "sessions")
        val a = auth.freshAuth() ?: return
        for (id in sessionIds) {
            try {
                CloudHttp.delete("$base/users/${a.uid}/sessions/${cloudDocId(id)}", a.idToken)
                tombstones.remove(id, "sessions")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the session tombstone for the next sync.
            }
        }
        try {
            CloudHttp.delete("$base/users/${a.uid}/subjects/${cloudDocId(code)}", a.idToken)
            tombstones.remove(code, "subjects")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the subject tombstone; a later sync retries it.
        }
    }

    /** Best-effort wipe of everything under users/{uid} (delete-all + account deletion). */
    suspend fun deleteAllRemote() {
        // Everything is going away, so any outstanding per-doc tombstones are moot.
        tombstones.clearAll()
        val a = auth.freshAuth() ?: return
        mapped {
            for (coll in listOf("subjects", "sessions")) {
                for (name in listDocNames(a, coll)) {
                    try {
                        CloudHttp.delete("https://firestore.googleapis.com/v1/$name", a.idToken)
                    } catch (e: Exception) {
                        // best-effort loop; keep going
                    }
                }
            }
        }
    }

    /**
     * Drop every offline-delete tombstone. Called on sign-out (the `clearLastSync` equivalent) so a
     * different account signed in on this device never inherits this account's deleted-doc set.
     */
    fun clearTombstones() {
        tombstones.clearAll()
    }

    /** Full resource names of every doc in a collection (for the delete loops). */
    private suspend fun listDocNames(a: AuthState, coll: String): List<String> {
        val out = mutableListOf<String>()
        var pageToken: String? = null
        do {
            var url = "$base/users/${a.uid}/$coll?pageSize=300"
            pageToken?.let { url += "&pageToken=" + URLEncoder.encode(it, "UTF-8") }
            val obj = CloudHttp.getJson(url, a.idToken)
            obj["documents"]?.jsonArray?.forEach { doc ->
                doc.jsonObject["name"]?.jsonPrimitive?.content?.let { out.add(it) }
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    /** Convert transport errors into a single warm, user-ready [CloudException]. */
    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudException) {
        throw e
    } catch (e: Exception) {
        throw CloudException(CLOUD_OFFLINE_MESSAGE)
    }
}
