//
//  AppleSignIn.kt
//  MoniVitals (Android)
//
//  Sign in with Apple on Android via a Custom Tabs web OAuth flow — Apple has no native
//  Android SDK. We obtain an Apple `id_token` in the browser, then AuthService exchanges it
//  via Identity Toolkit signInWithIdp (providerId apple.com) with the RAW nonce
//  (docs/CLOUD_OAUTH.md).
//
//  GATED OFF until the human completes the Apple Developer portal + Firebase console setup
//  (Services ID, .p8 key). While FirebaseConfig.APPLE_ANDROID_ENABLED is false the button
//  shows the friendly "isn't set up yet" line and this flow is never entered.
//
//  REDIRECT URI (registered with Apple as the Services ID return URL, and in the Firebase
//  console): https://monivitals.firebaseapp.com/__/auth/handler — the Firebase auth handler.
//  Apple posts the fragment there; the handler round-trips it back to this app via the
//  custom-scheme deep link declared in AndroidManifest.xml:
//      com.monivitals.app://apple-callback
//  The intent-filter on MainActivity captures that deep link and completeAppleRedirect()
//  parses the id_token from the fragment/query.
//

package com.monivitals.cloud

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Custom-scheme deep link Apple's redirect ultimately returns to (see class header). */
const val APPLE_REDIRECT_SCHEME = "com.monivitals.app"
const val APPLE_REDIRECT_HOST = "apple-callback"
const val APPLE_REDIRECT_URI = "$APPLE_REDIRECT_SCHEME://$APPLE_REDIRECT_HOST"

/**
 * One in-flight Apple sign-in attempt. The RAW nonce and `state` are generated up front;
 * [rawNonce] is what we later hand to signInWithIdp, [state] is echoed back for CSRF safety.
 */
data class AppleAuthAttempt(val rawNonce: String, val state: String)

object AppleSignIn {

    /**
     * Build Apple's authorize URL. The URL nonce is hex(sha256(rawNonce)); the RAW nonce is
     * kept in [AppleAuthAttempt] for the later token exchange. Pure enough to unit-test.
     */
    fun authorizeUrl(attempt: AppleAuthAttempt, redirectUri: String): String {
        val nonceHash = appleNonceHash(attempt.rawNonce)
        return "https://appleid.apple.com/auth/authorize" +
            "?response_type=" + urlEncode("code id_token") +
            "&response_mode=fragment" +
            "&client_id=" + urlEncode(FirebaseConfig.appleServiceId) +
            "&redirect_uri=" + urlEncode(redirectUri) +
            "&scope=" + urlEncode("name email") +
            "&state=" + urlEncode(attempt.state) +
            "&nonce=" + urlEncode(nonceHash)
    }

    /** Fresh attempt with a secure RAW nonce and random state token. */
    fun newAttempt(): AppleAuthAttempt =
        AppleAuthAttempt(rawNonce = newRawNonce(), state = base64UrlNoPad(sha256(newRawNonce())))

    /**
     * Open the Apple authorize page in a Custom Tab. When [FirebaseConfig.APPLE_ANDROID_ENABLED]
     * is false this throws the friendly gate message and opens nothing. Returns the [attempt]
     * whose rawNonce the redirect handler will need.
     *
     * Note: the redirect capture (MainActivity intent-filter → [parseIdToken]) and the
     * subsequent completeIdpSignIn call are wired only once the provider is enabled; the gate
     * keeps users from ever reaching a browser that can only fail.
     */
    fun launch(activity: Activity): AppleAuthAttempt {
        if (!FirebaseConfig.APPLE_ANDROID_ENABLED) {
            throw CloudException(APPLE_NOT_SET_UP_MESSAGE)
        }
        val attempt = newAttempt()
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(activity, Uri.parse(authorizeUrl(attempt, APPLE_REDIRECT_URI)))
        return attempt
    }

    /**
     * Extract the Apple `id_token` from the redirect deep link (fragment or query). Delegates
     * to the pure [redirectParam] so it's unit-testable without android.net.Uri.
     */
    fun parseIdToken(redirect: String): String? = redirectParam(redirect, "id_token")
}
