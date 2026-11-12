package cz.erlebach.cardrpg.game

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved game as stored: the player-chosen name and the game. */
@Serializable
data class SaveFile(val name: String, val game: GameState)

/**
 * A named saved game. [key] is where it is stored (the file name without .json on the desktop,
 * the localStorage entry in the browser) and its name on the home server.
 */
data class SaveSlot(val name: String, val key: String, val state: GameState) {
    val syncName: String get() = key
}

/**
 * Named saved games in some storage: files on the desktop (SaveManager), localStorage in the browser.
 * The subclass only stores texts under keys; [validKey] tells which keys it accepts.
 */
abstract class SaveStore(private val validKey: (String) -> Boolean) {
    protected val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** Keys of all stored games, the most recently saved first. */
    protected abstract fun keys(): List<String>

    /** The text stored under [key], or null if there is none. */
    protected abstract fun readText(key: String): String?

    /** Replaces the text under [key] in one step (a crash never leaves half a save behind). */
    protected abstract fun writeText(key: String, text: String)

    /** Where the game [slot] (or, for null, all the games) is stored – shown in the menu. */
    abstract fun location(slot: SaveSlot?): String

    /** All readable saved games, the most recently played first. */
    fun list(): List<SaveSlot> = keys().mapNotNull(::read)

    /** A new (not yet written) game called [name]; throws [GameException] if the name is empty or already used. */
    fun create(name: String): SaveSlot {
        val n = name.trim()
        if (n.isEmpty()) throw GameException("Type a name first!")
        if (n.length > MAX_NAME) throw GameException("That name is too long (max $MAX_NAME letters).")
        if (list().any { it.name.equals(n, ignoreCase = true) }) throw GameException("There already is a game called \"$n\".")
        return SaveSlot(n, freeKey(n), GameState())
    }

    fun save(slot: SaveSlot, state: GameState) =
        writeText(slot.key, json.encodeToString(SaveFile.serializer(), SaveFile(slot.name, state)))

    /** The slot read again from storage (e.g. after the server sync replaced it), or null if it can't be read. */
    fun reload(slot: SaveSlot): SaveSlot? = read(slot.key)

    protected fun read(key: String): SaveSlot? {
        val text = readText(key) ?: return null
        return try {
            val s = json.decodeFromString(SaveFile.serializer(), text)
            SaveSlot(s.name, key, s.game)
        } catch (e: Exception) {
            println("Could not read the saved game $key: ${e.message}")
            null
        }
    }

    /** A key made from [name] (safe as a file name on every OS), not used by another game. */
    private fun freeKey(name: String): String {
        val base = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
            .takeIf(validKey) ?: "game"  // e.g. "con" can't be a file name on Windows
        return generateSequence(1) { it + 1 }
            .map { i -> if (i == 1) base else "$base-$i" }
            .first { readText(it) == null }
    }

    companion object {
        const val LEGACY_NAME = "Paja"
        const val MAX_NAME = 24
    }
}

/** Keeps the saved games in sync with another place (on the desktop: the home server). */
interface GameSync {
    /** Called on the UI thread when an upload was refused because the game was changed elsewhere meanwhile. */
    var onRejected: (gameName: String) -> Unit

    /** Before a game opens: picks up progress made elsewhere. false = couldn't check (e.g. no connection). */
    fun refresh(syncName: String): Boolean

    /** After every save of [slot]. */
    fun saved(slot: SaveSlot)
}
