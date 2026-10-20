package io.crashbox.passage.git

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS

/**
 * Runs jgit's SSH transport over sshj, authenticating with a single key pair.
 *
 * With a Keystore key pair on Android, call
 * `net.schmizz.sshj.common.SecurityUtils.setRegisterBouncyCastle(false)`
 * before first use: BouncyCastle cannot sign with Keystore keys.
 *
 * @param keyPair called once per connection, so it may load the key lazily
 */
class SshjSessionFactory(
  private val keyPair: () -> KeyPair,
  private val knownHosts: KnownHosts,
) : SshSessionFactory() {

  override fun getType(): String = "sshj"

  override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS?, tms: Int): RemoteSession {
    val verifier = knownHosts.verifier()
    val client = SSHClient()
    try {
      client.addHostKeyVerifier(verifier)
      val timeout = if (tms > 0) tms * 1000 else DEFAULT_TIMEOUT_MS
      client.connectTimeout = timeout
      client.timeout = timeout
      client.connect(uri.host, if (uri.port > 0) uri.port else 22)
      client.authPublickey(uri.user ?: "git", KeyPairWrapper(keyPair()))
      return SshjSession(client)
    } catch (e: Exception) {
      client.close()
      verifier.changed?.let { throw HostKeyChangedException(uri, it) }
      verifier.unknown?.let { throw UnknownHostKeyException(uri, it) }
      throw e as? TransportException ?: TransportException(uri, e.message ?: e.toString(), e)
    }
  }

  private class SshjSession(private val client: SSHClient) : RemoteSession {
    override fun exec(commandName: String, timeout: Int): Process {
      val session = client.startSession()
      return SshjProcess(session, session.exec(commandName))
    }

    override fun disconnect() = client.close()
  }

  private class SshjProcess(private val session: Session, private val command: Session.Command) : Process() {
    override fun getOutputStream(): OutputStream = command.outputStream
    override fun getInputStream(): InputStream = command.inputStream
    override fun getErrorStream(): InputStream = command.errorStream

    override fun waitFor(): Int {
      command.join()
      return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
      command.join(timeout, unit)
      return !command.isOpen
    }

    override fun exitValue(): Int {
      if (command.isOpen) throw IllegalThreadStateException("command still running")
      return command.exitStatus ?: -1
    }

    override fun destroy() {
      command.close()
      session.close()
    }
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000
  }
}
