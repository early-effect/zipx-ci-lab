# zipx-ci-lab

A CI lab that measures one GitHub Actions run. It is not a library. [What is measured](https://www.earlyeffect.rocks/zipx-ci-lab/).

This checkout pins sbt-zipx `0.20.2-d614c3776a86-SNAPSHOT` and runs sbt jobs in the pinned `sbtscala/scala-sbt` image. How that image relates to the LocalDir snapshot, what the cloud agent needs as a Runtime Secret, and what the pin companion commits are on [Caching](https://www.earlyeffect.rocks/zipx/caching), [Packs](https://www.earlyeffect.rocks/zipx/packs) (Cloud agent), and [Pin feeds](https://www.earlyeffect.rocks/zipx/pin-feeds).
