package cz.erlebach.cardrpg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.content.StageDef
import cz.erlebach.cardrpg.game.GameException
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.game.SaveManager
import cz.erlebach.cardrpg.game.SaveSlot
import cz.erlebach.cardrpg.game.ServerSaves

enum class Tab(val title: String, val icon: String) {
    CARDS("Cards", "🃏"), PARTY("Party", "🛡️"), DUNGEONS("Dungeons", "🏰"), MEDITATION("Meditation", "🪷"), DEV("Dev", "🛠️")
}

class ActiveFight(val stage: StageDef, val battle: Battle)

/**
 * Holds the game state for the UI; every change is saved immediately (once a game is open)
 * and, with a home server ([sync]), uploaded there too. [syncStatus] is shown on the saved-games screen.
 */
class AppModel(
    private val saves: SaveManager = SaveManager(),
    private val sync: ServerSaves? = null,
    val syncStatus: String? = null,
) {
    /** The open game; null = the saved-games screen is shown. */
    var slot by mutableStateOf<SaveSlot?>(null)
        private set
    var state by mutableStateOf(GameState())
        private set
    /** Saved games for the selection screen. */
    var slots by mutableStateOf(saves.list())
        private set
    var tab by mutableStateOf(Tab.DUNGEONS)
    var fight by mutableStateOf<ActiveFight?>(null)
    var message by mutableStateOf<String?>(null)
    val devMode = System.getProperty("cardrpg.dev") == "true"
    val savePath get() = slot?.file?.absolutePath ?: saves.path
    /** Today as a local epoch day (meditation clues come at local midnight); replaceable for tests. */
    var today: () -> Long = { java.time.LocalDate.now().toEpochDay() }

    /**
     * Applies a rule; shows the error message instead of crashing on a [GameException].
     * Every change also brings meditation up to date (opens the tab, adds the clues of passed nights).
     */
    fun update(change: (GameState) -> GameState): Boolean = try {
        val (new, gained) = Rules.collectClues(change(state), today())
        val opened = new.meditationUnlocked && !state.meditationUnlocked && new.started
        state = new
        slot?.let {
            saves.save(it, new)
            sync?.saved(it)
        }
        if (gained.isNotEmpty()) message = cluesMessage(gained)
        else if (opened) message = "🪷 Your first ${Balance.MEDITATION_RARITY.label} hero! The Meditation tab is open: " +
            "${Balance.MEDITATION_RARITY.label} and better heroes meditate every night and find clues."
        true
    } catch (e: GameException) {
        message = e.message
        false
    }

    /** Collects the meditation clues if a midnight passed (called on opening a game and regularly while it runs). */
    fun refreshMeditation() {
        if (slot != null && state.started && fight == null) update { it }
    }

    private fun cluesMessage(gained: Map<CardType, Long>) =
        "🪷 Your heroes meditated and found clues:\n" + gained.entries.joinToString("\n") { (t, n) -> "${t.title}: +$n" }

    init {
        sync?.onRejected = { game ->
            javax.swing.SwingUtilities.invokeLater {
                message = "⚠️ \"$game\" was changed on another computer meanwhile. Your progress is saved on this computer only " +
                    "for now; next time you open the game you'll choose which version to keep."
            }
        }
    }

    fun open(s: SaveSlot) {
        // progress from another computer (asks if both changed); the file may have been replaced
        show(if (sync != null && sync.refresh(s.syncName)) saves.reload(s) ?: s else s)
    }

    private fun show(s: SaveSlot) {
        slot = s
        state = s.state
        fight = null
        tab = Tab.DUNGEONS
        refreshMeditation()
    }

    /** Opens a new game called [name] (it goes to the starter pick; saved once a hero is chosen). */
    fun newGame(name: String): Boolean = try {
        show(saves.create(name))
        true
    } catch (e: GameException) {
        message = e.message
        false
    }

    /** Back to the saved-games screen. */
    fun closeGame() {
        slot = null
        fight = null
        state = GameState()
        slots = saves.list()
    }

    fun reset() {
        fight = null
        tab = Tab.DUNGEONS
        update { GameState() }
    }
}
