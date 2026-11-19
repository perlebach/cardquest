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
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity
import kotlin.math.pow
import kotlin.random.Random

/**
 * How hard the late dungeons are for an all-hero party. For every stage it tries every party of 5 different heroes
 * (56 of them, the sturdiest three in front like the bot does) and finds the lowest effective level at which the party wins.
 * Prints the best and the median party's level, and how many parties win at the reference levels
 * (maxed purple++ = 70, purple+++ = 77.5, legendary = 80, legendary+ = 87.5, … with [Balance.LEGENDARY_HERO_BONUS]).
 * `./gradlew gauntlet` (env GAUNTLET_DUNGEONS=8,9,10; GAUNTLET_RARITY=EPIC: rarity of the skills; GAUNTLET_SKILL=5: skill level;
 * GAUNTLET_CUTS=2: meditation cooldown cuts; GAUNTLET_PASSIVE=2: passive ability level)
 */
fun main() {
    val dungeons = (System.getenv("GAUNTLET_DUNGEONS") ?: "8,9,10").split(",").map { it.trim().toInt() }
    val rarity = Rarity.valueOf(System.getenv("GAUNTLET_RARITY") ?: "EPIC")
    val skill = System.getenv("GAUNTLET_SKILL")?.toInt() ?: 5
    val cuts = System.getenv("GAUNTLET_CUTS")?.toInt() ?: 0
    val passive = System.getenv("GAUNTLET_PASSIVE")?.toInt() ?: 0
    val opts = Opts(rarity, skill, cuts, passive)
    // maxed hero cards (legendary ones with their bonus)
    fun maxed(r: Rarity, evo: Int) = Balance.cardEffectiveLevel(CardType.PALADIN, r.maxLevel, r, evo)
    val refs = listOf(
        "P++" to maxed(Rarity.EPIC, 2), "P+++" to maxed(Rarity.EPIC, 3),
        "L" to maxed(Rarity.LEGENDARY, 0), "L+" to maxed(Rarity.LEGENDARY, 1), "L++" to maxed(Rarity.LEGENDARY, 2),
        "L+++" to maxed(Rarity.LEGENDARY, 3),
    )
    val parties = subsets(CardType.heroes, 5)
    println("All-hero parties, skills ${rarity.label} level $skill, $cuts cooldown cuts, passive level $passive. Lowest winning effective level: best party / median party;")
    println("share of the 56 parties that win at " + refs.joinToString(", ") { (n, l) -> "$n = $l" } + "; best party")
    for (st in Dungeons.all.filter { it.id in dungeons }.flatMap { it.stages }) {
        val levels = parties.parallelStream().map { minLevel(st, it, opts) }.toList()
        val best = parties[levels.indexOf(levels.min())]
        val median = levels.sorted()[levels.size / 2]
        val boss = st.waves.last().firstOrNull { it.type.boss }?.let { " boss ${it.type.title}${it.type.trait?.let { t -> " (${t.title})" } ?: ""}" } ?: ""
        println("  %-5s best %5.1f  median %5.1f   %s   %s%s".format(
            st.key, levels.min(), median,
            refs.joinToString("  ") { (n, l) -> "$n %3d%%".format(100 * levels.count { it <= l } / levels.size) },
            best.joinToString("/") { it.title.take(4) }, boss,
        ))
    }
}

private fun <T> subsets(list: List<T>, k: Int): List<List<T>> =
    if (k == 0) listOf(emptyList()) else if (list.size < k) emptyList()
    else subsets(list.drop(1), k - 1).map { listOf(list[0]) + it } + subsets(list.drop(1), k)

private data class Opts(val rarity: Rarity, val skill: Int, val cuts: Int, val passive: Int)

private fun wins(st: StageDef, party: List<CardType>, eff: Double, o: Opts): Boolean {
    val m = Balance.STAT_GROWTH.pow(eff - 1)
    val slots = listOf(0, 1, 2, 4, 3)
    val fighters = party.sortedByDescending { it.baseHp }.mapIndexed { i, t ->
        val p = Passive.of(t)!!
        Fighter(100_000 + i, Side.PLAYER, t, o.rarity, 0, 1,
            (t.baseHp * m * (1 + p.hpBonus(o.passive))).toInt(), (t.baseAtk * m * (1 + p.atkBonus(o.passive))).toInt(),
            t.skill, t.skill.power(o.rarity, o.skill), listOf(slots[i]), skillLevel = o.skill,
            skillCooldown = Balance.SKILL_COOLDOWN - o.cuts, passiveLevel = o.passive)
    }
    val b = Battle(fighters, st.waves.map { w -> { w.map(Rules::monsterFighter) } }, Random(1))
    return b.runToEnd() == Phase.WON
}

/** Lowest effective level (0.5 steps) at which [party] wins the stage (200 = not even there). */
private fun minLevel(st: StageDef, party: List<CardType>, o: Opts): Double {
    var lo = 1.0
    var hi = 200.0
    if (!wins(st, party, hi, o)) return hi
    while (hi - lo > 0.5) {
        val mid = (lo + hi) / 2
        if (wins(st, party, mid, o)) hi = mid else lo = mid
    }
    return hi
}
