package lab.publish

import zio.*
import zio.json.*

import java.security.MessageDigest

/** One request the registry answered. A write is a PUT or a finished blob upload that stored a coordinate. A 401 is a
  * fact and not a coordinate.
  */
final case class Fact(
    authority: String,
    repository: String,
    method: String,
    path: String,
    digest: Option[String],
    bytes: Long,
    auth: String,
    status: Int,
) derives JsonEncoder

object Fact:
  final case class ListView(facts: List[Fact]) derives JsonEncoder

/** In-memory coordinates. Maven bodies stay, because a resolve is a GET of those bytes. Image blob bodies are hashed
  * and dropped; the manifest and the tag map stay.
  */
final class Ledger(facts: Ref[Chunk[Fact]], files: Ref[Map[String, Chunk[Byte]]], blobs: Ref[Map[String, Long]],
    uploads: Ref[Map[String, Ledger.Upload]], tags: Ref[Map[String, Map[String, String]]],
    manifests: Ref[Map[String, Ledger.Manifest]]):

  def record(fact: Fact): UIO[Unit] = facts.update(_ :+ fact)

  def all: UIO[Chunk[Fact]] = facts.get

  def json: UIO[String] = all.map(fs => Fact.ListView(fs.toList).toJson)

  def reset: UIO[Unit] =
    facts.set(Chunk.empty) *> files.set(Map.empty) *> blobs.set(Map.empty) *> uploads.set(Map.empty) *>
      tags.set(Map.empty) *> manifests.set(Map.empty)

  def file(key: String): UIO[Option[Chunk[Byte]]] = files.get.map(_.get(key))

  def fileKeys: UIO[List[String]] = files.get.map(_.keys.toList.sorted)

  /** Drops every stored body whose key contains `needle`. Used to prove a later resolve does not need metadata. */
  def deleteContaining(needle: String): UIO[Int] =
    files.modify { map =>
      val gone = map.keys.filter(_.contains(needle)).toList
      (gone.length, gone.foldLeft(map)((acc, key) => acc.removed(key)))
    }

  /** `None` when a release path is already stored. Snapshots and metadata replace. */
  def putFile(key: String, body: Chunk[Byte], immutable: Boolean): UIO[Option[Chunk[Byte]]] =
    files.modify { map =>
      map.get(key) match
        case Some(existing) if immutable => (None, map)
        case _                           => (Some(body), map.updated(key, body))
    }

  def blobSize(digest: String): UIO[Option[Long]] = blobs.get.map(_.get(digest))

  def putBlob(digest: String, size: Long): UIO[Unit] = blobs.update(_.updated(digest, size))

  def openUpload(id: String): UIO[Unit] =
    uploads.update(_.updated(id, Ledger.Upload(MessageDigest.getInstance("SHA-256"), 0L)))

  def appendUpload(id: String, body: Chunk[Byte]): UIO[Option[Long]] =
    uploads.modify { map =>
      map.get(id) match
        case None => (None, map)
        case Some(up) =>
          if body.nonEmpty then up.digest.update(body.toArray)
          val next = up.copy(size = up.size + body.length)
          (Some(next.size), map.updated(id, next))
    }

  /** Finishes the upload. `Left` is a missing session or a digest mismatch. The session is dropped either way. */
  def finishUpload(id: String, body: Chunk[Byte], expected: String): UIO[Either[String, (String, Long)]] =
    uploads.modify { map =>
      map.get(id) match
        case None => (Left("unknown upload"), map)
        case Some(up) =>
          if body.nonEmpty then up.digest.update(body.toArray)
          val got  = Ledger.hex(up.digest.digest())
          val size = up.size + body.length
          val rest = map.removed(id)
          if got == expected then (Right(got -> size), rest)
          else (Left(s"digest sha256:$got did not match $expected"), rest)
    }

  def putManifest(key: String, manifest: Ledger.Manifest, tagKey: Option[(String, String)]): UIO[Unit] =
    manifests.update(_.updated(key, manifest)) *> tagKey.fold(ZIO.unit) { (repoKey, tag) =>
      tags.update { map =>
        val tagsOf = map.getOrElse(repoKey, Map.empty)
        map.updated(repoKey, tagsOf.updated(tag, manifest.digest))
      }
    }

  def manifest(key: String): UIO[Option[Ledger.Manifest]] = manifests.get.map(_.get(key))

  def tagList(repoKey: String): UIO[Map[String, String]] = tags.get.map(_.getOrElse(repoKey, Map.empty))

end Ledger

object Ledger:
  final case class Upload(digest: MessageDigest, size: Long)
  final case class Manifest(mediaType: String, body: Chunk[Byte], digest: String)

  def make: UIO[Ledger] =
    for
      facts     <- Ref.make(Chunk.empty[Fact])
      files     <- Ref.make(Map.empty[String, Chunk[Byte]])
      blobs     <- Ref.make(Map.empty[String, Long])
      uploads   <- Ref.make(Map.empty[String, Upload])
      tags      <- Ref.make(Map.empty[String, Map[String, String]])
      manifests <- Ref.make(Map.empty[String, Manifest])
    yield Ledger(facts, files, blobs, uploads, tags, manifests)

  def sha256(body: Chunk[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    if body.nonEmpty then md.update(body.toArray)
    hex(md.digest())

  def sha1(body: Chunk[Byte]): String =
    val md = MessageDigest.getInstance("SHA-1")
    if body.nonEmpty then md.update(body.toArray)
    hex(md.digest())

  def md5(body: Chunk[Byte]): String =
    val md = MessageDigest.getInstance("MD5")
    if body.nonEmpty then md.update(body.toArray)
    hex(md.digest())

  def hex(raw: Array[Byte]): String =
    val out = new StringBuilder(raw.length * 2)
    raw.foreach(b => out.append(String.format("%02x", Byte.box(b))))
    out.result()
end Ledger
