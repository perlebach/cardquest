plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "mt"

// Home server (updates + saved games) is a separate project; the desktop exe gets its client library from there.
// Without it (e.g. on GitHub, which builds only the web version) everything works except the desktop target.
if (file("../gameServer").isDirectory) {
  includeBuild("../gameServer")
} else {
  logger.lifecycle("../gameServer not found: only the web version can be built (desktop needs the home server project).")
}
