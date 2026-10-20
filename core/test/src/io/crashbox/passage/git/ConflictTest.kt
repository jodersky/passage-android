package io.crashbox.passage.git

import io.crashbox.passage.store.PasswordStore
import io.crashbox.passage.store.StoreSync
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git

/**
 * Concurrent changes to the same entries. The entries' content does not
 * need to be real ciphertext here: git treats them as opaque files.
 */
class ConflictTest {
  private val f = GitFixture()
  private val clone = f.clone()
  private val sync = StoreSync(clone, PasswordStore(clone.dir)) { LocalDate.of(2026, 10, 21) }

  init {
    f.commitToRemote(".age-recipients" to "age1one\n", "a.age" to "a0", "b.age" to "b0", "web/c.age" to "c0", message = "store")
    sync.integrate(sync.prepare(), emptyMap(), f.author)
  }

  @AfterTest
  fun cleanup() = f.close()

  private fun file(path: String): String? = File(clone.dir, path).takeIf { it.isFile }?.readText()

  @Test
  fun detectsConflictsWithoutChangingAnything() {
    f.commitLocally(clone, "a.age" to "a-mine", "b.age" to null, "web/c.age" to "c-same", "new.age" to "n-mine")
    f.commitToRemote("a.age" to "a-theirs", "b.age" to "b-theirs", "web/c.age" to "c-same", "new.age" to "n-theirs")
    val head = Git.open(clone.dir).use { it.repository.resolve("HEAD") }

    val d = sync.prepare()

    assertEquals(
      listOf(
        Conflict("a.age", Change.MODIFIED, Change.MODIFIED),
        Conflict("b.age", Change.DELETED, Change.MODIFIED),
        Conflict("new.age", Change.ADDED, Change.ADDED),
      ),
      d.conflicts, // web/c.age changed identically on both sides: no conflict
    )
    assertFalse(d.conflicts[1].canKeepBoth)
    assertEquals(head, Git.open(clone.dir).use { it.repository.resolve("HEAD") })
    assertEquals("a-mine", file("a.age"))
  }

  @Test
  fun resolvesEachWay() {
    f.commitLocally(clone, "a.age" to "a-mine", "b.age" to "b-mine", "web/c.age" to "c-mine", "other.age" to "o-mine", message = "local edits")
    f.commitToRemote("a.age" to "a-theirs", "b.age" to "b-theirs", "web/c.age" to "c-theirs", "remote.age" to "r-theirs")

    val d = sync.prepare()
    val reencrypt = sync.integrate(
      d,
      mapOf("a.age" to Resolution.MINE, "b.age" to Resolution.THEIRS, "web/c.age" to Resolution.BOTH),
      f.author,
    )
    clone.push()

    assertEquals("a-mine", f.remoteFile("a.age"))
    assertEquals("b-theirs", f.remoteFile("b.age"))
    assertEquals("c-theirs", f.remoteFile("web/c.age"))
    assertEquals("c-mine", f.remoteFile("web/c-conflict-2026-10-21.age"))
    assertEquals("o-mine", f.remoteFile("other.age")) // non-conflicting local change
    assertEquals("r-theirs", f.remoteFile("remote.age")) // non-conflicting remote change
    assertEquals(emptyList(), reencrypt) // recipients did not change

    Git.open(clone.dir).use { git ->
      val merge = git.log().setMaxCount(1).call().first()
      assertEquals(2, merge.parentCount)
      assertEquals("Merge remote changes, resolving conflicts in a.age, b.age, web/c.age.", merge.shortMessage)
      assertTrue(git.log().call().any { it.shortMessage == "local edits" }) // local commit kept
      assertTrue(git.status().call().isClean)
    }
    assertFalse(clone.hasUnpushedCommits())
  }

  @Test
  fun resolvesDeletions() {
    f.commitLocally(clone, "a.age" to null, "b.age" to "b-mine")
    f.commitToRemote("a.age" to "a-theirs", "b.age" to null)

    sync.integrate(sync.prepare(), mapOf("a.age" to Resolution.MINE, "b.age" to Resolution.MINE), f.author)
    clone.push()

    assertEquals(null, f.remoteFile("a.age"))
    assertEquals("b-mine", f.remoteFile("b.age"))
  }

  @Test
  fun keepBothAvoidsExistingNames() {
    f.commitLocally(clone, "a.age" to "a-mine", "a-conflict-2026-10-21.age" to "earlier copy")
    f.commitToRemote("a.age" to "a-theirs")
    sync.integrate(sync.prepare(), mapOf("a.age" to Resolution.BOTH), f.author)
    assertEquals("earlier copy", file("a-conflict-2026-10-21.age"))
    assertEquals("a-mine", file("a-conflict-2026-10-21-2.age"))
  }

  @Test
  fun refusesIncompleteResolutions() {
    f.commitLocally(clone, "a.age" to "a-mine", "b.age" to null)
    f.commitToRemote("a.age" to "a-theirs", "b.age" to "b-theirs")
    val d = sync.prepare()
    assertFailsWith<IllegalArgumentException> { sync.integrate(d, mapOf("a.age" to Resolution.MINE), f.author) }
    assertFailsWith<IllegalArgumentException> {
      sync.integrate(d, mapOf("a.age" to Resolution.MINE, "b.age" to Resolution.BOTH), f.author)
    }
  }

  @Test
  fun findsLocalEntriesWithOutdatedRecipients() {
    // edited offline, while a recipient was added on the server
    f.commitLocally(clone, "a.age" to "a-mine", "web/c.age" to "c-mine", "b.age" to "b-mine")
    f.commitToRemote(".age-recipients" to "age1one\nage1two\n", "b.age" to "b-theirs")

    val d = sync.prepare()
    val reencrypt = sync.integrate(d, mapOf("b.age" to Resolution.MINE), f.author)

    assertEquals(setOf("a", "web/c", "b"), reencrypt.toSet())
  }

  @Test
  fun closerRecipientsFileCounts() {
    f.commitLocally(clone, "a.age" to "a-mine", "web/c.age" to "c-mine")
    f.commitToRemote("web/.age-recipients" to "age1web\n")
    assertEquals(listOf("web/c"), sync.integrate(sync.prepare(), emptyMap(), f.author))
  }

  @Test
  fun keptTheirsNeedsNoReencryption() {
    f.commitLocally(clone, "a.age" to "a-mine")
    f.commitToRemote(".age-recipients" to "age1one\nage1two\n", "a.age" to "a-theirs")
    assertEquals(emptyList(), sync.integrate(sync.prepare(), mapOf("a.age" to Resolution.THEIRS), f.author))
  }

  @Test
  fun keptBothReencryptsTheCopy() {
    f.commitLocally(clone, "a.age" to "a-mine")
    f.commitToRemote(".age-recipients" to "age1one\nage1two\n", "a.age" to "a-theirs")
    assertEquals(listOf("a-conflict-2026-10-21"), sync.integrate(sync.prepare(), mapOf("a.age" to Resolution.BOTH), f.author))
  }

  @Test
  fun nothingToDo() {
    val d = sync.prepare()
    assertEquals(emptyList(), d.conflicts)
    assertEquals(emptyList(), sync.integrate(d, emptyMap(), f.author))
  }
}
