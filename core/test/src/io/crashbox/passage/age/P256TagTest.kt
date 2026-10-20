package io.crashbox.passage.age

import com.exceptionfactory.jagged.FileKey
import com.exceptionfactory.jagged.RecipientStanza
import com.exceptionfactory.jagged.RecipientStanzaReader
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.UnsupportedRecipientStanzaException
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue

class P256TagTest {
  private fun softwareKey(): CountingKeyAgreement {
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(ECGenParameterSpec("secp256r1"))
    val pair = gen.generateKeyPair()
    return CountingKeyAgreement(JcaP256KeyAgreement(pair.private, pair.public as ECPublicKey))
  }

  /** Counts ECDH operations, which on a hardware key may prompt the user. */
  private class CountingKeyAgreement(private val inner: P256KeyAgreement) : P256KeyAgreement by inner {
    var count = 0
    override fun agree(peer: ECPublicKey): ByteArray = inner.agree(peer).also { count++ }
  }

  private class TestStanza(
    private val type: String,
    private val arguments: List<String>,
    private val body: ByteArray
  ) : RecipientStanza {
    override fun getType() = type
    override fun getArguments() = arguments
    override fun getBody() = body
  }

  private fun stanzaFor(key: P256KeyAgreement, fileKey: FileKey) =
    P256TagRecipientStanzaWriter(P256TagRecipient.of(key.publicKey)).getRecipientStanzas(fileKey).single()

  private fun encrypt(plaintext: ByteArray, writers: List<RecipientStanzaWriter>): ByteArray {
    val out = ByteArrayOutputStream()
    StandardEncryptingChannelFactory().newEncryptingChannel(Channels.newChannel(out), writers).use {
      it.write(java.nio.ByteBuffer.wrap(plaintext))
    }
    return out.toByteArray()
  }

  private fun decrypt(ciphertext: ByteArray, readers: List<RecipientStanzaReader>): ByteArray {
    val channel = StandardDecryptingChannelFactory()
      .newDecryptingChannel(Channels.newChannel(ByteArrayInputStream(ciphertext)), readers)
    return Channels.newInputStream(channel).use { it.readBytes() }
  }

  // ---- recipient encoding ----

  @Test
  fun specRecipientRoundTrips() {
    val encoded = "age1tag1qt8lw0ual6avlwmwatk888yqnmdamm7xfd0wak53ut6elz5c4swx2yqdj4e"
    assertEquals(encoded, P256TagRecipient.parse(encoded).toString())
  }

  @Test
  fun rejectsOtherRecipientTypes() {
    assertFailsWith<IllegalArgumentException> {
      P256TagRecipient.parse("age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p")
    }
  }

  // ---- stanzas ----

  @Test
  fun roundTrip() {
    val key = softwareKey()
    val fileKey = FileKey()
    val stanza = stanzaFor(key, fileKey)
    assertEquals("p256tag", stanza.type)
    assertEquals(fileKey, P256TagRecipientStanzaReader(key).getFileKey(listOf(stanza)))
    assertEquals(1, key.count)
  }

  @Test
  fun skipsStanzasForOtherRecipientsWithoutEcdh() {
    val ours = softwareKey()
    val theirs = softwareKey()
    val fileKey = FileKey()
    val reader = P256TagRecipientStanzaReader(ours)
    assertFailsWith<UnsupportedRecipientStanzaException> {
      reader.getFileKey(listOf(stanzaFor(theirs, fileKey)))
    }
    assertEquals(0, ours.count)
    assertEquals(fileKey, reader.getFileKey(listOf(stanzaFor(theirs, fileKey), stanzaFor(ours, fileKey))))
    assertEquals(1, ours.count)
  }

  @Test
  fun rejectsTamperedBody() {
    val key = softwareKey()
    val stanza = stanzaFor(key, FileKey())
    val body = stanza.body.also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertFailsWith<AEADBadTagException> {
      P256TagRecipientStanzaReader(key).getFileKey(listOf(TestStanza(stanza.type, stanza.arguments, body)))
    }
  }

