//
//  CloudLogicTests.kt
//  MoniVitals (Android)
//
//  JVM tests for the pure cloud helpers (docs/CLOUD_SYNC.md): base64url doc ids, the
//  union/newer-wins merge planner, and the friendly auth error copy. No network, no
//  Android framework.
//

package com.monivitals

import com.monivitals.cloud.CLOUD_OFFLINE_MESSAGE
import com.monivitals.cloud.cloudDocId
import com.monivitals.cloud.cloudDocIdDecode
import com.monivitals.cloud.compareSessionStamps
import com.monivitals.cloud.filterTombstoned
import com.monivitals.cloud.friendlyAuthError
import com.monivitals.cloud.planMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDocIdTests {
    @Test
    fun encodesKnownVector() {
        assertEquals("UzAx", cloudDocId("S01"))
    }

    @Test
    fun slashesNeverReachTheDocId() {
        // Subject codes are user-entered; a "/" would break a Firestore path.
        val id = cloudDocId("ward-3/bed-12")
        assertFalse(id.contains("/"))
        assertFalse(id.contains("+"))
        assertFalse(id.contains("="))
        assertEquals("ward-3/bed-12", cloudDocIdDecode(id))
    }

    @Test
    fun unicodeRoundTrips() {
        val key = "対象者-α#2026-08-12T10:00:00Z"
        val id = cloudDocId(key)
        // base64url alphabet only: letters, digits, "-", "_"
        assertTrue(id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
        assertEquals(key, cloudDocIdDecode(id))
    }
}

private data class Rec(val key: String, val at: String, val v: String)

class MergePlanTests {
    private fun plan(local: List<Rec>, remote: List<Rec>) =
        planMerge(local, remote, key = { it.key }, stamp = { it.at })

    @Test
    fun unionPullsMissingBothWays() {
        val l = Rec("a", "2026-01-01T00:00:00Z", "local")
        val r = Rec("b", "2026-01-02T00:00:00Z", "remote")
        val p = plan(listOf(l), listOf(r))
        assertEquals(listOf(r), p.toLocal)
        assertEquals(listOf(l), p.toPush)
    }

    @Test
    fun newerRemoteWinsCollision() {
        val l = Rec("a", "2026-01-01T00:00:00Z", "old-local")
        val r = Rec("a", "2026-01-03T00:00:00Z", "new-remote")
        val p = plan(listOf(l), listOf(r))
        assertEquals(listOf(r), p.toLocal)
        assertTrue(p.toPush.isEmpty())
    }

    @Test
    fun newerLocalPushes() {
        val l = Rec("a", "2026-01-05T00:00:00Z", "new-local")
        val r = Rec("a", "2026-01-01T00:00:00Z", "old-remote")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertEquals(listOf(l), p.toPush)
    }

    @Test
    fun equalTimestampsKeepLocalAndPushNothing() {
        val l = Rec("a", "2026-01-01T00:00:00Z", "local")
        val r = Rec("a", "2026-01-01T00:00:00Z", "remote")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertTrue(p.toPush.isEmpty())
    }

    @Test
    fun fractionalSecondsCompareAsInstantsNotStrings() {
        // Lexicographically "…00Z" > "…00.500Z", but as an instant the fractional one is later.
        val l = Rec("a", "2026-01-01T00:00:00Z", "local")
        val r = Rec("a", "2026-01-01T00:00:00.500Z", "remote")
        val p = plan(listOf(l), listOf(r))
        assertEquals(listOf(r), p.toLocal)
        assertTrue(p.toPush.isEmpty())
    }
}

/** Offline-delete tombstone suppression (pure part; mirrors iOS CloudSyncTests). */
class TombstoneFilterTests {
    private fun plan(local: List<Rec>, remote: List<Rec>) =
        planMerge(local, remote, key = { it.key }, stamp = { it.at })

    @Test
    fun tombstoneSuppressesRePullOfDeletedRemote() {
        // A session deleted locally while offline is still present remotely; the merge must not
        // re-pull it.
        val deleted = Rec("s1", "2024-01-01T00:00:00Z", "deleted-remote")
        val kept = Rec("s2", "2024-01-02T00:00:00Z", "kept-remote")
        val filtered = filterTombstoned(listOf(deleted, kept), setOf("s1"), key = { it.key })
        assertEquals(listOf(kept), filtered)
        // And the union-merge over the filtered remote never plans a pull for s1.
        val p = plan(emptyList(), filtered)
        assertEquals(listOf(kept), p.toLocal)
        assertFalse(p.toLocal.any { it.key == "s1" })
    }

    @Test
    fun emptyTombstonesLeaveRemoteUntouched() {
        val a = Rec("a", "2024-01-01T00:00:00Z", "a")
        val b = Rec("b", "2024-01-02T00:00:00Z", "b")
        assertEquals(listOf(a, b), filterTombstoned(listOf(a, b), emptySet(), key = { it.key }))
    }

    @Test
    fun multipleTombstonesAllSuppressed() {
        val recs = listOf(
            Rec("s1", "2024-01-01T00:00:00Z", "a"),
            Rec("s2", "2024-01-02T00:00:00Z", "b"),
            Rec("s3", "2024-01-03T00:00:00Z", "c"),
        )
        val filtered = filterTombstoned(recs, setOf("s1", "s3"), key = { it.key })
        assertEquals(listOf(recs[1]), filtered)
    }
}

private data class Sess(val id: String, val startedAt: String, val endedAt: String?, val v: String)

/** Sessions merge on the composite stamp: primary startedAt, tie-break endedAt ?? "". */
class SessionMergeStampTests {
    private fun plan(local: List<Sess>, remote: List<Sess>) =
        planMerge(local, remote, key = { it.id },
            compare = { a, b -> compareSessionStamps(a.startedAt, a.endedAt, b.startedAt, b.endedAt) })

    @Test
    fun endedAtSetAfterFirstPushRePushes() {
        // A session is pushed while still open (endedAt null); once it ends locally the
        // closed copy must beat the open remote copy despite identical startedAt.
        val l = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "local-closed")
        val r = Sess("s1", "2026-01-01T00:00:00Z", null, "remote-open")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertEquals(listOf(l), p.toPush)
    }

    @Test
    fun remoteClosedCopyWinsOverLocalOpenCopy() {
        val l = Sess("s1", "2026-01-01T00:00:00Z", null, "local-open")
        val r = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "remote-closed")
        val p = plan(listOf(l), listOf(r))
        assertEquals(listOf(r), p.toLocal)
        assertTrue(p.toPush.isEmpty())
    }

    @Test
    fun laterEndedAtWinsTheTieBreak() {
        val l = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:09:00Z", "local-later-end")
        val r = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "remote-earlier-end")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertEquals(listOf(l), p.toPush)
    }

    @Test
    fun equalStartedAndEndedKeepsLocalAndPushesNothing() {
        val l = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "local")
        val r = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "remote")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertTrue(p.toPush.isEmpty())
    }

    @Test
    fun bothStillOpenIsAlsoATieKeepLocal() {
        val l = Sess("s1", "2026-01-01T00:00:00Z", null, "local")
        val r = Sess("s1", "2026-01-01T00:00:00Z", null, "remote")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertTrue(p.toPush.isEmpty())
    }

    @Test
    fun laterStartedAtStillDominatesEndedAt() {
        // Primary key of the composite is startedAt: a later start beats an earlier
        // start even when only the earlier one is closed.
        val l = Sess("s1", "2026-01-02T00:00:00Z", null, "local-later-open")
        val r = Sess("s1", "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", "remote-earlier-closed")
        val p = plan(listOf(l), listOf(r))
        assertTrue(p.toLocal.isEmpty())
        assertEquals(listOf(l), p.toPush)
    }
}

