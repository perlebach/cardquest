package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.model.Balance

/**
 * Prints every stage: fee, gold for beating all monsters (and the expected gold, as dropped cards give none),
 * its ratio to the fee, monster counts and the rarities/levels that show up. `./gradlew stages`
 */
fun main() {
    val ratios = mutableListOf<Double>()
    for (d in Dungeons.all) {
        println("== ${d.id}. ${d.name} (unlocked by ${d.unlockedBy ?: "-"})")
        for (st in d.stages) {
            val all = st.waves.flatten()
            val expected = all.sumOf { it.gold * (if (it.type.boss) 1.0 else 1 - it.dropChance) }
            val ratio = expected / st.entryFee
            ratios += ratio
            val kinds = all.groupBy { "${it.rarity.label.first()}${"+".repeat(it.evo)}" }
                .entries.joinToString(" ") { (k, v) -> "$k×${v.size}" }
            val eff = all.filter { !it.type.boss }.map { Balance.effectiveLevel(it.level, it.rarity, it.evo) }
            val boss = all.firstOrNull { it.type.boss }?.let { "  boss ${it.rarity.label}${"+".repeat(it.evo)} L${it.level} ${it.type.title} (${it.gold}g)" } ?: ""
            println(
                "%-5s t%-2d fee %7d  gold %8d (exp %8.0f) = %4.1f× fee  waves %s  eff %.0f-%.0f  %s%s".format(
                    st.key, st.tier, st.entryFee, st.maxGold, expected, ratio, st.waves.map { it.size },
                    eff.min(), eff.max(), kinds, boss,
                )
            )
        }
    }
    println("expected gold / fee: min %.2f, max %.2f, avg %.2f".format(ratios.min(), ratios.max(), ratios.average()))
}
