//
//  FirebaseConfig.kt
//  MoniVitals (Android)
//
//  Firebase project constants for the optional cloud layer (docs/CLOUD_SYNC.md).
//  No Firebase SDK — plain HTTPS against the Identity Toolkit + Firestore REST APIs.
//  apiKey mirrors android/app/google-services.json (`current_key`).
//

package com.monivitals.cloud

object FirebaseConfig {
    /** Identity Toolkit web API key (google-services.json → current_key). */
    const val apiKey = "AIzaSyBEi7rSO84cLXoUMaCLKd1SS2l4Ep4N_Vw"

    /** Firebase project id; Firestore database `(default)`, location asia-south1. */
    const val projectId = "monivitals"

    /** Android Firebase app id (google-services.json → mobilesdk_app_id); App Check REST path. */
    const val appId = "1:981916297971:android:69ba31b1710ce9a0d4e586"

    /** Firebase Hosting auth domain; used as the signInWithIdp `requestUri` and Apple redirect host. */
    const val authDomain = "monivitals.firebaseapp.com"

    /**
     * Android/web Google **server (web) client id** — passed to Credential Manager as
     * `serverClientId` so the returned Google id_token is audienced to the web client that
     * Firebase's `google.com` provider trusts (docs/CLOUD_OAUTH.md).
     */
    const val googleWebClientId = "981916297971-9a1g4vkdi0n94mmhctcpm1i5s540hnrq.apps.googleusercontent.com"

    /**
     * Apple **Services ID** for the web OAuth flow on Android. Empty until the human sets it
     * up in the Apple Developer portal + Firebase console (see docs/CLOUD_OAUTH.md MANUAL).
     * While blank, [APPLE_ANDROID_ENABLED] is false and the Apple button shows the friendly
     * "isn't set up yet" line instead of attempting a doomed flow.
     */
    const val appleServiceId = "com.monivitals.web"

    /**
     * Gate for Android Apple sign-in. We keep it OFF until the Apple Services ID + .p8 key
     * are configured server-side; flipping it to true (once the portal/console steps in
     * docs/CLOUD_OAUTH.md are done) enables the Custom-Tabs flow. Chosen over runtime
     * INVALID_IDP_RESPONSE detection because it avoids opening a browser that can only fail.
     */
    const val APPLE_ANDROID_ENABLED = false
}
