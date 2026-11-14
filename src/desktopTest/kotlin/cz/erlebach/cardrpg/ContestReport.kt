package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.content.Contest
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.game.SaveFile
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

/**
 * Contest tuning: monster strength and diamonds per wave, then how far the real saves (~/.cardquest/saves) get and how many
 * diamonds they earn per ticket – without passives and with every party hero's passive at level 1 / 2 / 3. `./gradlew contest`
 */
fun main() {
    println("== waves (averages of 300 random waves)")
    var total = 0.0
    for (w in 0 until 20) {
        val rolls = List(300) { Contest.wave(w, Random(w * 1000 + it)).map { Rules.monsterFighter(it, contest = true) } }
        val em = rolls.map { r -> r.sumOf { it.diamonds } }.average()
        total += em
        val hp = rolls.flatten().map { it.maxHp }.average()
        val atk = rolls.flatten().map { it.atk }.average()
        val f = rolls.first().first()
        println(
            "wave %2d  %-6s tier %3d  hp %8.0f  atk %7.0f  e.g. %s%s L%d   diamonds %7.1f  (total %8.0f)".format(
                w + 1, Contest.stageLabel(w), Contest.tier(w), hp, atk, f.rarity.label, "+".repeat(f.evo), f.level, em, total,
            )
        )
    }
    val json = Json { ignoreUnknownKeys = true }
    val dir = File(System.getProperty("user.home"), ".cardquest/saves")
    for (file in dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }) {
        val s = json.decodeFromString(SaveFile.serializer(), file.readText()).game
        println("== ${file.nameWithoutExtension}: player level ${s.playerLevel}, party " +
            s.partyCards.joinToString { "L${it.level} ${it.fullName}" })
        for (pl in 0..3) {
            val st = s.copy(cards = s.cards.map { if (it.type.hero && s.inParty(it.id)) it.copy(passiveLevel = pl) else it })
            report("passives L$pl", st)
        }
    }
}

private fun report(label: String, s: GameState) {
    val runs = (0 until 200).map { i ->
        val b = Rules.startContest(s.copy(contestUnlocked = true, contestTickets = 1), Random(i)).second
        b.runToEnd()
        b.wavesCleared to b.diamonds
    }
    val waves = runs.map { it.first }.sorted()
    val em = runs.map { it.second }.sorted()
    println("  %-12s waves min %2d median %2d max %2d   diamonds min %6d median %6d avg %8.0f max %7d".format(
        label, waves.first(), waves[waves.size / 2], waves.last(), em.first(), em[em.size / 2], em.average(), em.last(),
    ))
}
