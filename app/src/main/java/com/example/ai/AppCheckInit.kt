package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val TOKEN_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val EXPECTED_PROJECT_ID = "gen-lang-client-0675019109"
private const val EXPECTED_APP_ID = "1:710382825344:android:217d80d8206c797f415b65"
private const val DUMMY_ERROR_TOKEN = "eyJlcnJvciI6IlVOS05PV05fRVJST1IifQ=="

data class AppCheckDiagnosticState(
    val providerMode: String = "UNINITIALIZED",
    val receivedIntentDebugToken: Boolean = false,
    val lastError: String? = null
)

object AppCheckCoordinator {
    @Volatile
    var diagnosticState: AppCheckDiagnosticState = AppCheckDiagnosticState()
        internal set

    /**
     * Requests a valid Firebase App Check token before calling Firebase AI Logic.
     * Detects the Firebase SDK's DUMMY_TOKEN fallback and translates 403 / provider
     * failures into actionable diagnostics rather than letting an invalid header reach Gemini.
     */
    suspend fun awaitValidAppCheckToken(
        context: Context?,
        forceRefresh: Boolean = false
    ): Result<String> {
        if (context == null) {
            return Result.failure(IllegalStateException("Application context is required for App Check."))
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            return Result.failure(IllegalStateException("FirebaseApp is not initialized."))
        }

        val firstAttempt = fetchTokenOnce(forceRefresh)
        if (firstAttempt.isSuccess) {
            return firstAttempt
        }

        // If the non-forced read hit a stale/dummy cache entry, retry once with forceRefresh = true.
        if (!forceRefresh) {
            val secondAttempt = fetchTokenOnce(forceRefresh = true)
            if (secondAttempt.isSuccess) {
                return secondAttempt
            }
            return secondAttempt
        }
        return firstAttempt
    }

    private suspend fun fetchTokenOnce(forceRefresh: Boolean): Result<String> {
        return try {
            val result = withTimeout(12_000L) {
                suspendCancellableCoroutine<Result<String>> { cont ->
                    FirebaseAppCheck.getInstance()
                        .getAppCheckToken(forceRefresh)
                        .addOnSuccessListener { appCheckToken ->
                            val token = appCheckToken.token
                            if (token.isBlank() || token == DUMMY_ERROR_TOKEN || token.startsWith("eyJlcnJvciI6")) {
                                val err = IllegalStateException(buildActionableError("App Check returned an invalid placeholder token."))
                                diagnosticState = diagnosticState.copy(lastError = err.message)
                                if (cont.isActive) cont.resume(Result.failure(err))
                            } else {
                                diagnosticState = diagnosticState.copy(lastError = null)
                                if (cont.isActive) cont.resume(Result.success(token))
                            }
                        }
                        .addOnFailureListener { exc ->
                            val err = IllegalStateException(buildActionableError(exc.localizedMessage ?: exc.javaClass.simpleName), exc)
                            diagnosticState = diagnosticState.copy(lastError = err.message)
                            if (cont.isActive) cont.resume(Result.failure(err))
                        }
                }
            }
            result
        } catch (e: Exception) {
            val err = IllegalStateException(buildActionableError(e.localizedMessage ?: "App Check token request failed."), e)
            diagnosticState = diagnosticState.copy(lastError = err.message)
            Result.failure(err)
        }
    }

    private fun buildActionableError(rawMessage: String): String {
        val state = diagnosticState
        return if (BuildConfig.DEBUG) {
            val tokenHint = if (state.receivedIntentDebugToken) {
                "A debug token was provided via launch Intent; verify network connectivity to firebaseappcheck.googleapis.com and that the token matches project $EXPECTED_PROJECT_ID."
            } else {
                "No FIREBASE_APPCHECK_DEBUG_TOKEN launch extra was supplied. Launch via AI Studio OR register the DebugAppCheckProvider UUID printed in Logcat under Firebase Console -> App Check for app $EXPECTED_APP_ID ($EXPECTED_PROJECT_ID)."
            }
            "Firebase App Check token is invalid ($rawMessage). $tokenHint"
        } else {
            "Firebase App Check reCAPTCHA attestation failed ($rawMessage). Verify Play Store / WebView network access and reCAPTCHA v3 key in Firebase Console ($EXPECTED_PROJECT_ID)."
        }
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        } else {
            FirebaseApp.getInstance()
        } ?: return

        val persistenceKey = app.persistenceKey

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            val debugPrefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            val tokenPrefs = context.getSharedPreferences(
                "$TOKEN_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )

            val receivedExtra = !debugToken.isNullOrEmpty()
            if (receivedExtra) {
                val previousSecret = debugPrefs.getString(DEBUG_SECRET_KEY, null)
                if (previousSecret != debugToken) {
                    // Synchronous commit() ensures DebugAppCheckProvider reads the updated secret immediately
                    // and clears any stale cached App Check JWT issued under an older secret.
                    debugPrefs.edit().putString(DEBUG_SECRET_KEY, debugToken).commit()
                    tokenPrefs.edit().clear().commit()
                    Log.d(TAG, "Updated App Check debug secret from Intent extra and cleared stale token cache.")
                } else {
                    Log.d(TAG, "App Check debug secret already matches Intent extra.")
                }
            } else {
                Log.w(
                    TAG,
                    "No $DEBUG_TOKEN_EXTRA Intent extra found; DebugAppCheckProvider will use persisted debug secret in $DEBUG_STORE_PREFIX$persistenceKey."
                )
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckCoordinator.diagnosticState = AppCheckDiagnosticState(
                providerMode = "DEBUG_PROVIDER",
                receivedIntentDebugToken = receivedExtra,
                lastError = null
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed for ${context.packageName}.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    AppCheckCoordinator.diagnosticState = AppCheckDiagnosticState(
                        providerMode = "RECAPTCHA_V3",
                        receivedIntentDebugToken = false,
                        lastError = null
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
        AppCheckCoordinator.diagnosticState = AppCheckCoordinator.diagnosticState.copy(
            lastError = e.localizedMessage
        )
    }
}
