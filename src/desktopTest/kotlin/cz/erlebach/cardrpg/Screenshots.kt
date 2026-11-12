package cz.erlebach.cardrpg

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.game.SaveManager
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity
import cz.erlebach.cardrpg.ui.ActiveFight
import cz.erlebach.cardrpg.ui.App
import cz.erlebach.cardrpg.ui.AppModel
import cz.erlebach.cardrpg.ui.Tab
import cz.erlebach.cardrpg.ui.CardArt
import cz.erlebach.cardrpg.ui.StunnedOverlay
import cz.erlebach.cardrpg.ui.CardFace
import cz.erlebach.cardrpg.ui.Palette
import cz.erlebach.cardrpg.ui.look
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlinx.coroutines.swing.Swing

/** Renders the main screens to PNG files (dev helper): gradlew screenshots -Pout=<dir> */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val tmp = kotlin.io.path.createTempDirectory("cardquest").toFile()
    var games = 0
    fun model(state: GameState) = AppModel(SaveManager(tmp)).also { m -> m.newGame("Player ${++games}"); m.update { state } }

    var id = 1L
    fun c(t: CardType, r: Rarity, evo: Int = 0, lv: Int = 1) = OwnedCard(id++, t, r, evo, lv, xp = 40)
    val cards = listOf(
        c(CardType.WARRIOR, Rarity.UNCOMMON, 1, 12), c(CardType.MAGE, Rarity.RARE, 2, 7), c(CardType.ROGUE, Rarity.EPIC, 3, 3),
        c(CardType.BEAR, Rarity.LEGENDARY, 4, 2), c(CardType.SNAIL, Rarity.UNCOMMON, 0, 9), c(CardType.BAT, Rarity.COMMON, 0, 5),
        c(CardType.FROG, Rarity.COMMON), c(CardType.MUSHROOM, Rarity.UNCOMMON), c(CardType.GHOST, Rarity.RARE),
        c(CardType.LION, Rarity.COMMON, 0, 3), c(CardType.IMP, Rarity.UNCOMMON, 1, 4), c(CardType.HUNTER, Rarity.UNCOMMON, 0, 10),
        c(CardType.FOOD, Rarity.COMMON), c(CardType.FOOD, Rarity.RARE, 1),
    )
    val state = GameState(
        started = true, playerLevel = 12, playerXp = 300, gold = 1234, cards = cards,
        party = listOf(1L, 5L, 12L, 2L, 3L, null), cleared = setOf("1-1", "1-2", "1-3", "1-4", "1-5", "2-1"), nextId = id,
        bestStars = mapOf("1-1" to 3, "1-2" to 2, "1-4" to 2),
    )

    fun shot(name: String, m: AppModel?, w: Int = 1280, h: Int = 860, times: List<Long> = listOf(0L), content: @Composable () -> Unit = { App(m!!) }) {
        // Like the real app, run composition, effects and rendering on the Swing thread.
        fun <T> edt(f: () -> T): T { var r: T? = null; javax.swing.SwingUtilities.invokeAndWait { r = f() }; return r!! }
        val scene = edt { ImageComposeScene(w, h, Density(1f), coroutineContext = kotlinx.coroutines.Dispatchers.Swing) { content() } }
        val start = System.nanoTime()
        for ((i, t) in times.withIndex()) {
            // keep rendering frames so animations advance
            val until = System.nanoTime() + t * 1_000_000
            while (System.nanoTime() < until) { edt { scene.render(System.nanoTime() - start) }; Thread.sleep(16) }
            val img = edt { scene.render(System.nanoTime() - start) }
            val file = File(out, if (times.size == 1) "$name.png" else "$name-$i.png")
            file.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        edt { scene.close() }
    }

    val onlyBattle = System.getenv("ONLY_BATTLE") != null
    // all hero cards, big and small
    shot("heroes", null, 1280, 620) {
        Column(Modifier.fillMaxSize().background(Palette.felt).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in CardType.heroes) CardFace(OwnedCard(0, t, Rarity.UNCOMMON).look(), width = 150.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in CardType.heroes) CardFace(OwnedCard(0, t, Rarity.RARE, evo = 1).look(), width = 100.dp)
                for (t in CardType.heroes) CardArt(t, 40.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in CardType.heroes) CardFace(OwnedCard(0, t, Rarity.UNCOMMON).look(), width = 66.dp)
                CardFace(OwnedCard(0, CardType.FOX, Rarity.COMMON).look(), width = 120.dp, overlay = { StunnedOverlay() })
            }
        }
    }
    // the same monsters in every rarity (card background follows the rarity)
    shot("rarities", null, 1280, 760) {
        Column(Modifier.fillMaxSize().background(Palette.felt).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in listOf(CardType.TROLL, CardType.FROG, CardType.OGRE)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Rarity.entries) for (evo in listOf(0, 1).filter { it <= r.maxEvo }) CardFace(OwnedCard(0, t, r, evo, 10).look(), width = 120.dp)
            }
        }
    }
    if (System.getenv("ONLY_HEROES") != null) { println("heroes in ${out.absolutePath}"); kotlin.system.exitProcess(0) }
    if (!onlyBattle) shot("saves", model(state).also { it.closeGame() })
    if (!onlyBattle) shot("starter", model(GameState()))
    if (!onlyBattle) shot("cards", model(state).also { it.tab = Tab.CARDS })
    if (!onlyBattle) shot("party", model(state).also { it.tab = Tab.PARTY })
    if (!onlyBattle) shot("dungeons", model(state).also { it.tab = Tab.DUNGEONS })
    if (!onlyBattle) {
        // meditation: a few rare+ heroes, some clues, one card with cooldown cuts
        val med = state.copy(
            cards = state.cards + listOf(
                c(CardType.PRIEST, Rarity.LEGENDARY, 2, 5).copy(cooldownCuts = 3), c(CardType.PALADIN, Rarity.RARE, 0, 30),
                c(CardType.HUNTER, Rarity.EPIC, 1, 12).copy(cooldownCuts = 1),
            ),
            meditationUnlocked = true, lastClueDay = java.time.LocalDate.now().toEpochDay(), clues = CardType.heroes.associateWith { 12L } + (CardType.BERSERKER to 4L),
        )
        shot("meditation", model(med).also { m -> m.message = null; m.tab = Tab.MEDITATION }, h = 1000)
        shot("meditation-rich", model(med.copy(clues = CardType.heroes.associateWith { 150L })).also { m -> m.message = null; m.tab = Tab.MEDITATION }, h = 1000)
    }
    val bm = model(state.copy(gold = 99999))
    val stage = Dungeons.stage("1-5")!!
    bm.update { s -> Rules.startFight(s, stage).let { (ns, b) -> bm.fight = ActiveFight(stage, b); ns } }
    shot("battle", bm, times = listOf(900, 2500, 40000))
    val all = Dungeons.all.flatMap { d -> d.stages.map { it.key } }
    shot("boss-preview", model(state.copy(cleared = all.toSet() - "2-10")).also { it.tab = Tab.DUNGEONS })
    val woods = Dungeons.all[0].stages.map { it.key }.toSet()
    shot("dungeons-complete", model(state.copy(cleared = woods + setOf("2-1", "2-2"), bestStars = woods.associateWith { 3 }, claimedMilestones = setOf("1:20", "1:40", "1:60"))).also { it.tab = Tab.DUNGEONS })
    val mm = model(state.copy(gold = 99999, skillMode = cz.erlebach.cardrpg.battle.SkillMode.ASK, cleared = all.toSet()))
    val boss = Dungeons.stage("2-10")!!
    mm.update { s -> Rules.startFight(s, boss).let { (ns, b) -> mm.fight = ActiveFight(boss, b); ns } }
    shot("manual", mm, times = listOf(5000))
    tmp.deleteRecursively()
    println("screens in ${out.absolutePath}")
    kotlin.system.exitProcess(0)
}
