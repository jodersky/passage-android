package io.crashbox.passage

import android.app.Application
import android.content.Context
import io.crashbox.passage.age.NoMatchingStanzaException
import io.crashbox.passage.git.GitRepo
import io.crashbox.passage.git.KnownHosts
import io.crashbox.passage.git.SshjSessionFactory
import io.crashbox.passage.store.PasswordStore
import java.io.File
import javax.crypto.KeyAgreement
import net.schmizz.sshj.common.SecurityUtils

class App : Application() {
  val passage: Passage by lazy { Passage(this) }

  override fun onCreate() {
    super.onCreate()
    // sshj would otherwise route all JCA calls to BouncyCastle, which cannot
    // sign with Keystore keys
    SecurityUtils.setRegisterBouncyCastle(false)
    // jgit looks for configuration files in the home directory
    System.setProperty("user.home", filesDir.path)
  }
}

class NotARecipientException :
  Exception("This entry is not encrypted to this phone. Add the phone's recipient to .age-recipients and re-encrypt the store with passage.")

/** The app's state on disk: settings, the clone of the store and trusted host keys. */
class Passage(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var remoteUrl: String?
    get() = prefs.getString("remote_url", null)
    private set(value) = prefs.edit().putString("remote_url", value).apply()

  val knownHosts = KnownHosts(File(context.filesDir, "known_hosts"))
  val repo = GitRepo(File(context.filesDir, "store"), SshjSessionFactory(Keys::sshKeyPair, knownHosts))
  val store = PasswordStore(repo.dir)

  /** Replaces the local clone, if any, by a fresh clone of [url]. */
  fun clone(url: String) {
    repo.dir.deleteRecursively()
    try {
      repo.clone(url)
    } catch (e: Exception) {
      repo.dir.deleteRecursively()
      throw e
    }
    remoteUrl = url
  }

  fun decrypt(name: String, authorize: (KeyAgreement) -> Unit): String =
    try {
      store.decrypt(name, listOf(Keys.ageIdentity(authorize))).decodeToString()
    } catch (e: NoMatchingStanzaException) {
      throw NotARecipientException()
    }
}
