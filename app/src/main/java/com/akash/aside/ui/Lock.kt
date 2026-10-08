package com.akash.aside.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** When the app was last left, so coming straight back (or rotating) does not ask again. */
object LockState {
    const val GRACE_MS = 2 * 60 * 1000L
    var leftAt = 0L

    fun needsUnlock(now: Long = System.currentTimeMillis()) = leftAt == 0L || now - leftAt > GRACE_MS
}

object Lock {
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** False when the phone itself has no fingerprint or PIN set: then there is nothing to ask. */
    fun available(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String = "Unlock Aside", onResult: (Boolean) -> Unit) {
        if (!available(activity)) { onResult(true); return }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build()
        )
    }
}
