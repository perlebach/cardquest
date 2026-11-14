import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  kotlin("multiplatform") version "2.2.20"
  kotlin("plugin.compose") version "2.2.20"
  kotlin("plugin.serialization") version "2.2.20"
  id("org.jetbrains.compose") version "1.9.0"
}

group = "cz.erlebach"
// The game version: shown on the start screen (generated GameVersion.kt) and used for the msi. Raise it for each release.
version = "1.0.3"

val generateVersion by tasks.registering {
  val version = project.version.toString()
  val dir = layout.buildDirectory.dir("generated/version")
  inputs.property("version", version)
  outputs.dir(dir)
  doLast {
    val file = dir.get().file("cz/erlebach/cardrpg/GameVersion.kt").asFile
    file.parentFile.mkdirs()
    file.writeText("package cz.erlebach.cardrpg\n\n/** Generated from build.gradle.kts. */\nconst val GAME_VERSION = \"$version\"\n")
  }
}

repositories {
  mavenCentral()
  google()
}

// Two targets from one code base:
//  - desktop: the Windows exe (src/desktopMain: files on disk, home server sync, updates)
//  - wasmJs:  the web page (src/wasmJsMain: saves in the browser's localStorage)
// The game rules and all screens are shared (src/commonMain).
kotlin {
  jvmToolchain(21)

  jvm("desktop") {
    testRuns["test"].executionTask.configure { useJUnitPlatform() }
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("cardquest")
    browser {
      commonWebpackConfig { outputFileName = "cardquest.js" }
    }
    binaries.executable()
  }

  sourceSets {
    commonMain { kotlin.srcDir(generateVersion) }
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.ui)
      implementation(compose.material3)
      implementation(compose.components.resources)
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    }
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
        // Finding the home server, updates and save sync (project ../gameServer).
        implementation("cz.erlebach:gameserver-client:1.0")
      }
    }
    val desktopTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation("cz.erlebach:server:1.0")  // the real server, for the sync tests
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
      }
    }
    wasmJsMain.dependencies {
      implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3.1")  // window, document, localStorage
    }
  }
}

compose.resources {
  packageOfResClass = "cz.erlebach.cardrpg.resources"
}

// `gradlew test` as before the web version (the tests run on the desktop target)
tasks.register("test") {
  group = "verification"
  description = "Runs the rule and save tests (= desktopTest)."
  dependsOn("desktopTest")
}

/** Classpath of the desktop tests, for the dev helper tasks below (simulation, reports, screenshots). */
val desktopTestClasspath = kotlin.jvm("desktop").compilations["test"].let { files(it.output.allOutputs, it.runtimeDependencyFiles) }

compose.desktop {
  application {
    mainClass = "cz.erlebach.cardrpg.MainKt"
    // run/package with the same JDK 21 the code is compiled for (Gradle itself may run on an older JDK)
    javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
      .get().metadata.installationPath.asFile.absolutePath
    // ./gradlew run -Pdev  -> shows the developer cheat panel
    if (project.hasProperty("dev")) jvmArgs += "-Dcardrpg.dev=true"
    nativeDistributions {
      targetFormats(TargetFormat.Msi)
      packageName = "CardQuest"
      packageVersion = project.version.toString()
    }
  }
}

// ---------------------------------------------------------------- exe + home server
// `gradlew packageExe` -> build/distributions/CardQuest-windows.zip (CardQuest.exe with its own Java; unzip and run).
// Every createDistributable/packageExe also publishes the jars to the home server (../gameServer,
// ~/.gameServer/games/cardQuest/update, other folder: -PgameServerDir=...); the exes download them at their next start.
val distributableApp = layout.buildDirectory.dir("compose/binaries/main/app")

val packageExe by tasks.registering(Zip::class) {
  group = "distribution"
  description = "Zips CardQuest.exe with its bundled Java for copying to other computers."
  dependsOn("createDistributable")
  from(distributableApp)
  archiveFileName.set("CardQuest-windows.zip")
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

val publishToServer by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Publishes the current build of the game to the home server (gameServer)."
  dependsOn("createDistributable")
  val serverDir = providers.gradleProperty("gameServerDir").orNull ?: "${System.getProperty("user.home")}/.gameServer"
  // exactly the jars inside the exe, so an exe of the same build counts as up to date
  from(distributableApp.map { it.dir("CardQuest/app") }) { include("*.jar") }
  into(file("$serverDir/games/cardQuest/update"))
}
afterEvaluate { tasks.named("createDistributable") { finalizedBy(publishToServer) } }


tasks.register<JavaExec>("simulate") {
  description = "Runs the balance simulation bot"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.BalanceSimulationKt")
  // ./gradlew simulate -Pseeds=40  -> more runs per starter (less luck in the averages)
  args(project.findProperty("seeds")?.toString() ?: "6")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("screenshots") {
  description = "Renders the main screens to PNG (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.ScreenshotsKt")
  args(project.findProperty("out")?.toString() ?: "build/screens")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("traits") {
  description = "Prints how many levels the counter skill of each trait boss is worth (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.TraitReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("stages") {
  description = "Prints all dungeon stages: fees, gold, monsters (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.StageReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("economy") {
  description = "Shop hero copy vs raising bought green heroes: replays needed per stage (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.EconomyReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("contest") {
  description = "Contest tuning: monsters and emeralds per wave, how far the saved games get (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.ContestReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("gauntlet") {
  description = "How hard the late dungeons are for all-hero parties: lowest winning effective level per stage (dev helper)"
  classpath = desktopTestClasspath
  dependsOn("desktopTestClasses")
  mainClass.set("cz.erlebach.cardrpg.GauntletReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
