package cz.erlebach.cardrpg.game

import cz.erlebach.gameserver.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One save file: the player-chosen name and the game. */
@Serializable
data class SaveFile(val name: String, val game: GameState)

/** A named saved game and the file it lives in. */
data class SaveSlot(val name: String, val file: File, val state: GameState) {
    /** Name of this save on the home server: the file name without .json (the player's name is inside the file). */
    val syncName: String get() = file.nameWithoutExtension
}

/**
 * Stores named games as JSON files in ~/.cardquest/saves/ (each written atomically after every change).
 * The old single save (~/.cardquest/save.json) is converted into the game "[LEGACY_NAME]" on first start.
 */
class SaveManager(private val dir: File = File(System.getProperty("user.home"), ".cardquest")) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    val savesDir = File(dir, "saves")

    init {
        migrateLegacySave()
    }

    /** All readable saved games, the most recently played first. */
    fun list(): List<SaveSlot> =
        (savesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> read(f) }

    /** A new (not yet written) game called [name]; throws [GameException] if the name is empty or already used. */
    fun create(name: String): SaveSlot {
        val n = name.trim()
        if (n.isEmpty()) throw GameException("Type a name first!")
        if (n.length > MAX_NAME) throw GameException("That name is too long (max $MAX_NAME letters).")
        if (list().any { it.name.equals(n, ignoreCase = true) }) throw GameException("There already is a game called \"$n\".")
        return SaveSlot(n, freeFile(n), GameState())
    }

    fun save(slot: SaveSlot, state: GameState) {
        savesDir.mkdirs()
        val tmp = File(savesDir, "${slot.file.nameWithoutExtension}.tmp")
        tmp.writeText(json.encodeToString(SaveFile.serializer(), SaveFile(slot.name, state)))
        Files.move(tmp.toPath(), slot.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    val path: String get() = savesDir.absolutePath

    /** The slot read again from its file (after the server sync replaced it), or null if it can't be read. */
    fun reload(slot: SaveSlot): SaveSlot? = if (slot.file.exists()) read(slot.file) else null

    /** File of the save called [syncName] on the server. */
    fun fileFor(syncName: String): File = File(savesDir, "$syncName.json")

    /** Server names of the local saves (files whose name is a valid server save name). */
    fun syncNames(): List<String> =
        (savesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .map { it.nameWithoutExtension }.filter(Protocol::validSaveName)

    private fun read(f: File): SaveSlot? = try {
        val s = json.decodeFromString(SaveFile.serializer(), f.readText())
        SaveSlot(s.name, f, s.game)
    } catch (e: Exception) {
        System.err.println("Could not read save file ${f.name}: ${e.message}")
        null
    }

    /** A file name made from [name] (safe on every OS), not used by another game. */
    private fun freeFile(name: String): File {
        val base = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
            .takeIf(Protocol::validSaveName) ?: "game"  // e.g. "con" can't be a file name on Windows
        return generateSequence(1) { it + 1 }
            .map { i -> File(savesDir, if (i == 1) "$base.json" else "$base-$i.json") }
            .first { !it.exists() }
    }

    private fun migrateLegacySave() {
        val legacy = File(dir, "save.json")
        if (!legacy.exists()) return
        try {
            val state = json.decodeFromString(GameState.serializer(), legacy.readText())
            if (list().none { it.name.equals(LEGACY_NAME, ignoreCase = true) }) save(create(LEGACY_NAME), state)
            Files.move(legacy.toPath(), File(dir, "save.migrated.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            System.err.println("Could not convert the old save file: ${e.message}")
        }
    }

    companion object {
        const val LEGACY_NAME = "Paja"
        const val MAX_NAME = 24
    }
}
