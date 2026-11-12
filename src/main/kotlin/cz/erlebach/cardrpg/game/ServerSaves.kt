package cz.erlebach.cardrpg.game

import cz.erlebach.gameserver.Protocol
import cz.erlebach.gameserver.SaveSync
import cz.erlebach.gameserver.ServerClient
import cz.erlebach.gameserver.SyncConfig

/**
 * Keeps all saved games in sync with the home server (project ../gameServer). Each game is one save on the server,
 * named after its file (`saves/Paja.json` = "Paja"); the files keep working without the server.
 *
 * - at startup [syncAll] fetches games played on other computers and uploads the ones played here offline,
 * - before a game opens, [refresh] picks up progress made elsewhere meanwhile,
 * - after each save, [saved] uploads it (shortly after, several quick saves become one upload).
 *
 * When both sides changed, [askConflict] decides (true = keep the server copy); the other version is backed up.
 */
class ServerSaves(
    private val server: ServerClient,
    private val saves: SaveManager,
    private val config: SyncConfig,
    private val askConflict: (gameName: String, SaveSync.Conflict) -> Boolean,
) {
    /** Called from the sync thread when the server refused an upload because another computer changed that game meanwhile. */
    @Volatile var onRejected: (gameName: String) -> Unit = {}

    private val syncs = mutableMapOf<String, SaveSync>()

    val address: String get() = server.address

    private fun syncFor(name: String): SaveSync = synchronized(syncs) {
        syncs.getOrPut(name) { SaveSync(server, name, saves.fileFor(name).toPath(), config, onConflict = { onRejected(displayName(name)) }) }
    }

    private fun displayName(name: String) = saves.list().firstOrNull { it.syncName == name }?.name ?: name

    /** Every game on either side; returns a short status line for the start screen. */
    fun syncAll(): String {
        val remote = try {
            server.listSaves()
        } catch (e: Exception) {
            return "⚠️ The game server at ${server.address} doesn't answer – playing on this computer only."
        }
        var downloaded = 0
        var uploaded = 0
        var failed = 0
        for (name in (remote + saves.syncNames()).toSortedSet()) {
            try {
                when (startup(name)) {
                    SaveSync.Outcome.DOWNLOADED, SaveSync.Outcome.KEPT_SERVER -> downloaded++
                    SaveSync.Outcome.UPLOADED, SaveSync.Outcome.KEPT_LOCAL -> uploaded++
                    SaveSync.Outcome.UNCHANGED -> {}
                }
            } catch (e: Exception) {
                System.err.println("Sync of $name failed: $e")
                failed++
            }
        }
        val details = listOfNotNull(
            "$downloaded from the server".takeIf { downloaded > 0 },
            "$uploaded uploaded".takeIf { uploaded > 0 },
            "$failed failed".takeIf { failed > 0 },
        )
        return "🔄 Games synced with the server ${server.address}" + if (details.isEmpty()) "" else " (${details.joinToString()})"
    }

    /** Before opening a game: progress from another computer. Returns false if the server didn't answer. */
    fun refresh(name: String): Boolean = try {
        startup(name)
        true
    } catch (e: Exception) {
        false
    }

    private fun startup(name: String) = syncFor(name).startup { conflict -> askConflict(displayName(name), conflict) }

    fun saved(slot: SaveSlot) {
        if (Protocol.validSaveName(slot.syncName)) syncFor(slot.syncName).scheduleUpload()
    }

    /** Uploads what's pending right away (when the game ends). */
    fun flush() {
        synchronized(syncs) { syncs.values.toList() }.forEach { it.flush() }
    }
}
