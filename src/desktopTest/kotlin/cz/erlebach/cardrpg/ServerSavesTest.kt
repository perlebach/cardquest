package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.game.SaveManager
import cz.erlebach.cardrpg.game.ServerSaves
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.ui.AppModel
import cz.erlebach.gameserver.Io
import cz.erlebach.gameserver.ServerClient
import cz.erlebach.gameserver.SyncConfig
import cz.erlebach.gameserver.server.GameServer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saved games travel between "computers" (separate home directories) through the real home server. */
class ServerSavesTest {
    private val root: Path = Files.createTempDirectory("cardquest-sync")
    private val server = GameServer(root.resolve("server"), port = 0, log = {}).apply { start(withDiscovery = false) }
    private val client = ServerClient("127.0.0.1:${server.port}", "cardQuest")

    init {
        Files.createDirectories(server.gamesDir.resolve("cardQuest"))  // what publishToServer does
    }

    @AfterTest
    fun cleanup() {
        server.stop()
        Io.deleteRecursively(root)
    }

    private inner class Pc(name: String, val keepServerCopy: Boolean = true) {
        val dir: File = root.resolve(name).toFile()
        val saves = SaveManager(dir)
        var asked = 0
        val sync = ServerSaves(client, saves, SyncConfig(dir.toPath().resolve("sync.properties"))) { _, _ -> asked++; keepServerCopy }
        val status = sync.syncAll()
        val model = AppModel(saves, sync, status)

        fun play(game: String, gold: Long) {
            val slot = saves.list().firstOrNull { it.name == game }
            if (slot == null) {
                model.newGame(game)
                model.update { Rules.newGame(CardType.WARRIOR).copy(gold = gold) }
            } else {
                model.open(slot)
                model.update { it.copy(gold = gold) }
            }
            sync.flush()
            model.closeGame()
        }

        fun gold(game: String) = saves.list().first { it.name == game }.state.gold
    }

    @Test
    fun `games move between computers`() {
        val son = Pc("son")
        son.play("Paja", 100L)
        son.play("Ája / 2", 5L)  // a name that isn't a valid file name

        val wife = Pc("wife")
        assertTrue("2 from the server" in wife.status, wife.status)
        assertEquals(setOf("Paja", "Ája / 2"), wife.saves.list().map { it.name }.toSet())
        assertEquals(100L, wife.gold("Paja"))
        wife.play("Paja", 200L)

        // the son's game is still open in the list from before – opening it picks up the progress
        son.model.open(son.saves.list().first { it.name == "Paja" })
        assertEquals(200L, son.model.state.gold)
        assertEquals(0, son.asked + wife.asked)
    }

    @Test
    fun `games played offline are uploaded and a conflict is asked once and then resolved`() {
        val son = Pc("son")
        son.play("Paja", 100L)
        val wife = Pc("wife")

        son.model.open(son.saves.list().first { it.name == "Paja" })  // the son plays...
        wife.play("Paja", 300L)                                        // ...while the same game is played elsewhere
        son.model.update { it.copy(gold = 150L) }
        son.sync.flush()  // the server refuses: the wife's computer changed it meanwhile
        assertEquals(300L, Pc("check").gold("Paja"))
        assertEquals(150L, son.gold("Paja"), "kept on the son's computer")

        son.model.closeGame()
        son.model.open(son.saves.list().first { it.name == "Paja" })  // asks; keeps the server copy
        assertEquals(1, son.asked)
        assertEquals(300L, son.model.state.gold)
        son.model.update { it.copy(gold = 400L) }  // uploads work again after the conflict was resolved
        son.sync.flush()
        assertEquals(400L, Pc("check2").gold("Paja"))
    }
}
