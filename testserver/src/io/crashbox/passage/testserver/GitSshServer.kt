package io.crashbox.passage.testserver

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.PublicKey
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack

/**
 * A minimal git server over SSH, for testing. It serves the git repositories
 * under [repos] (`git-upload-pack` and `git-receive-pack` only, implemented
 * with jgit; it never runs a shell or a process) to clients authenticating
 * with one of [authorizedKeys].
 *
 * @param hostKey where to store the generated host key, so that it stays the
 *   same across restarts
 * @param port 0 picks a free port
 */
class GitSshServer(
  private val repos: Path,
  private val authorizedKeys: () -> Collection<PublicKey>,
  hostKey: Path,
  port: Int = 0,
  private val log: (String) -> Unit = {},
) : AutoCloseable {
  private val server: SshServer = SshServer.setUpDefaultServer().apply {
    host = "127.0.0.1"
    this.port = port
    keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey).apply {
      algorithm = "EC"
      keySize = 256
    }
    publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
      val ok = key in authorizedKeys()
      log("auth attempt: user=$username accepted=$ok")
      ok
    }
    keyboardInteractiveAuthenticator = null
    commandFactory = CommandFactory { _, command -> GitCommand(command) }
  }

  val port: Int get() = server.port

  fun start(): GitSshServer = apply { server.start() }

  override fun close() = server.stop(true)

  private inner class GitCommand(private val command: String) : Command {
    private lateinit var input: InputStream
    private lateinit var out: OutputStream
    private lateinit var err: OutputStream
    private lateinit var exit: ExitCallback

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(out: OutputStream) { this.out = out }
    override fun setErrorStream(err: OutputStream) { this.err = err }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
      log("command: $command")
      Thread {
        try {
          val (verb, path) = parse(command)
          openRepository(path).use { repo ->
            when (verb) {
              "git-upload-pack" -> UploadPack(repo).upload(input, out, err)
              "git-receive-pack" -> ReceivePack(repo).receive(input, out, err)
            }
          }
          out.flush()
          exit.onExit(0)
        } catch (e: Exception) {
          log("command failed: $e")
          err.write("error: ${e.message}\n".toByteArray())
          err.flush()
          exit.onExit(1, e.message ?: "error")
        }
      }.start()
    }

    override fun destroy(channel: ChannelSession) {}
  }

  private fun parse(command: String): Pair<String, String> {
    val match = Regex("""^(git-upload-pack|git-receive-pack) '?/?([^']*)'?$""").matchEntire(command)
      ?: throw IllegalArgumentException("unsupported command")
    return match.groupValues[1] to match.groupValues[2]
  }

  private fun openRepository(path: String): Repository {
    val dir = repos.resolve(path).normalize()
    require(dir.startsWith(repos.normalize()) && dir != repos.normalize()) { "invalid repository path" }
    return FileRepositoryBuilder().setGitDir(dir.toFile()).setMustExist(true).build()
  }
}
