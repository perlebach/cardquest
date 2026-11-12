package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.game.GameException
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.game.SaveManager
import cz.erlebach.cardrpg.model.CardType
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveManagerTest {
    private val dir = createTempDirectory("cardquest-test").toFile()

    @Test
    fun oldSaveBecomesPaja() {
        val old = Rules.newGame(CardType.MAGE).copy(gold = 777)
        File(dir, "save.json").writeText(Json.encodeToString(GameState.serializer(), old))
        val saves = SaveManager(dir)
        val slots = saves.list()
        assertEquals(listOf("Paja"), slots.map { it.name })
        assertEquals(old, slots.single().state)
        assertFalse(File(dir, "save.json").exists())
        assertTrue(File(dir, "save.migrated.json").exists())
        // a second start doesn't convert again
        assertEquals(1, SaveManager(dir).list().size)
        dir.deleteRecursively()
    }

    @Test
    fun namedGames() {
        val saves = SaveManager(dir)
        val a = saves.create("Ája / 1")
        saves.save(a, Rules.newGame(CardType.WARRIOR))
        val b = saves.create("Bob")
        saves.save(b, Rules.newGame(CardType.ROGUE))
        assertEquals(setOf("Ája / 1", "Bob"), saves.list().map { it.name }.toSet())
        assertFailsWith<GameException> { saves.create("bob") }
        assertFailsWith<GameException> { saves.create("  ") }
        val warrior = saves.list().first { it.name == "Ája / 1" }.state
        assertEquals(warrior.partyCards.single().let { it.hp + it.atk }.toLong(), warrior.partyStrength)
        dir.deleteRecursively()
    }
}
