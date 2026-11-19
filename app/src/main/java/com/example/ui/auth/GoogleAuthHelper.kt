package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await

sealed class GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome()
    data object Cancelled : GoogleSignInOutcome()
    data class Error(val message: String) : GoogleSignInOutcome()
}

object GoogleAuthHelper {

    private const val TAG = "GoogleAuthHelper"

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun serverClientId(context: Context): String {
        val direct = runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else ""
    }

    suspend fun attemptAutoSignIn(context: Context): FirebaseUser? {
        Firebase.auth.currentUser?.let { return it }
        val webClientId = serverClientId(context)
        if (webClientId.isBlank()) return null

        val uiContext = context.findActivity() ?: context
        return try {
            val credentialManager = CredentialManager.create(uiContext)
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(uiContext, request)
            signInWithCredentialResult(result)
        } catch (_: NoCredentialException) {
            null
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Silent Google Sign-In cancelled: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Silent Google Sign-In skipped: ${e.message}")
            null
        }
    }

    suspend fun signInInteractive(context: Context): GoogleSignInOutcome {
        val webClientId = serverClientId(context)
        if (webClientId.isBlank()) {
            return GoogleSignInOutcome.Error(
                "Google Sign-In client ID (default_web_client_id) is missing. Please verify Firebase google-services.json."
            )
        }
        val uiContext = context.findActivity() ?: context
        return try {
            val credentialManager = CredentialManager.create(uiContext)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(uiContext, request)
            val user = signInWithCredentialResult(result)
            if (user != null) {
                GoogleSignInOutcome.Success(user)
            } else {
                GoogleSignInOutcome.Error("Failed to retrieve Firebase user from Google credential.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Interactive Google Sign-In cancelled by user: ${e.message}")
            GoogleSignInOutcome.Cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Interactive Google Sign-In failed", e)
            GoogleSignInOutcome.Error(e.localizedMessage ?: "Google Sign-In failed. Please try again.")
        }
    }

    private suspend fun signInWithCredentialResult(result: GetCredentialResponse): FirebaseUser? {
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = Firebase.auth.signInWithCredential(firebaseCred).await()
            return authResult.user
        }
        return null
    }

    suspend fun signOut(context: Context) {
        Firebase.auth.signOut()
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
