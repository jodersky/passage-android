package io.crashbox.passage.testserver

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

/**
 * A minimal SSH server that only accepts the public keys listed in an
 * authorized_keys file and answers every command with a fixed message. It
 * never runs a shell or a process.
 */
fun main(args: Array<String>) {
  require(args.isNotEmpty()) { "usage: testserver <authorized_keys> [port]" }
  val authorizedKeys = Paths.get(args[0])
  val port = args.getOrNull(1)?.toInt() ?: 2222

  val server = SshServer.setUpDefaultServer()
  server.host = "127.0.0.1"
  server.port = port
  server.keyPairProvider = hostKeys()
  server.publickeyAuthenticator = PublickeyAuthenticator { username, key, _ ->
    // re-read on each attempt, so keys can be added while the server runs
    val allowed = Files.readAllLines(authorizedKeys)
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, null, null) }
    val ok = allowed.any { it == key }
    println("auth attempt: user=$username key=${PublicKeyEntry.toString(key)} accepted=$ok")
    ok
  }
  server.commandFactory = CommandFactory { _, command -> FixedReply(command) }
  server.start()
  println("listening on 127.0.0.1:$port, authorized keys from $authorizedKeys")
  Thread.currentThread().join()
}

private fun hostKeys(): KeyPairProvider {
  val path: Path = Paths.get(System.getProperty("java.io.tmpdir"), "passage-testserver-hostkey")
  return SimpleGeneratorHostKeyProvider(path).apply { algorithm = "EC" }
}

private class FixedReply(private val command: String) : Command {
  private lateinit var out: OutputStream
  private lateinit var exit: ExitCallback

  override fun setInputStream(input: InputStream) {}
  override fun setOutputStream(out: OutputStream) { this.out = out }
  override fun setErrorStream(err: OutputStream) {}
  override fun setExitCallback(callback: ExitCallback) { exit = callback }

  override fun start(channel: ChannelSession, env: Environment) {
    out.write("hello ${channel.session.username}, you ran: $command\n".toByteArray())
    out.flush()
    exit.onExit(0)
  }

  override fun destroy(channel: ChannelSession) {}
}