class AuthErrorCopyTests {
    @Test
    fun mapsKnownCodes() {
        assertEquals("That email already has an account — try signing in instead.", friendlyAuthError("EMAIL_EXISTS"))
        assertEquals("That email and password don't match — have another look and try again.", friendlyAuthError("INVALID_LOGIN_CREDENTIALS"))
        assertEquals("That email and password don't match — have another look and try again.", friendlyAuthError("EMAIL_NOT_FOUND"))
        assertEquals("Passwords need at least 6 characters.", friendlyAuthError("WEAK_PASSWORD : Password should be at least 6 characters"))
        assertEquals("That doesn't look like an email address yet.", friendlyAuthError("INVALID_EMAIL"))
        assertEquals("Lots of tries in a row — take a short break and try again.", friendlyAuthError("TOO_MANY_ATTEMPTS_TRY_LATER"))
        assertEquals("Cloud accounts aren't switched on for this build yet.", friendlyAuthError("CONFIGURATION_NOT_FOUND"))
    }

    @Test
    fun unknownOrMissingFallsBackToOfflineLine() {
        assertEquals(CLOUD_OFFLINE_MESSAGE, friendlyAuthError("SOMETHING_NEW"))
        assertEquals(CLOUD_OFFLINE_MESSAGE, friendlyAuthError(null))
    }
}
