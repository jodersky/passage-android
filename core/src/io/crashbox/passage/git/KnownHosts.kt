package io.crashbox.passage.git

import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.URIish

/** A server's host key, as presented during an SSH handshake. */
class HostKey(
  /** host in known_hosts form: `host`, or `[host]:port` for non-default ports */
  val host: String,
  val key: PublicKey,
) {
  val type: String = KeyType.fromKey(key).toString()

  private val blob: ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

  /** OpenSSH-style fingerprint, as shown by `ssh-keygen -lf` */
  val fingerprint: String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

  internal val knownHostsLine: String get() = "$host $type ${Base64.getEncoder().encodeToString(blob)}"
}

/** The server presented a host key that is not in known_hosts yet. Ask the user, then [KnownHosts.trust] it. */
class UnknownHostKeyException(uri: URIish, val hostKey: HostKey) :
  TransportException(uri, "unknown host key ${hostKey.type} ${hostKey.fingerprint}")

/** The server presented a host key different from the one in known_hosts: possible man-in-the-middle attack. */
class HostKeyChangedException(uri: URIish, val hostKey: HostKey) :
  TransportException(uri, "host key changed, now ${hostKey.type} ${hostKey.fingerprint}")

/** Host keys of trusted servers, in an OpenSSH known_hosts file. Unknown keys are never accepted silently. */
class KnownHosts(private val file: File) {

  /** Adds a host key, typically after the user confirmed its fingerprint. */
  @Synchronized
  fun trust(hostKey: HostKey) {
    file.parentFile?.mkdirs()
    file.appendText(hostKey.knownHostsLine + "\n")
  }

  /** A verifier for one connection attempt, which remembers why it rejected a key. */
  internal fun verifier(): Verifier = synchronized(this) { Verifier(file) }

  internal class Verifier(file: File) : OpenSSHKnownHosts(file) {
    var unknown: HostKey? = null
      private set
    var changed: HostKey? = null
      private set

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
      // sshj only compares keys of the same type. A known host presenting a
      // key of another type must not look like a new host, or a
      // man-in-the-middle could get the user to trust it.
      val known = entries().any { runCatching { it.appliesTo(hostname) }.getOrDefault(false) }
      if (known) changed = HostKey(hostname, key) else unknown = HostKey(hostname, key)
      return false
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
      changed = HostKey(hostname, key)
      return false
    }
  }
}
