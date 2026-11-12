import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  kotlin("jvm") version "2.2.20"
  kotlin("plugin.compose") version "2.2.20"
  kotlin("plugin.serialization") version "2.2.20"
  id("org.jetbrains.compose") version "1.9.0"
}

group = "cz.erlebach"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  google()
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  // Finding the home server, updates and save sync (project ../gameServer).
  implementation("cz.erlebach:gameserver-client:1.0")
  testImplementation(kotlin("test"))
  testImplementation("cz.erlebach:server:1.0")  // the real server, for the sync tests
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
}

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
      packageVersion = "1.0.0"
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
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.BalanceSimulationKt")
  // ./gradlew simulate -Pseeds=40  -> more runs per starter (less luck in the averages)
  args(project.findProperty("seeds")?.toString() ?: "6")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("screenshots") {
  description = "Renders the main screens to PNG (dev helper)"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.ScreenshotsKt")
  args(project.findProperty("out")?.toString() ?: "build/screens")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("traits") {
  description = "Prints how many levels the counter skill of each trait boss is worth (dev helper)"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.TraitReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("stages") {
  description = "Prints all dungeon stages: fees, gold, monsters (dev helper)"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.StageReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("economy") {
  description = "Shop hero copy vs raising bought green heroes: replays needed per stage (dev helper)"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.EconomyReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("gauntlet") {
  description = "How hard the late dungeons are for all-hero parties: lowest winning effective level per stage (dev helper)"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("cz.erlebach.cardrpg.GauntletReportKt")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
