package io.crashbox.passage

import android.content.Context
import android.util.Log
import com.exceptionfactory.jagged.RecipientStanzaReader
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.framework.stream.StandardDecryptingChannelFactory
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaReaderFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory
import io.crashbox.passage.age.P256TagRecipient
import io.crashbox.passage.age.P256TagRecipientStanzaReader
import io.crashbox.passage.age.P256TagRecipientStanzaWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * Feasibility checks for the hardware-backed age identity (see plan.md, first
 * task). Temporary: to be replaced by the real app.
 */
object Spike {
  private const val TAG = "passage-spike"

  // Throwaway key for testing only, never used for real data.
  private const val TEST_X25519_IDENTITY = "AGE-SECRET-KEY-1SDGAW3PX6VKMGXXCU2CH55AKG70F7JN9FLGCGW36H4VYQ7JCE4CSJK0HES"
  private const val TEST_X25519_RECIPIENT = "age1jv4kjj75uvgnjdj7pdpp4kfj9mnr47ak032af8tzqzx98lfae5qstrltlq"

  class Check(val name: String, val ok: Boolean, val detail: String)

  /** Runs all checks. Blocking, call off the main thread. */
  fun run(context: Context): List<Check> {
    val results = mutableListOf<Check>()
    fun check(name: String, body: () -> String) {
      val start = System.nanoTime()
      val result = try {
        Check(name, true, body())
      } catch (e: Throwable) {
        Log.e(TAG, name, e)
        Check(name, false, "${e.javaClass.name}: ${e.message}")
      }
      val ms = (System.nanoTime() - start) / 1_000_000
      Log.i(TAG, "${if (result.ok) "OK  " else "FAIL"} $name (${ms}ms): ${result.detail}")
      results += Check(result.name, result.ok, "${result.detail} [${ms}ms]")
    }

    val dir = context.getExternalFilesDir(null)!!
    val key by lazy { AgeKeystore.getOrCreate() }
    val recipient by lazy { P256TagRecipient.of(key.publicKey) }

    check("Keystore key") {
      key // generate on first run
      "security level: ${AgeKeystore.securityLevel()}"
    }

    check("Recipient") {
      File(dir, "recipient.txt").writeText("$recipient\n")
      recipient.toString()
    }

    check("On-device round trip (p256tag)") {
      val ciphertext = encrypt("hello from the phone", listOf(P256TagRecipientStanzaWriter(recipient)))
      decrypt(ciphertext, listOf(P256TagRecipientStanzaReader(key)))
    }

    check("On-device round trip (X25519)") {
      val ciphertext = encrypt(
        "hello from the phone",
        listOf(X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(TEST_X25519_RECIPIENT))
      )
      decrypt(ciphertext, listOf(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(TEST_X25519_IDENTITY)))
    }

    val desktopFile = File(dir, "test.age")
    if (desktopFile.exists()) {
      check("Desktop file (p256tag)") {
        decrypt(desktopFile.readBytes(), listOf(P256TagRecipientStanzaReader(key)))
      }
      check("Desktop file (X25519)") {
        decrypt(
          desktopFile.readBytes(),
          listOf(X25519RecipientStanzaReaderFactory.newRecipientStanzaReader(TEST_X25519_IDENTITY))
        )
      }
    } else {
      results += Check("Desktop file", false, "not found: ${desktopFile.path}")
    }

    return results
  }

  private fun encrypt(plaintext: String, writers: List<RecipientStanzaWriter>): ByteArray {
    val out = ByteArrayOutputStream()
    StandardEncryptingChannelFactory().newEncryptingChannel(Channels.newChannel(out), writers).use {
      it.write(ByteBuffer.wrap(plaintext.toByteArray()))
    }
    return out.toByteArray()
  }

  private fun decrypt(ciphertext: ByteArray, readers: List<RecipientStanzaReader>): String {
    val channel = StandardDecryptingChannelFactory()
      .newDecryptingChannel(Channels.newChannel(ByteArrayInputStream(ciphertext)), readers)
    return Channels.newInputStream(channel).use { it.readBytes() }.decodeToString()
  }
}
