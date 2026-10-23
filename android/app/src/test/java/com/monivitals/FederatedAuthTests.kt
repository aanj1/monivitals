//
//  FederatedAuthTests.kt
//  MoniVitals (Android)
//
//  JVM tests for the pure federated-sign-in helpers (docs/CLOUD_OAUTH.md): the
//  signInWithIdp postBody builder (Google vs Apple, nonce present/absent), the nonce
//  sha256 + base64url/hex round-trip, redirect id_token parsing, and the new error
//  mappings. No network, no Android framework.
//

package com.monivitals

import com.monivitals.cloud.APPLE_NOT_SET_UP_MESSAGE
import com.monivitals.cloud.CLOUD_OFFLINE_MESSAGE
import com.monivitals.cloud.PROVIDER_APPLE
import com.monivitals.cloud.PROVIDER_GOOGLE
import com.monivitals.cloud.appleNonceHash
import com.monivitals.cloud.base64UrlNoPad
import com.monivitals.cloud.friendlyAuthError
import com.monivitals.cloud.newRawNonce
import com.monivitals.cloud.redirectParam
import com.monivitals.cloud.sha256
import com.monivitals.cloud.signInWithIdpPostBody
import com.monivitals.cloud.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.security.SecureRandom
import org.junit.Test

class SignInWithIdpPostBodyTests {
    @Test
    fun googleBodyHasNoNonce() {
        val body = signInWithIdpPostBody("JWT123", PROVIDER_GOOGLE)
        assertEquals("id_token=JWT123&providerId=google.com", body)
    }

    @Test
    fun appleBodyIncludesRawNonce() {
        val body = signInWithIdpPostBody("JWT456", PROVIDER_APPLE, "raw-nonce-abc")
        assertEquals("id_token=JWT456&providerId=apple.com&nonce=raw-nonce-abc", body)
    }

    @Test
    fun nullNonceOmitsTheParam() {
        val body = signInWithIdpPostBody("JWT789", PROVIDER_APPLE, null)
        assertEquals("id_token=JWT789&providerId=apple.com", body)
    }

    @Test
    fun idTokenAndNonceAreUrlEncoded() {
        // A JWT never contains "&" but a defensive encode protects the form body regardless.
        val body = signInWithIdpPostBody("a b&c", PROVIDER_APPLE, "n=1")
        assertEquals("id_token=a+b%26c&providerId=apple.com&nonce=n%3D1", body)
    }
}

class NonceTests {
    @Test
    fun sha256KnownVector() {
        // Well-known: SHA-256("abc") hex.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            toHex(sha256("abc")),
        )
    }

    @Test
    fun appleNonceHashIsHexSha256OfRaw() {
        val raw = "raw-nonce-value"
        assertEquals(toHex(sha256(raw)), appleNonceHash(raw))
        // Hex sha256 is always 64 lowercase hex chars.
        assertEquals(64, appleNonceHash(raw).length)
        assertTrue(appleNonceHash(raw).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun base64UrlNoPadUsesUrlAlphabetOnly() {
        // 0xFB 0xFF would produce "+/" in standard base64; base64url must use "-_" and no "=".
        val encoded = base64UrlNoPad(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
        assertTrue(encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
        assertTrue(!encoded.contains('='))
    }

    @Test
    fun rawNonceIsDeterministicFromSeededRandom() {
        val a = newRawNonce(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        val b = newRawNonce(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        // Same seed → same nonce (proves it's a pure function of the RNG), and it's base64url.
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertTrue(!a.contains('='))
    }
}

class RedirectParamTests {
    @Test
    fun readsIdTokenFromFragment() {
        val url = "com.monivitals.app://apple-callback#state=xyz&id_token=THE_JWT&code=abc"
        assertEquals("THE_JWT", redirectParam(url, "id_token"))
    }

    @Test
    fun readsIdTokenFromQuery() {
        val url = "https://monivitals.firebaseapp.com/__/auth/handler?id_token=QUERY_JWT&state=z"
        assertEquals("QUERY_JWT", redirectParam(url, "id_token"))
    }

    @Test
    fun urlDecodesTheValue() {
        val url = "com.monivitals.app://apple-callback#id_token=a%2Bb%3Dc"
        assertEquals("a+b=c", redirectParam(url, "id_token"))
    }

    @Test
    fun missingParamIsNull() {
        val url = "com.monivitals.app://apple-callback#state=only"
        assertNull(redirectParam(url, "id_token"))
    }
}

class FederatedErrorCopyTests {
    @Test
    fun federatedAlreadyLinkedGetsItsOwnCopy() {
        assertEquals(
            "That email is already signed up a different way — try that instead.",
            friendlyAuthError("FEDERATED_USER_ID_ALREADY_LINKED"),
        )
    }

    @Test
    fun appleNotSetUpErrorsMapToTheFriendlyGate() {
        assertEquals(APPLE_NOT_SET_UP_MESSAGE, friendlyAuthError("INVALID_IDP_RESPONSE"))
        assertEquals(APPLE_NOT_SET_UP_MESSAGE, friendlyAuthError("MISSING_OR_INVALID_NONCE"))
    }

    @Test
    fun existingEmailExistsCopyStaysForPasswordPath() {
        // The password create-account path still surfaces its own line for EMAIL_EXISTS.
        assertEquals("That email already has an account — try signing in instead.", friendlyAuthError("EMAIL_EXISTS"))
    }

    @Test
    fun unknownStillFallsBackToOffline() {
        assertEquals(CLOUD_OFFLINE_MESSAGE, friendlyAuthError("SOMETHING_ELSE"))
    }
}
