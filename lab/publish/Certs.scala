package lab.publish

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Base64

/** A lab CA for the loopback listeners. sbt trusts a PKCS12 that also holds the JVM's public CAs, so plugin resolution
  * still reaches Maven Central. Docker trusts the same certificate from `certs.d`.
  */
object Certs:
  val StorePass = "zipx-lab"

  final case class Material(certPem: String, keyPem: String, trustStore: Path)

  def generate: IO[ProofError, Material] =
    ZIO.attemptBlocking {
      val dir   = Files.createTempDirectory("zipx-proof-tls")
      val store = dir.resolve("identity.p12")
      val code  = new ProcessBuilder(
        "keytool",
        "-genkeypair",
        "-alias",
        "zipx-lab",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "2",
        "-storetype",
        "PKCS12",
        "-keystore",
        store.toString,
        "-storepass",
        StorePass,
        "-keypass",
        StorePass,
        "-dname",
        "CN=127.0.0.1",
        "-ext",
        "SAN=ip:127.0.0.1,dns:localhost,dns:host.docker.internal",
      ).inheritIO().start().waitFor()
      if code != 0 then throw new RuntimeException(s"keytool exited $code")
      val ks = KeyStore.getInstance("PKCS12")
      val in = Files.newInputStream(store)
      try ks.load(in, StorePass.toCharArray)
      finally in.close()
      val cert = Option(ks.getCertificate("zipx-lab")) match
        case Some(found) => found
        case None        => throw new RuntimeException("keytool stored no certificate")
      val key = Option(ks.getKey("zipx-lab", StorePass.toCharArray)) match
        case Some(pk: java.security.PrivateKey) => pk
        case Some(other)                        => throw new RuntimeException(s"keytool stored a ${other.getClass.getName}")
        case None                               => throw new RuntimeException("keytool stored no private key")
      val trust = dir.resolve("trust.p12")
      writeTrust(cert, trust)
      Material(pem("CERTIFICATE", cert.getEncoded), pem("PRIVATE KEY", key.getEncoded), trust)
    }.mapError(e => ProofError.Cert(String.valueOf(e.getMessage)))

  def installDocker(certPem: String, hosts: List[String]): IO[ProofError, Unit] =
    ZIO.attemptBlocking {
      val home = Path.of(sys.props("user.home"), ".docker", "certs.d")
      hosts.foreach { host =>
        val dir = home.resolve(host)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ca.crt"), certPem)
      }
    }.mapError(e => ProofError.Cert(String.valueOf(e.getMessage))) *> linuxCerts(certPem, hosts) *>
      ZIO.attemptBlocking {
        ensureDockerHostName()
        installIntoColima(hosts)
      }.mapError(e => ProofError.Cert(String.valueOf(e.getMessage)))

  private def writeTrust(cert: Certificate, dest: Path): Unit =
    val cacerts = Path.of(sys.props("java.home"), "lib", "security", "cacerts")
    val ks      = KeyStore.getInstance("PKCS12")
    val in      = Files.newInputStream(cacerts)
    try ks.load(in, "changeit".toCharArray)
    finally in.close()
    ks.setCertificateEntry("zipx-lab", cert)
    val out = Files.newOutputStream(dest)
    try ks.store(out, StorePass.toCharArray)
    finally out.close()

  private def pem(kind: String, der: Array[Byte]): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
    s"-----BEGIN $kind-----\n$body\n-----END $kind-----\n"

  /** A Linux daemon on this machine resolves `host.docker.internal` only after it is in `/etc/hosts`. Colima already
    * has the name, and it must keep pointing at the host gateway.
    */
  private def ensureDockerHostName(): Unit =
    val os = sys.props("os.name").toLowerCase
    if os.contains("mac") || os.contains("windows") then ()
    else
      val text = Files.readString(Path.of("/etc/hosts"))
      if text.split("\\s+").contains(Registry.DockerRegistryHost) then ()
      else
        val tee = new ProcessBuilder("sudo", "tee", "-a", "/etc/hosts").redirectErrorStream(true).start()
        val raw = tee.getOutputStream
        raw.write(s"127.0.0.1 ${Registry.DockerRegistryHost}\n".getBytes(StandardCharsets.UTF_8))
        raw.close()
        val code = tee.waitFor()
        if code != 0 then throw new RuntimeException(s"sudo tee /etc/hosts exited $code")

  /** Colima's dockerd reads `/etc/docker/certs.d` inside the VM. The home directory is mounted there, so the CA just
    * written under `~/.docker/certs.d` can be copied across.
    */
  private def installIntoColima(hosts: List[String]): Unit =
    if daemonName != "colima" then ()
    else
      val home = sys.props("user.home")
      val script = hosts
        .map { host =>
          val src = s"$home/.docker/certs.d/$host/ca.crt"
          val dir = s"/etc/docker/certs.d/$host"
          s"sudo mkdir -p '$dir' && sudo cp '$src' '$dir/ca.crt'"
        }
        .mkString(" && ")
      val code = new ProcessBuilder("colima", "ssh", "--", "bash", "-lc", script).inheritIO().start().waitFor()
      if code != 0 then throw new RuntimeException(s"colima cert install exited $code")

  private def daemonName: String =
    val proc = new ProcessBuilder("docker", "info", "--format", "{{.Name}}").redirectErrorStream(true).start()
    val text = String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
    val code = proc.waitFor()
    if code == 0 then text else ""

  private def linuxCerts(certPem: String, hosts: List[String]): IO[ProofError, Unit] =
    if !sys.props("os.name").toLowerCase.contains("linux") then ZIO.unit
    else
      ZIO.foreachDiscard(hosts) { host =>
        ZIO.attemptBlocking {
          val dir = s"/etc/docker/certs.d/$host"
          val mk  = new ProcessBuilder("sudo", "mkdir", "-p", dir).inheritIO().start().waitFor()
          if mk != 0 then throw new RuntimeException(s"sudo mkdir $dir exited $mk")
          val tee = new ProcessBuilder("sudo", "tee", s"$dir/ca.crt").redirectErrorStream(true).start()
          val raw = tee.getOutputStream
          raw.write(certPem.getBytes(StandardCharsets.UTF_8))
          raw.close()
          val code = tee.waitFor()
          if code != 0 then throw new RuntimeException(s"sudo tee $dir/ca.crt exited $code")
        }.mapError(e => ProofError.Cert(String.valueOf(e.getMessage)))
      }
end Certs
