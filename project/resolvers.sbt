// A zipx branch build under test, committed as a Maven directory so CI needs no registry token. See README.md.
resolvers += "zipx-snapshot" at (baseDirectory.value / "zipx-snapshot").toURI.toString
