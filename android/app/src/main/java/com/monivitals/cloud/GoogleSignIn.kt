//
//  GoogleSignIn.kt
//  MoniVitals (Android)
//
//  Sign in with Google via Credential Manager (androidx.credentials + googleid) — no
//  Firebase SDK. We only need the Google id_token; the Firebase session is minted by
//  AuthService.completeIdpSignIn() against Identity Toolkit signInWithIdp (docs/CLOUD_OAUTH.md).
//
//  Credential Manager needs an Activity context (it shows a system bottom sheet), so the
//  caller threads one down from the Compose tree (LocalContext cast to Activity).
//

package com.monivitals.cloud

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException

/**
 * Drives the Google credential picker and returns the raw Google `id_token`.
 *
 * Cancellation (user dismissed the sheet, or no eligible account) is surfaced as a
 * [CloudException] carrying the [AUTH_CANCELLED] sentinel so the ViewModel can return to
 * signed-out silently — no red error line. Everything else maps to the warm offline copy.
 */
object GoogleSignIn {

    suspend fun idToken(activity: Activity): String {
        // First run: don't filter to previously-authorized accounts, so a brand-new user
        // can pick any Google account rather than seeing an empty sheet.
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(FirebaseConfig.googleWebClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            throw CloudException(AUTH_CANCELLED)
        } catch (e: NoCredentialException) {
            // No Google account available to offer — treat as a silent back-out.
            throw CloudException(AUTH_CANCELLED)
        } catch (e: GetCredentialException) {
            throw CloudException(CLOUD_OFFLINE_MESSAGE)
        }

        val credential = response.credential
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw CloudException(CLOUD_OFFLINE_MESSAGE)
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: Exception) {
            throw CloudException(CLOUD_OFFLINE_MESSAGE)
        }
    }
}
