package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.content.StageDef
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.Rarity

/**
 * Shop copy vs "green path": how many replays of a stage it takes to get one more hero copy of a rarity
 * (evolution material) by buying it in the shop (selling every dropped card) or by buying green heroes and
 * raising/evolving them with the dropped cards (selling only as many drops as the gold needs).
 * Replays only (no first-clear bonus), no same-skill bonus, no cakes. `./gradlew economy`
 * Env ECON_EFF=1.0,0.8 (multipliers of the feed XP to compare), ECON_SELL=1 (multipliers of the sell price), ECON_STAGES=1,10,16,19.
 */

private data class Cost(val gold: Double, val xp: Double) {
    operator fun plus(o: Cost) = Cost(gold + o.gold, xp + o.xp)
    operator fun times(k: Int) = Cost(gold * k, xp * k)
}

/** Gold + hero XP to make one level 1 hero card of [r] (evo 0) from bought green cards. */
private fun copyCost(r: Rarity): Cost {
    if (r == Balance.HERO_BUY_RARITY) return Cost(Balance.heroPrice(0).toDouble(), 0.0)
    val prev = Rarity.entries[r.ordinal - 1]
    var c = copyCost(prev)
    for (evo in 0..prev.maxEvo)
        c = c + Cost(Balance.evolveGoldCost(prev, evo).toDouble(), Balance.cardXpTotal(prev.maxLevel, true).toDouble()) +
            copyCost(prev) * Balance.evolveMaterials(evo)
    return c
}

/** Per replay: gold from the stage (fee paid), XP of the expected drops (fed at [eff]), gold if they are sold instead. */
private fun perRun(st: StageDef, eff: Double, sellFactor: Double): Triple<Double, Double, Double> {
    var gold = -st.entryFee.toDouble(); var xp = 0.0; var sell = 0.0
    for (m in st.waves.flatten()) {
        if (m.type.boss) {
            gold += m.gold
            xp += Balance.FOOD_DROP_CHANCE * Balance.foodXp(m.rarity, m.evo) // cakes can't be sold
        } else {
            gold += m.gold * (1 - m.dropChance)
            val lvl = Balance.lootLevel(m.level, m.rarity, 999)
            xp += m.dropChance * eff * Balance.sacrificeValue(lvl, m.rarity, m.evo)
            sell += m.dropChance * Balance.sellPrice(m.rarity, m.evo, lvl) * sellFactor
        }
    }
    return Triple(gold, xp, sell)
}

private val LADDER = Rarity.entries.flatMap { r -> (0..r.maxEvo).map { r to it } }

private fun dropChance(r: Rarity, evo: Int) =
    when (r) { Rarity.COMMON -> 0.25; Rarity.UNCOMMON -> 0.2; Rarity.RARE -> 0.15; Rarity.EPIC -> 0.1; Rarity.LEGENDARY -> 0.0 } * Math.pow(0.75, evo.toDouble())

/**
 * Idea 5b2 (`ECON_MIX=1`): every regular monster keeps its strength (effective level) but its rarity is spread over the ladder
 * steps 0..its current step (uniform, or "pyramid" = the lower the step, the more common: weight top−k+1). Levels may go above
 * the rarity's max; a looted card feeds and sells at its real level (slower growth above the max). Per replay: drops, feed XP, skill-up
 * chances (sum of the per-card chances, same skill assumed), gold (the monster's stage share, paid if it doesn't drop).
 */
private fun mixReport(stages: List<Int>) {
    data class Agg(var drops: Double = 0.0, var xp: Double = 0.0, var skill: Double = 0.0, var gold: Double = 0.0, var sell: Double = 0.0)
    fun add(a: Agg, w: Double, r: Rarity, evo: Int, level: Int, gold: Long) {
        val dc = dropChance(r, evo)
        val lvl = level.coerceAtLeast(1)
        a.drops += w * dc
        a.xp += w * dc * Balance.sacrificeValue(lvl, r, evo)
        a.skill += w * dc * Balance.SKILL_UP_CHANCE[r.ordinal]
        a.sell += w * dc * Balance.sellPrice(r, evo, lvl)
        a.gold += w * (1 - dc) * gold
    }
    println("5b2: per replay  drops / feed XP / skill-up % (sum) / gold / sell value of drops")
    println("        now                                   | uniform below the top                  | pyramid (low rarities most common)")
    for (d in Dungeons.all) for (st in d.stages.filter { it.stage in stages }) {
        val now = Agg(); val uni = Agg(); val pyr = Agg()
        for (m in st.waves.flatten().filter { !it.type.boss }) {
            add(now, 1.0, m.rarity, m.evo, m.level, m.gold)
            val eff = Balance.effectiveLevel(m.level, m.rarity, m.evo)
            val top = Balance.ladderStep(m.rarity, m.evo)
            val wsum = (0..top).sumOf { top - it + 1.0 }
            for (k in 0..top) {
                val (r, e) = LADDER[k]
                val level = (eff - Balance.effectiveLevel(0, r, e)).toInt()
                add(uni, 1.0 / (top + 1), r, e, level, m.gold)
                add(pyr, (top - k + 1) / wsum, r, e, level, m.gold)
            }
        }
        fun f(a: Agg) = "%4.1f %7.0f %4.0f%% %7.0f %6.0f".format(a.drops, a.xp, 100 * a.skill, a.gold, a.sell)
        println("%-5s %s | %s | %s".format(st.key, f(now), f(uni), f(pyr)))
    }
}

fun main() {
    if (System.getenv("ECON_MIX") != null) return mixReport((System.getenv("ECON_STAGES") ?: "1,10,16,19").split(",").map { it.toInt() })
    val effs = (System.getenv("ECON_EFF") ?: "1.0,0.8").split(",").map { it.toDouble() }
    val sells = (System.getenv("ECON_SELL") ?: "1").split(",").map { it.toDouble() }
    val stages = (System.getenv("ECON_STAGES") ?: "1,10,16,19").split(",").map { it.toInt() }
    for (r in listOf(Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY)) {
        val c = copyCost(r)
        println("${r.label} copy: shop ${Balance.HERO_PRICE_BY_RARITY[r]} gold | green path %.0f gold + %.0f hero XP".format(c.gold, c.xp))
    }
    println()
    println("Replays needed for ONE more hero copy: shop (sell all drops) / green path (feed drops, sell just enough for the gold)")
    println("columns: " + sells.flatMap { sf -> effs.map { e -> "sell×$sf eff $e" } }.joinToString(" | "))
    for (d in Dungeons.all) for (st in d.stages.filter { it.stage in stages }) {
        val cells = mutableListOf<String>()
        for (sf in sells) for (e in effs) {
            val (g, x, s) = perRun(st, e, sf)
            val parts = listOf(Rarity.RARE, Rarity.EPIC).map { r ->
                val c = copyCost(r)
                val shop = Balance.HERO_PRICE_BY_RARITY[r]!! / (g + s)
                val green = maxOf((c.gold + c.xp * s / x) / (g + s), c.xp / x)
                "%s %5.1f/%5.1f".format(r.label.first(), shop, green)
            }
            cells += parts.joinToString(" ")
        }
        val (g, x, s) = perRun(st, effs.first(), sells.first())
        println("%-5s gold %6.0f xp %7.0f sell %6.0f |  %s".format(st.key, g, x, s, cells.joinToString(" | ")))
    }
}
