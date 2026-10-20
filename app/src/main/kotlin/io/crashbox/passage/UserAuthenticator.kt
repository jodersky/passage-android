package io.crashbox.passage

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.CountDownLatch
import javax.crypto.KeyAgreement

class AuthenticationException(message: String) : Exception(message)

/**
 * Asks the user to authenticate (fingerprint or device credential) a single
 * Keystore key agreement operation.
 */
class UserAuthenticator(private val activity: Activity, private val title: String, private val subtitle: String) {

  /**
   * Blocks the calling thread until the user authenticated [keyAgreement].
   * Must not be called on the main thread, which shows the prompt.
   *
   * @throws AuthenticationException if the user cancelled or authentication failed
   */
  fun authorize(keyAgreement: KeyAgreement) {
    check(Looper.myLooper() != Looper.getMainLooper()) { "authorize() would block the main thread" }
    val done = CountDownLatch(1)
    var error: String? = null
    val cancel = CancellationSignal()
    activity.mainExecutor.execute {
      try {
        prompt(keyAgreement, cancel, onDone = { error = it; done.countDown() })
      } catch (e: Exception) {
        error = describe(e)
        done.countDown()
      }
    }
    try {
      done.await()
    } catch (e: InterruptedException) {
      cancel.cancel()
      throw e
    }
    error?.let { throw AuthenticationException(it) }
  }

  /** Shows the prompt; [onDone] is called with null on success, or an error message. */
  private fun prompt(keyAgreement: KeyAgreement, cancel: CancellationSignal, onDone: (String?) -> Unit) {
    BiometricPrompt.Builder(activity)
      .setTitle(title)
      .setSubtitle(subtitle)
      .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
      .build()
      .authenticate(
        BiometricPrompt.CryptoObject(keyAgreement),
        cancel,
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(null)

          // a single failed attempt (e.g. unknown finger) keeps the prompt
          // open, so only errors end it
          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(errString.toString())
        },
      )
  }
}
