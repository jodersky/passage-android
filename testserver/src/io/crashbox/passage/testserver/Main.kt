package io.crashbox.passage.testserver

import java.nio.file.Files
import java.nio.file.Paths
import org.apache.sshd.common.config.keys.PublicKeyEntry

/**
 * Serves the bare git repositories in a directory over SSH, for testing the
 * app against. Only keys listed in the authorized_keys file are accepted; it
 * is re-read on every login attempt, so keys can be added while running.
 */
fun main(args: Array<String>) {
  require(args.size >= 2) { "usage: testserver <repos-dir> <authorized_keys> [port]" }
  val repos = Paths.get(args[0]).toAbsolutePath()
  val authorizedKeys = Paths.get(args[1])
  val port = args.getOrNull(2)?.toInt() ?: 2222

  val server = GitSshServer(
    repos = repos,
    authorizedKeys = {
      Files.readAllLines(authorizedKeys)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, null, null) }
    },
    hostKey = Paths.get(System.getProperty("java.io.tmpdir"), "passage-testserver-hostkey"),
    port = port,
    log = ::println,
  ).start()
  println("serving git repositories in $repos on 127.0.0.1:${server.port}, authorized keys from $authorizedKeys")
  Thread.currentThread().join()
}