  @Test
  fun rejectsMalformedStanzas() {
    val key = softwareKey()
    val stanza = stanzaFor(key, FileKey())
    val (tag, enc) = stanza.arguments
    val reader = P256TagRecipientStanzaReader(key)
    val malformed = listOf(
      TestStanza("p256tag", listOf(tag), stanza.body),
      TestStanza("p256tag", listOf(tag, enc, "extra"), stanza.body),
      TestStanza("p256tag", listOf("$tag==", enc), stanza.body), // padded, not canonical
      TestStanza("p256tag", listOf(tag, enc.dropLast(4)), stanza.body), // short enc
      TestStanza("p256tag", listOf(tag, enc), stanza.body + 0), // body length
    )
    for (s in malformed) {
      assertFailsWith<UnsupportedRecipientStanzaException>(s.arguments.toString()) { reader.getFileKey(listOf(s)) }
    }
    assertEquals(0, key.count)
  }

  // ---- whole files, interoperability with the age CLI ----

  @Test
  fun fileRoundTripWithX25519() {
    val key = softwareKey()
    val x25519 = generateX25519()
    val plaintext = "hunter2\n".toByteArray()
    val ciphertext = encrypt(
      plaintext,
      listOf(
        X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(x25519.recipient),
        P256TagRecipientStanzaWriter(P256TagRecipient.of(key.publicKey))
      )
    )
    assertEquals(plaintext.decodeToString(), decrypt(ciphertext, listOf(P256TagRecipientStanzaReader(key))).decodeToString())
    val viaX25519 = decrypt(ciphertext, listOf(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(x25519.identity)))
    assertEquals(plaintext.decodeToString(), viaX25519.decodeToString())
  }

  @Test
  fun decryptsFilesEncryptedByAge() {
    assumeTrue(hasAge(), "age CLI not available")
    val key = softwareKey()
    val x25519 = generateX25519()
    val plaintext = "correct horse battery staple\n".toByteArray()
    val ciphertext = run(
      listOf("age", "-r", x25519.recipient, "-r", P256TagRecipient.of(key.publicKey).toString()),
      plaintext
    )
    assertEquals(plaintext.decodeToString(), decrypt(ciphertext, listOf(P256TagRecipientStanzaReader(key))).decodeToString())
  }

  @Test
  fun ageDecryptsFilesWithOurStanzas() {
    // age can't decrypt p256tag without a plugin, but it must accept our
    // stanza when decrypting with another recipient in the same file
    assumeTrue(hasAge(), "age CLI not available")
    val key = softwareKey()
    val x25519 = generateX25519()
    val plaintext = "open sesame\n".toByteArray()
    val ciphertext = encrypt(
      plaintext,
      listOf(
        P256TagRecipientStanzaWriter(P256TagRecipient.of(key.publicKey)),
        X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(x25519.recipient)
      )
    )
    val identity = File.createTempFile("identity", ".txt").apply {
      deleteOnExit()
      writeText(x25519.identity + "\n")
    }
    val decrypted = run(listOf("age", "-d", "-i", identity.path), ciphertext)
    assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
  }

  // ---- helpers ----

  private class X25519Keys(val identity: String, val recipient: String)

  private fun generateX25519(): X25519Keys {
    // jagged has no key generation API that returns encoded keys, so use
    // age-keygen if available, otherwise a fixed test key
    if (hasAge()) {
      val identity = run(listOf("age-keygen"), ByteArray(0)).decodeToString()
        .lines().single { it.startsWith("AGE-SECRET-KEY-") }
      val recipient = run(listOf("age-keygen", "-y"), (identity + "\n").toByteArray()).decodeToString().trim()
      return X25519Keys(identity, recipient)
    }
    return X25519Keys(
      "AGE-SECRET-KEY-1GQ9778VQXMMJVE8SK7J6VT8UJ4HDQAJUVSFCWCM02D8GEWQ72PVQ2Y5J33",
      "age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwj"
    )
  }

  private fun hasAge(): Boolean = try {
    ProcessBuilder("age", "--version").start().waitFor() == 0
  } catch (e: java.io.IOException) {
    false
  }

  private fun run(command: List<String>, stdin: ByteArray): ByteArray {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    process.outputStream.use { it.write(stdin) }
    val out = process.inputStream.readBytes()
    check(process.waitFor() == 0) { "$command failed" }
    return out
  }
}
