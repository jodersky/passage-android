package io.crashbox.passage

import android.app.Application
import android.content.Context
import io.crashbox.passage.age.NoMatchingStanzaException
import io.crashbox.passage.git.Divergence
import io.crashbox.passage.git.GitRepo
import io.crashbox.passage.git.KnownHosts
import io.crashbox.passage.git.Resolution
import io.crashbox.passage.git.SshjSessionFactory
import io.crashbox.passage.store.PasswordStore
import io.crashbox.passage.store.StoreSync
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
  val sync = StoreSync(repo, store)

  /**
   * Entries encrypted to outdated recipients after a sync. They must be
   * re-encrypted (which needs the user) before pushing. Persisted, so that an
   * interrupted sync cannot push them as they are.
   */
  var pendingReencryption: Set<String>
    get() = prefs.getStringSet("pending_reencryption", null) ?: emptySet()
    set(value) {
      prefs.edit().putStringSet("pending_reencryption", value).commit()
    }

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

  /**
   * This phone's name, as a host name: the device name from the system
   * settings (falling back to the model), e.g. "Jakob's Pixel 9a" ->
   * "jakobs-pixel-9a".
   */
  val deviceName: String = run {
    val name = android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
      ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    name.lowercase().replace(Regex("['’]"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "android" }
  }

  /** Identifies this phone: the commit author, and the SSH public key's comment. */
  val identity: String get() = "passage-android@$deviceName"

  /** Filled as username by autofill for entries without a username line. */
  var fallbackUsername: String
    get() = prefs.getString("fallback_username", null) ?: ""
    set(value) = prefs.edit().putString("fallback_username", value).apply()

  private fun author() = PersonIdent("Passage Android", identity)

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

  /** Re-encrypts an entry for its current recipients, and commits it. */
  fun reencrypt(name: String, authorize: (KeyAgreement) -> Unit) {
    val content = decrypt(name, authorize)
    store.encrypt(name, content.toByteArray())
    repo.commit("Reencrypt $name for updated recipients.", author(), listOf(store.relativePath(name)))
  }

  /** Integrates fetched remote changes (see [StoreSync.integrate]) and records entries to re-encrypt. */
  fun integrate(divergence: Divergence, resolutions: Map<String, Resolution>) {
    val stale = sync.integrate(divergence, resolutions, author())
    pendingReencryption = pendingReencryption + stale
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
