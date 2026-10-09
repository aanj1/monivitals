//
//  Store.kt
//  MoniVitals (Android)
//
//  Dependency-light, local-first persistence. Stores data as JSON files under the app's
//  private files directory:
//
//      <filesDir>/MoniVitals/
//        subjects.json          [Subject]
//        sessions.json          [Session]
//        annotations.json       [Annotation]
//        chunks/<sessionId>.json     [StreamChunk]   (pulse sample blocks)
//        metrics/<sessionId>.json    [MetricsRecord]
//
//  Per-session sample data lives in its own file so listing/deleting sessions never loads
//  every pulse trace. All access is serialized on a lock.
//

package com.monivitals.model

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Concatenated, contiguous pulse trace for a session (review / export). */
class SessionStreams {
    class Ppg {
        val t = mutableListOf<Double>()
        val green = mutableListOf<Double>()
        val red = mutableListOf<Double>()
    }

    val ppg = Ppg()
}

class Store private constructor(baseDir: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val root = File(baseDir, "MoniVitals")
    private val chunksDir = File(root, "chunks")
    private val metricsDir = File(root, "metrics")

    // Live-recording write buffering: addMetrics (every 0.25 s) and addChunk (every few
    // seconds) used to rewrite the whole session JSON on every call — O(n²) over a
    // recording. Records now buffer in memory and flush to disk on a background thread
    // every ~10 s, on any read of the same table, and on stop (Recorder.finish →
    // flushPending).
    private val pendingMetrics = HashMap<String, MutableList<MetricsRecord>>()
    private val pendingChunks = HashMap<String, MutableList<StreamChunk>>()
    // Sessions deleted while a Recorder io-task was still in flight. A chunk/metrics append
    // that lands AFTER deleteSession would otherwise re-create chunks/<id>.json as an orphan,
    // so buffered writes for these ids are dropped and their files kept deleted.
    private val deletedSessions = HashSet<String>()
    private var flushScheduled = false
    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "monivitals-store-flush").apply { isDaemon = true }
    }

    init {
        root.mkdirs()
        chunksDir.mkdirs()
        metricsDir.mkdirs()
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 10_000L

        @Volatile private var instance: Store? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = Store(context.filesDir)
                }
            }
        }

        val shared: Store
            get() = instance ?: error("Store.init(context) must be called before Store.shared")
    }

    // MARK: - file helpers

    private fun file(name: String) = File(root, name)

    private val subjectsList = ListSerializer(Subject.serializer())
    private val sessionsList = ListSerializer(Session.serializer())
    private val annosList = ListSerializer(Annotation.serializer())
    private val chunksList = ListSerializer(StreamChunk.serializer())
    private val metricsList = ListSerializer(MetricsRecord.serializer())

    private fun <T> load(f: File, serializer: kotlinx.serialization.KSerializer<List<T>>, def: List<T>): List<T> {
        if (!f.exists()) return def
        return try {
            json.decodeFromString(serializer, f.readText())
        } catch (e: Exception) {
            def
        }
    }

    private fun <T> save(value: List<T>, serializer: kotlinx.serialization.KSerializer<List<T>>, f: File) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            tmp.renameTo(f)
        } catch (e: Exception) {
            // best-effort local store
        }
    }

    private fun nextId(items: List<Int?>): Int = (items.mapNotNull { it }.maxOrNull() ?: 0) + 1

    // MARK: - Subjects

    fun upsertSubject(s: Subject) = synchronized(lock) {
        val all = load(file("subjects.json"), subjectsList, emptyList()).toMutableList()
        all.removeAll { it.code == s.code }
        all.add(s)
        save(all, subjectsList, file("subjects.json"))
    }

    /** Subjects, newest first (by createdAt). */
    fun listSubjects(): List<Subject> = synchronized(lock) {
        load(file("subjects.json"), subjectsList, emptyList()).sortedByDescending { it.createdAt }
    }

    fun getSubject(code: String): Subject? = synchronized(lock) {
        load(file("subjects.json"), subjectsList, emptyList()).firstOrNull { it.code == code }
    }

    fun deleteSubject(code: String) {
        val sessions = listSessions().filter { it.subjectCode == code }
        for (s in sessions) deleteSession(s.id)
        synchronized(lock) {
            val subs = load(file("subjects.json"), subjectsList, emptyList()).toMutableList()
            subs.removeAll { it.code == code }
            save(subs, subjectsList, file("subjects.json"))
        }
    }

    // MARK: - Sessions

    fun createSession(s: Session) = synchronized(lock) {
        deletedSessions.remove(s.id) // a fresh session may legitimately reuse a prior id
        val all = load(file("sessions.json"), sessionsList, emptyList()).toMutableList()
        all.removeAll { it.id == s.id }
        all.add(s)
        save(all, sessionsList, file("sessions.json"))
    }

    fun endSession(id: String, endedAt: String) = synchronized(lock) {
        // A finished recording must be fully on disk, buffered rows included, before it's marked
        // ended (mirrors iOS Store.endSession). Safe today because Recorder.finish() already flushes,
        // but defensive if a caller ends a session without a Recorder in the loop. Reentrant: both
        // this method and flushPending() hold `lock`.
        flushPending()
        val all = load(file("sessions.json"), sessionsList, emptyList()).toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(endedAt = endedAt)
            save(all, sessionsList, file("sessions.json"))
        }
    }

    fun getSession(id: String): Session? = synchronized(lock) {
        load(file("sessions.json"), sessionsList, emptyList()).firstOrNull { it.id == id }
    }

    /** Sessions, newest first (by startedAt). */
    fun listSessions(): List<Session> = synchronized(lock) {
        load(file("sessions.json"), sessionsList, emptyList()).sortedByDescending { it.startedAt }
    }

    fun deleteSession(id: String) = synchronized(lock) {
        pendingMetrics.remove(id)
        pendingChunks.remove(id)
        // Tombstone the id so a Recorder append still in flight can't re-create its files.
        deletedSessions.add(id)
        val sessions = load(file("sessions.json"), sessionsList, emptyList()).toMutableList()
        sessions.removeAll { it.id == id }
        save(sessions, sessionsList, file("sessions.json"))
        val annos = load(file("annotations.json"), annosList, emptyList()).toMutableList()
        annos.removeAll { it.sessionId == id }
        save(annos, annosList, file("annotations.json"))
        File(chunksDir, "$id.json").delete()
        File(metricsDir, "$id.json").delete()
    }

    // MARK: - Annotations

    fun addAnnotation(a: Annotation): Int = synchronized(lock) {
        val all = load(file("annotations.json"), annosList, emptyList()).toMutableList()
        val item = a.copy(id = nextId(all.map { it.id }))
        all.add(item)
        save(all, annosList, file("annotations.json"))
        item.id!!
    }

    fun listAnnotations(sessionId: String): List<Annotation> = synchronized(lock) {
        load(file("annotations.json"), annosList, emptyList())
            .filter { it.sessionId == sessionId }
            .sortedBy { it.tUs }
    }

    // MARK: - Metrics

    /** Buffer a metrics record; persisted by the periodic/stop-time [flushPending]. */
    fun addMetrics(m: MetricsRecord) = synchronized(lock) {
        if (m.sessionId in deletedSessions) return@synchronized // late write after delete — drop it
        pendingMetrics.getOrPut(m.sessionId) { mutableListOf() }.add(m)
        scheduleFlushLocked()
    }

    fun listMetrics(sessionId: String): List<MetricsRecord> = synchronized(lock) {
        flushPending()
        val f = File(metricsDir, "$sessionId.json")
        load(f, metricsList, emptyList()).sortedBy { it.tUs }
    }

    // MARK: - Stream chunks

    /** Buffer a sample chunk; persisted by the periodic/stop-time [flushPending]. */
    fun addChunk(chunk: StreamChunk) = synchronized(lock) {
        if (chunk.sessionId in deletedSessions) return@synchronized // late write after delete — drop it
        pendingChunks.getOrPut(chunk.sessionId) { mutableListOf() }.add(chunk)
        scheduleFlushLocked()
    }

    /**
     * Write all buffered metrics/chunks to disk (append once per touched session file).
     * Cheap when nothing is pending; safe to call from any thread.
     */
    fun flushPending(): Unit = synchronized(lock) {
        if (pendingMetrics.isNotEmpty()) {
            for ((sessionId, records) in pendingMetrics) {
                if (records.isEmpty() || sessionId in deletedSessions) continue
                val f = File(metricsDir, "$sessionId.json")
                val all = load(f, metricsList, emptyList()).toMutableList()
                var next = nextId(all.map { it.id })
                for (m in records) {
                    all.add(m.copy(id = next))
                    next += 1
                }
                save(all, metricsList, f)
            }
            pendingMetrics.clear()
        }
        if (pendingChunks.isNotEmpty()) {
            for ((sessionId, chunks) in pendingChunks) {
                if (chunks.isEmpty() || sessionId in deletedSessions) continue
                val f = File(chunksDir, "$sessionId.json")
                val all = load(f, chunksList, emptyList()).toMutableList()
                var next = nextId(all.map { it.id })
                for (c in chunks) {
                    all.add(c.copy(id = next))
                    next += 1
                }
                save(all, chunksList, f)
            }
            pendingChunks.clear()
        }
    }

    /** Arrange a background flush ~10 s out (one in flight at a time). Caller holds [lock]. */
    private fun scheduleFlushLocked() {
        if (flushScheduled) return
        flushScheduled = true
        try {
            flushExecutor.schedule({
                synchronized(lock) { flushScheduled = false }
                flushPending()
            }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            flushScheduled = false // executor shut down (process teardown) — flush on read/stop
        }
    }

    private fun chunks(sessionId: String): List<StreamChunk> {
        val f = File(chunksDir, "$sessionId.json")
        return load(f, chunksList, emptyList()).sortedBy { it.tStartUs }
    }

    /** Concatenate stored chunks back into a contiguous pulse trace. */
    fun readSessionStreams(sessionId: String): SessionStreams = synchronized(lock) {
        flushPending()
        val all = chunks(sessionId)
        val out = SessionStreams()
        for (c in all.filter { it.stream == StreamKindPpg }) {
            out.ppg.t += c.t
            out.ppg.green += c.green ?: emptyList()
            out.ppg.red += c.red ?: emptyList()
        }
        out
    }

    /** Wipe every stored table (Settings → data management). */
    fun deleteAllData() = synchronized(lock) {
        // Tombstone every known session id BEFORE wiping so that any Recorder
        // io-task still in flight has its late chunk/metrics append dropped
        // (lines checking `in deletedSessions`) instead of recreating an orphan
        // file that survives the wipe. Clearing the set — as this used to do —
        // removed the guard and let in-flight writes resurrect deleted data.
        load(file("sessions.json"), sessionsList, emptyList()).forEach { deletedSessions.add(it.id) }
        deletedSessions.addAll(pendingMetrics.keys)
        deletedSessions.addAll(pendingChunks.keys)
        pendingMetrics.clear()
        pendingChunks.clear()
        for (name in listOf("subjects.json", "sessions.json", "annotations.json")) {
            file(name).delete()
        }
        chunksDir.deleteRecursively()
        metricsDir.deleteRecursively()
        chunksDir.mkdirs()
        metricsDir.mkdirs()
    }

    internal fun chunksDir() = chunksDir
    internal fun metricsDir() = metricsDir
}

private val StreamKindPpg = com.monivitals.source.StreamKind.PPG

private fun <T> ListSerializer(elem: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(elem)
