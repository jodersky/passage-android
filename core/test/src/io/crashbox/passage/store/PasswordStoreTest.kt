package io.crashbox.passage.store

import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory
import io.crashbox.passage.age.JcaP256KeyAgreement
import io.crashbox.passage.age.P256TagRecipient
import io.crashbox.passage.age.P256TagRecipientStanzaReader
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class PasswordStoreTest {
  private val root: File = Files.createTempDirectory("passage-store-test").toFile()
  private val store = PasswordStore(root)

  // throwaway test keys
  private val x25519Identity = "AGE-SECRET-KEY-1SDGAW3PX6VKMGXXCU2CH55AKG70F7JN9FLGCGW36H4VYQ7JCE4CSJK0HES"
  private val x25519Recipient = "age1jv4kjj75uvgnjdj7pdpp4kfj9mnr47ak032af8tzqzx98lfae5qstrltlq"
  private val phone = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    .generateKeyPair().let { JcaP256KeyAgreement(it.private, it.public as ECPublicKey) }
  private val phoneRecipient = P256TagRecipient.of(phone.publicKey).toString()

  @AfterTest
  fun cleanup() {
    root.deleteRecursively()
  }

  private fun write(path: String, content: String = "") =
    File(root, path).apply { parentFile.mkdirs(); writeText(content) }

  @Test
  fun listsEntries() {
    listOf("email.age", "web/GitHub.age", "web/apple.age", "a/b/c.age", "notes.txt", ".hidden.age", ".git/x.age", ".extensions/y.age")
      .forEach { write(it) }
    assertEquals(listOf("a/b/c", "email", "web/apple", "web/GitHub"), store.entries())
  }

  @Test
  fun findsNearestRecipientsFile() {
    write(".age-recipients", "# root\n$x25519Recipient\n")
    write("work/.age-recipients", "\n$x25519Recipient\n  $phoneRecipient  \n")
    assertEquals(File(root, ".age-recipients"), store.recipientsFile("email"))
    assertEquals(File(root, "work/.age-recipients"), store.recipientsFile("work/deep/er/vpn"))
    assertEquals(listOf(x25519Recipient), store.recipients("email"))
    assertEquals(listOf(x25519Recipient, phoneRecipient), store.recipients("work/vpn"))
  }

  @Test
  fun missingRecipientsFile() {
    assertFailsWith<FileNotFoundException> { store.recipients("email") }
  }

  @Test
  fun rejectsInvalidNames() {
    for (name in listOf("", "/abs", "a//b", "../escape", "a/../b", "a/./b", ".hidden", "a/.git/b")) {
      assertFalse(PasswordStore.isValidName(name), name)
      assertFailsWith<IllegalArgumentException>(name) { store.file(name) }
    }
    assertTrue(PasswordStore.isValidName("web/github.com"))
  }

  @Test
  fun encryptsToAllRecipients() {
    write(".age-recipients", "$x25519Recipient\n$phoneRecipient\n")
    store.encrypt("web/site", "s3cret\nuser: me\n".toByteArray())
    val viaPhone = store.decrypt("web/site", listOf(P256TagRecipientStanzaReader(phone)))
    val viaX25519 = store.decrypt("web/site", listOf(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(x25519Identity)))
    assertEquals("s3cret\nuser: me\n", viaPhone.decodeToString())
    assertEquals("s3cret\nuser: me\n", viaX25519.decodeToString())
    assertEquals(listOf("web/site"), store.entries())
  }

  @Test
  fun refusesUnsupportedRecipients() {
    for (r in listOf(
      "age1yubikey1qwt50d05nh5vutpdzmlg5wn80xq5negm4uj9ghv0snvdd3yysf5yw3rhl3t",
      "age1pq1abcdef",
      "ecdsa-sha2-nistp256 AAAA",
    )) {
      write(".age-recipients", "$x25519Recipient\n$r\n")
      assertFailsWith<UnsupportedRecipientException>(r) { store.encrypt("x", "y".toByteArray()) }
    }
    assertFalse(File(root, "x.age").exists())
  }

  @Test
  fun decryptsFilesWrittenByAge() {
    // what passage does: age -e -R <recipients file> -o <entry>.age
    assumeTrue(runCatching { ProcessBuilder("age", "--version").start().waitFor() == 0 }.getOrDefault(false))
    val recipients = write(".age-recipients", "$x25519Recipient\n$phoneRecipient\n")
    val target = File(root, "web/site.age").apply { parentFile.mkdirs() }
    val process = ProcessBuilder("age", "-e", "-R", recipients.path, "-o", target.path).start()
    process.outputStream.use { it.write("from passage\n".toByteArray()) }
    assertEquals(0, process.waitFor())
    assertEquals("from passage\n", store.decrypt("web/site", listOf(P256TagRecipientStanzaReader(phone))).decodeToString())
  }
}
