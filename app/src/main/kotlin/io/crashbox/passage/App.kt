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
import org.eclipse.jgit.lib.PersonIdent

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

  var authorName: String
    get() = prefs.getString("author_name", null) ?: "Passage"
    set(value) = prefs.edit().putString("author_name", value).apply()

  var authorEmail: String
    get() = prefs.getString("author_email", null) ?: "passage@android"
    set(value) = prefs.edit().putString("author_email", value).apply()

  private fun author() = PersonIdent(authorName, authorEmail)

  /**
   * Encrypts and commits an entry (but does not push).
   *
   * @param oldName the entry's current name, null for a new entry. If it
   *   differs from [name], the entry is renamed (and re-encrypted for its
   *   new location, like `passage mv`).
   * @return warnings for the user about the entry's recipients
   */
  fun save(oldName: String?, name: String, content: String): List<String> {
    require(PasswordStore.isValidName(name)) { "Invalid name. Use letters, digits and / to separate folders." }
    if (name != oldName && store.exists(name)) throw IllegalArgumentException("An entry named $name already exists.")
    val plaintext = content.toByteArray()
    val (message, paths) = when (oldName) {
      null -> {
        store.encrypt(name, plaintext)
        "Add given password for $name to store." to listOf(store.relativePath(name))
      }
      name -> {
        store.encrypt(name, plaintext)
        "Edit password for $name using Passage for Android." to listOf(store.relativePath(name))
      }
      else -> {
        store.move(oldName, name, plaintext)
        "Rename $oldName to $name." to listOf(store.relativePath(oldName), store.relativePath(name))
      }
    }
    repo.commit(message, author(), paths)
    return recipientWarnings(name)
  }

  /** Deletes and commits an entry (but does not push). */
  fun delete(name: String) {
    val path = store.relativePath(name)
    store.delete(name)
    repo.commit("Remove $name from store.", author(), listOf(path))
  }

  private fun recipientWarnings(name: String): List<String> {
    val recipients = store.recipients(name)
    val phone = Keys.ageRecipient.toString()
    return when {
      phone !in recipients -> listOf("This phone is not a recipient of $name, so it cannot read it back.")
      recipients.size == 1 -> listOf("This phone is the only recipient of $name: it will be lost if the phone is.")
      else -> emptyList()
    }
  }

  fun decrypt(name: String, authorize: (KeyAgreement) -> Unit): String =
    try {
      store.decrypt(name, listOf(Keys.ageIdentity(authorize))).decodeToString()
    } catch (e: NoMatchingStanzaException) {
      throw NotARecipientException()
    }
}
