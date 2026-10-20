package io.crashbox.passage.store

import com.exceptionfactory.jagged.RecipientStanzaReader
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory
import com.exceptionfactory.jagged.ssh.SshEd25519RecipientStanzaWriterFactory
import com.exceptionfactory.jagged.ssh.SshRsaRecipientStanzaWriterFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory
import io.crashbox.passage.age.P256TagRecipient
import io.crashbox.passage.age.P256TagRecipientStanzaWriter
import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.Channels

/**
 * A passage password store: entry `a/b` is the age file `a/b.age` under
 * [root], encrypted to the recipients in the nearest `.age-recipients` file
 * in its directory or a parent directory (up to [root]).
 */
class PasswordStore(val root: File) {

  /** All entry names, sorted. Hidden files and directories (such as `.git`) are skipped. */
  fun entries(): List<String> =
    root.walkTopDown()
      .onEnter { it == root || !it.name.startsWith(".") }
      .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(SUFFIX) }
      .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(SUFFIX) }
      .sortedWith(String.CASE_INSENSITIVE_ORDER)
      .toList()

  fun file(name: String): File {
    require(isValidName(name)) { "invalid entry name: $name" }
    return File(root, name + SUFFIX)
  }

  /** The `.age-recipients` file that applies to [name], as passage looks it up. */
  fun recipientsFile(name: String): File? {
    var dir: File? = file(name).parentFile
    while (dir != null) {
      val candidate = File(dir, RECIPIENTS_FILE)
      if (candidate.isFile) return candidate
      if (dir == root) break
      dir = dir.parentFile
    }
    return null
  }

  /**
   * The recipients [name] must be encrypted to.
   *
   * @throws FileNotFoundException if there is no `.age-recipients` file. passage
   *   then encrypts to its identities file, which only exists on the desktop.
   */
  fun recipients(name: String): List<String> {
    val file = recipientsFile(name) ?: throw FileNotFoundException("no $RECIPIENTS_FILE for $name")
    return parseRecipients(file.readText())
  }

  fun decrypt(name: String, identities: List<RecipientStanzaReader>): ByteArray =
    file(name).inputStream().use { input ->
      val channel = StandardDecryptingChannelFactory().newDecryptingChannel(Channels.newChannel(input), identities)
      Channels.newInputStream(channel).readBytes()
    }

  /** Encrypts [plaintext] to [name]'s recipients and writes it, creating parent directories. */
  fun encrypt(name: String, plaintext: ByteArray) {
    val writers = recipients(name).map(::recipientWriter)
    val file = file(name)
    file.parentFile.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    tmp.outputStream().use { out ->
      StandardEncryptingChannelFactory().newEncryptingChannel(Channels.newChannel(out), writers).use {
        it.write(java.nio.ByteBuffer.wrap(plaintext))
      }
    }
    check(tmp.renameTo(file)) { "could not write $file" }
  }

  fun exists(name: String): Boolean = file(name).isFile

  /** Deletes an entry, and the directories it leaves empty. */
  fun delete(name: String) {
    val file = file(name)
    if (!file.delete()) throw FileNotFoundException("no entry $name")
    var dir = file.parentFile
    while (dir != root && dir.list()?.isEmpty() == true) {
      dir.delete()
      dir = dir.parentFile
    }
  }

  /**
   * Renames an entry, re-encrypting [plaintext] to the recipients of its new
   * location (like `passage mv`). The old entry is only deleted once the new
   * one is written.
   */
  fun move(from: String, to: String, plaintext: ByteArray) {
    require(from != to) { "same name" }
    if (exists(to)) throw FileAlreadyExistsException(file(to), reason = "an entry named $to already exists")
    if (!exists(from)) throw FileNotFoundException("no entry $from")
    encrypt(to, plaintext)
    delete(from)
  }

  /** Path of an entry's file, relative to [root]. */
  fun relativePath(name: String): String = file(name).relativeTo(root).invariantSeparatorsPath

  companion object {
    const val SUFFIX = ".age"
    const val RECIPIENTS_FILE = ".age-recipients"

    fun isValidName(name: String): Boolean =
      name.isNotEmpty() && name.split('/').none { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }

    /** Recipient lines, as accepted by `age -R`: blank lines and `#` comments are ignored. */
    fun parseRecipients(text: String): List<String> =
      text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * A writer for one recipient line.
     *
     * @throws UnsupportedRecipientException for recipient types we cannot
     *   encrypt to (e.g. plugins): files must never silently skip a recipient.
     */
    fun recipientWriter(recipient: String): RecipientStanzaWriter = when {
      recipient.startsWith("age1tag1") -> P256TagRecipientStanzaWriter(P256TagRecipient.parse(recipient))
      // Bech32 data never contains '1', so another '1' means a longer
      // prefix: a plugin (age1yubikey1...) or post-quantum (age1pq1...) recipient
      recipient.startsWith("age1") && !recipient.substring(4).contains('1') ->
        X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient)
      recipient.startsWith("ssh-ed25519 ") ->
        SshEd25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient.toByteArray(Charsets.US_ASCII))
      recipient.startsWith("ssh-rsa ") ->
        SshRsaRecipientStanzaWriterFactory.newRecipientStanzaWriter(recipient.toByteArray(Charsets.US_ASCII))
      else -> throw UnsupportedRecipientException(recipient)
    }
  }
}

class UnsupportedRecipientException(recipient: String) :
  Exception("unsupported recipient type: ${recipient.take(24)}…")
