package cz.erlebach.cardrpg.game

import cz.erlebach.gameserver.Protocol
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores named games as JSON files in ~/.cardquest/saves/ (each written atomically after every change).
 * The old single save (~/.cardquest/save.json) is converted into the game "[LEGACY_NAME]" on first start.
 * File names are also the games' names on the home server, so only valid server names are used.
 */
class SaveManager(private val dir: File = File(System.getProperty("user.home"), ".cardquest")) : SaveStore(Protocol::validSaveName) {
    val savesDir = File(dir, "saves")

    init {
        migrateLegacySave()
    }

    override fun keys(): List<String> =
        (savesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { it.nameWithoutExtension }

    override fun readText(key: String): String? = fileFor(key).takeIf { it.exists() }?.readText()

    override fun writeText(key: String, text: String) {
        savesDir.mkdirs()
        val tmp = File(savesDir, "$key.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), fileFor(key).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun location(slot: SaveSlot?): String = (slot?.let { fileFor(it.key) } ?: savesDir).absolutePath

    /** File of the save called [syncName] on the server. */
    fun fileFor(syncName: String): File = File(savesDir, "$syncName.json")

    /** Server names of the local saves (files whose name is a valid server save name). */
    fun syncNames(): List<String> = keys().filter(Protocol::validSaveName)

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
}
