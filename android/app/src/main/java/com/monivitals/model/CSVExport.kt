//
//  CSVExport.kt
//  MoniVitals (Android)
//
//  CSV export of a saved reading. Pure builders (testable without a store) plus a
//  store-reading entry point that writes a shareable .zip bundle. Column order is fixed; blank
//  numeric cells denote "not available" (not zero). Numbers are formatted the JavaScript way
//  (integers without a trailing ".0").
//

package com.monivitals.model

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Everything needed to build a bundle. */
data class BundleData(
    val session: Session,
    val subject: Subject?,
    val streams: SessionStreams,
    val metrics: List<MetricsRecord>,
    val annotations: List<Annotation>,
)

object CSVExport {
    private val prettyJson = Json { prettyPrint = true }

    // MARK: - number / field formatting

    /**
     * Format a Double like JavaScript's `String(number)`: integers have no decimals, other
     * values use the shortest round-tripping representation.
     */
    fun jsNumber(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x == Math.rint(x) && abs(x) < 1e15) {
            return x.toLong().toString()
        }
        var s = String.format(Locale.ROOT, "%.15g", x)
        if (s.contains(".")) {
            while (s.endsWith("0")) s = s.dropLast(1)
            if (s.endsWith(".")) s = s.dropLast(1)
        }
        return s
    }

    /** RFC-4180 field escaping; null → empty cell. */
    fun csvEscape(v: String?): String {
        if (v == null) return ""
        return if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }

    fun csvEscape(v: Double?): String = if (v == null) "" else jsNumber(v)

    private fun toCsv(header: List<String>, rows: List<List<String>>): String {
        val lines = mutableListOf(header.joinToString(","))
        for (row in rows) lines.add(row.joinToString(","))
        return lines.joinToString("\n") + "\n"
    }

    // MARK: - session_meta.json

    fun buildSessionMeta(d: BundleData): String {
        val root = buildJsonObject {
            put("schemaVersion", 2)
            put("session", buildJsonObject {
                put("id", d.session.id)
                put("subjectCode", d.session.subjectCode)
                put("label", d.session.label.rawValue)
                put("startedAt", d.session.startedAt)
                if (d.session.endedAt != null) put("endedAt", d.session.endedAt) else put("endedAt", JsonNull)
                put("notes", d.session.notes)
            })
            val subj = d.subject
            put("subject", if (subj != null) {
                buildJsonObject {
                    put("code", subj.code)
                    put("ageBand", subj.ageBand)
                    if (subj.sex != null) put("sex", subj.sex) else put("sex", JsonNull)
                    put("notes", subj.notes)
                }
            } else {
                buildJsonObject { put("code", d.session.subjectCode) }
            })
        }
        return prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    // MARK: - bundle files

    /** Build the in-memory bundle: filename → file contents. */
    fun buildBundleFiles(d: BundleData): List<Pair<String, String>> {
        val s = d.streams

        val ppgRows = mutableListOf<List<String>>()
        for (i in s.ppg.t.indices) {
            ppgRows.add(listOf(jsNumber(s.ppg.t[i]), jsNumber(s.ppg.green[i]), jsNumber(s.ppg.red[i])))
        }

        val metricsRows = d.metrics.map { m ->
            listOf(
                jsNumber(m.tUs),
                csvEscape(m.hrBpm),
                csvEscape(m.respRateBpm),
                csvEscape(m.hrvMs),
                csvEscape(m.hrvSdnnMs),
                m.contactQuality.toString(),
                m.movement.toString(),
            )
        }

        val annoRows = d.annotations.map { a ->
            listOf(jsNumber(a.tUs), csvEscape(a.type.rawValue), csvEscape(a.text))
        }

        return listOf(
            "session_meta.json" to buildSessionMeta(d),
            "pulse.csv" to toCsv(listOf("t_us", "green", "red"), ppgRows),
            "metrics.csv" to toCsv(
                listOf("t_us", "hr_bpm", "resp_bpm", "hrv_rmssd_ms", "hrv_sdnn_ms", "signal_quality", "movement"),
                metricsRows,
            ),
            "markers.csv" to toCsv(listOf("t_us", "type", "text"), annoRows),
        )
    }

    /** The bundle base name: `<label>_<first 8 of id>`. */
    fun bundleFolderName(session: Session): String =
        "${session.label.rawValue}_${session.id.take(8)}"

    // MARK: - store-reading entry point

    /**
     * Read a reading from the store and write a .zip bundle into the app cache. Returns the zip
     * File (share it via FileProvider), or null on failure.
     */
    fun exportSession(sessionId: String, context: Context, store: Store = Store.shared): File? {
        val session = store.getSession(sessionId) ?: return null
        val subject = store.getSubject(session.subjectCode)
        val streams = store.readSessionStreams(sessionId)
        val metrics = store.listMetrics(sessionId)
        val annotations = store.listAnnotations(sessionId)
        val files = buildBundleFiles(
            BundleData(
                session = session,
                subject = subject,
                streams = streams,
                metrics = metrics,
                annotations = annotations,
            )
        )

        val name = bundleFolderName(session)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(dir, "$name.zip")
        return try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                for ((fname, content) in files) {
                    zos.putNextEntry(ZipEntry("$name/$fname"))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            null
        }
    }
}
