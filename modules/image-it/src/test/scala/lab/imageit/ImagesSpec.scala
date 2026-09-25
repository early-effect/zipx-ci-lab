package lab.imageit

import zio.*
import zio.test.*

enum ImageError:
  case Missing(image: String)
  case DockerUnavailable(image: String, reason: String)

object DockerCli:
  /** Process launch is the impure edge here; a non-zero exit from `docker image inspect` means the tag is absent. */
  def inspect(image: String): IO[ImageError, Unit] =
    ZIO
      .attemptBlocking(new ProcessBuilder("docker", "image", "inspect", image).redirectErrorStream(true).start().waitFor())
      .mapError(e => ImageError.DockerUnavailable(image, e.getMessage))
      .filterOrFail(_ == 0)(ImageError.Missing(image))
      .unit

object ImagesSpec extends ZIOSpecDefault:
  private val images: List[String] =
    sys.props.get("lab.images").toList.flatMap(_.split(',').toList).filter(_.nonEmpty)

  def spec = suite("images")(
    test("every service and worker image is in the local daemon after publishLocal") {
      for failures <- ZIO.foreach(images)(image => DockerCli.inspect(image).flip.option).map(_.flatten)
      yield assertTrue(images.size == 4, failures.isEmpty)
    }
  )
