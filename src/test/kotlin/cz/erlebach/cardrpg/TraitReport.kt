package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.Fighter
import cz.erlebach.cardrpg.battle.Phase
import cz.erlebach.cardrpg.battle.Side
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.content.StageDef
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.Rarity
import kotlin.math.pow
import kotlin.random.Random

/**
 * How much the counter skill of a boss trait is worth. For every boss stage it finds the lowest effective level
 * at which a party of 5 equally strong heroes wins (every combination) and prints, for each hero, how many levels
 * the median party with it is below the median party without it. A good trait: its counter (*) is clearly on top. `./gradlew traits` (env TRAIT_SKILL=5: all skills at max level;
 * TRAIT_BOSS_ONLY=1: only the boss wave)
 */
fun main() {
    System.getenv("TRAIT_DEBUG")?.let { debug(it); return }
    val skillLevel = System.getenv("TRAIT_SKILL")?.toInt() ?: 1
    val stages = Dungeons.all.flatMap { it.stages }.filter { it.hasBoss && it.dungeon >= 2 }
    println("Lowest effective level that wins (all heroes equal, skill level $skillLevel); hero value = levels saved by having it (* = the counter)")
    for (st in stages) {
        val trait = st.bossTrait
        val counter = trait?.counterHero
        val parties = subsets(CardType.heroes, 5)
        val levels = parties.associateWith { minLevel(st, it, skillLevel) }
        fun median(l: List<Double>) = l.sorted()[l.size / 2]
        // value of a hero: how many levels lower the median party with it can be than the median party without it
        val value = CardType.heroes.associateWith { h ->
            median(levels.filterKeys { h !in it }.values.toList()) - median(levels.filterKeys { h in it }.values.toList())
        }.entries.sortedByDescending { it.value }
        val best = levels.minBy { it.value }
        // the regular stages around it (best party), to see if the boss is a wall
        val around = listOf(st.stage - 1, st.stage + 1).filter { it in 1..20 }.map { Dungeons.stage("${st.dungeon}-$it")!! }
            .joinToString("/") { n -> "%.1f".format(parties.minOf { minLevel(n, it, skillLevel) }) }
        val bestWithout = if (counter == null) best.value else levels.filterKeys { counter !in it }.values.min()
        println("  ${st.key} %-13s best %.1f, without counter %.1f, stages around %s  hero value: %s".format(
            trait?.title ?: "-", best.value, bestWithout, around,
            value.joinToString("  ") { (h, v) -> "${if (h == counter) "*" else ""}${h.title.take(4)} %+.1f".format(v) }))
    }
}

private fun <T> subsets(list: List<T>, k: Int): List<List<T>> =
    if (k == 0) listOf(emptyList()) else if (list.size < k) emptyList()
    else subsets(list.drop(1), k - 1).map { listOf(list[0]) + it } + subsets(list.drop(1), k)

private fun wins(st: StageDef, party: List<CardType>, eff: Double, skillLevel: Int): Boolean {
    val m = Balance.STAT_GROWTH.pow(eff - 1)
    // the sturdiest three in front (like the bot does)
    val slots = listOf(0, 1, 2, 4, 3)
    val fighters = party.sortedByDescending { it.baseHp }.mapIndexed { i, t ->
        Fighter(1000 + i, Side.PLAYER, t, Rarity.RARE, 0, 1, (t.baseHp * m).toInt(), (t.baseAtk * m).toInt(),
            t.skill, t.skill.power(Rarity.RARE, skillLevel), listOf(slots[i]), skillLevel = skillLevel)
    }
    val waves = if (System.getenv("TRAIT_BOSS_ONLY") != null) st.waves.takeLast(1) else st.waves
    val b = Battle(fighters, waves.map { w -> { w.map(Rules::monsterFighter) } }, Random(1))
    return b.runToEnd() == Phase.WON
}

/** Lowest effective level (0.5 steps) at which [party] wins the stage. */
private fun minLevel(st: StageDef, party: List<CardType>, skillLevel: Int): Double {
    var lo = 1.0
    var hi = 120.0
    while (hi - lo > 0.5) {
        val mid = (lo + hi) / 2
        if (wins(st, party, mid, skillLevel)) hi = mid else lo = mid
    }
    return hi
}

/** TRAIT_DEBUG=4-10:41:MAGE,ROGUE,HUNTER,PALADIN,PRIEST – plays the boss wave once and prints what happens to the boss. */
private fun debug(spec: String) {
    val (key, lvl, heroes) = spec.split(":")
    val st = Dungeons.stage(key)!!
    val party = heroes.split(",").map { CardType.valueOf(it) }
    val m = Balance.STAT_GROWTH.pow(lvl.toDouble() - 1)
    val slots = listOf(0, 1, 2, 4, 3)
    val fighters = party.sortedByDescending { it.baseHp }.mapIndexed { i, t ->
        Fighter(1000 + i, Side.PLAYER, t, Rarity.RARE, 0, 1, (t.baseHp * m).toInt(), (t.baseAtk * m).toInt(),
            t.skill, t.skill.power(Rarity.RARE, 1), listOf(slots[i]), skillLevel = 1)
    }
    val b = Battle(fighters, listOf { st.waves.last().map(Rules::monsterFighter) }, Random(1))
    var n = 0
    while (!b.finished && n++ < 400) {
        for (e in b.advance()) {
            val line = when (e) {
                is cz.erlebach.cardrpg.battle.BattleEvent.Attack -> "${b.fighter(e.attacker)?.name} ${e.skill?.title ?: "attacks"} -> " + e.targets.joinToString { b.fighter(it)?.name ?: "?" }
                is cz.erlebach.cardrpg.battle.BattleEvent.Damage -> "   ${b.fighter(e.target)?.name} -${e.amount} -> ${e.hpAfter}${if (e.blocked) " (blocked)" else ""}"
                is cz.erlebach.cardrpg.battle.BattleEvent.TurnStart, is cz.erlebach.cardrpg.battle.BattleEvent.Heal -> null
                else -> "   $e"
            }
            if (line != null) println(line)
        }
    }
    println(b.phase)
}
