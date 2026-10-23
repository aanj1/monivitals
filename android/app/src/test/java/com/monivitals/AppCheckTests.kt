//
//  AppCheckTests.kt
//  MoniVitals (Android)
//
//  JVM tests for the pure App Check helpers (AppCheckService.kt): ttl parsing, exchange
//  response parsing, challenge extraction, the cache-freshness margin, and the post-failure
//  mint cooldown. No network, no Android framework.
//

package com.monivitals

import com.monivitals.cloud.AppCheckToken
import com.monivitals.cloud.APP_CHECK_FAILURE_COOLDOWN_MS
import com.monivitals.cloud.APP_CHECK_REFRESH_MARGIN_MS
import com.monivitals.cloud.appCheckInFailureCooldown
import com.monivitals.cloud.appCheckTokenFresh
import com.monivitals.cloud.parseAppCheckChallenge
import com.monivitals.cloud.parseAppCheckExchange
import com.monivitals.cloud.parseAppCheckTtlMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

class AppCheckTtlTests {
    @Test
    fun parsesWholeSeconds() {
        assertEquals(3_600_000L, parseAppCheckTtlMillis("3600s"))
        assertEquals(1_800_000L, parseAppCheckTtlMillis("1800s"))
    }

    @Test
    fun parsesFractionalSeconds() {
        // The API sometimes returns fractional ttls like "3599.5s".
        assertEquals(3_599_500L, parseAppCheckTtlMillis("3599.5s"))
    }

    @Test
    fun missingOrMalformedFallsBackToAnHour() {
        assertEquals(3_600_000L, parseAppCheckTtlMillis(null))
        assertEquals(3_600_000L, parseAppCheckTtlMillis(""))
        assertEquals(3_600_000L, parseAppCheckTtlMillis("soon"))
        assertEquals(3_600_000L, parseAppCheckTtlMillis("-5s"))
        assertEquals(3_600_000L, parseAppCheckTtlMillis("0s"))
    }
}

class AppCheckExchangeParseTests {
    @Test
    fun parsesV1TokenShape() {
        val now = 1_000_000L
        val t = parseAppCheckExchange(obj("""{"token":"tok-abc","ttl":"3600s"}"""), now)
        assertEquals("tok-abc", t?.token)
        assertEquals(now + 3_600_000L, t?.expiresAt)
    }

    @Test
    fun parsesLegacyAttestationTokenShape() {
        val now = 5_000L
        val t = parseAppCheckExchange(obj("""{"attestationToken":"tok-old","ttl":"1800s"}"""), now)
        assertEquals("tok-old", t?.token)
        assertEquals(now + 1_800_000L, t?.expiresAt)
    }

    @Test
    fun preferTokenOverAttestationTokenWhenBothPresent() {
        val t = parseAppCheckExchange(obj("""{"token":"new","attestationToken":"old","ttl":"60s"}"""), 0L)
        assertEquals("new", t?.token)
    }

    @Test
    fun missingTtlDefaultsToAnHour() {
        val t = parseAppCheckExchange(obj("""{"token":"tok"}"""), 0L)
        assertEquals(3_600_000L, t?.expiresAt)
    }

    @Test
    fun missingOrBlankTokenReturnsNull() {
        assertNull(parseAppCheckExchange(obj("""{"ttl":"3600s"}"""), 0L))
        assertNull(parseAppCheckExchange(obj("""{"token":"","ttl":"3600s"}"""), 0L))
        assertNull(parseAppCheckExchange(obj("{}"), 0L))
    }
}

class AppCheckChallengeParseTests {
    @Test
    fun extractsChallenge() {
        assertEquals("nonce-123", parseAppCheckChallenge(obj("""{"challenge":"nonce-123","ttl":"180s"}""")))
    }

    @Test
    fun missingOrBlankChallengeReturnsNull() {
        assertNull(parseAppCheckChallenge(obj("{}")))
        assertNull(parseAppCheckChallenge(obj("""{"challenge":""}""")))
    }
}

class AppCheckCacheFreshnessTests {
    private val now = 1_000_000_000L

    @Test
    fun freshWhenMoreThanFiveMinutesRemain() {
        val t = AppCheckToken("tok", now + APP_CHECK_REFRESH_MARGIN_MS + 1)
        assertTrue(appCheckTokenFresh(t, now))
    }

    @Test
    fun staleAtExactlyFiveMinutesLeft() {
        // Under-or-equal to the margin refreshes proactively (same rule as AuthService).
        val t = AppCheckToken("tok", now + APP_CHECK_REFRESH_MARGIN_MS)
        assertFalse(appCheckTokenFresh(t, now))
    }

    @Test
    fun staleWhenExpiredOrMissing() {
        assertFalse(appCheckTokenFresh(AppCheckToken("tok", now - 1), now))
        assertFalse(appCheckTokenFresh(null, now))
    }

    @Test
    fun typicalMintIsFreshForMostOfItsHour() {
        val minted = AppCheckToken("tok", now + 3_600_000L)
        assertTrue(appCheckTokenFresh(minted, now))
        // 56 minutes in: only ~4 remain, so the cache must refresh.
        assertFalse(appCheckTokenFresh(minted, now + 56 * 60 * 1000L))
    }
}

class AppCheckCooldownTests {
    private val now = 1_000_000_000L

    @Test
    fun noCooldownBeforeAnyFailure() {
        // lastFailureAt == 0 means "never failed" — always allowed to mint.
        assertFalse(appCheckInFailureCooldown(0L, now))
    }

    @Test
    fun suppressesRetryImmediatelyAfterFailure() {
        assertTrue(appCheckInFailureCooldown(now, now))
        // Still inside the window a moment later.
        assertTrue(appCheckInFailureCooldown(now, now + 1_000L))
    }

    @Test
    fun allowsRetryOnceCooldownElapses() {
        // Exactly at the boundary the window is over (strict <), so minting is allowed again.
        assertFalse(appCheckInFailureCooldown(now, now + APP_CHECK_FAILURE_COOLDOWN_MS))
        assertFalse(appCheckInFailureCooldown(now, now + APP_CHECK_FAILURE_COOLDOWN_MS + 1L))
    }

    @Test
    fun cooldownIsOneMinute() {
        // Mirrors iOS failureCooldownSeconds = 60.
        assertEquals(60 * 1000L, APP_CHECK_FAILURE_COOLDOWN_MS)
    }
}
