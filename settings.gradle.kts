plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "mt"

// Home server (updates + saved games) is a separate project; the client library comes from there.
includeBuild("../gameServer")