package cz.erlebach.cardrpg.content

import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.Skill
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/** What a monster does in a contest formation (decides where it may stand). */
enum class Role(val label: String) {
    /** Shield Up monsters: the wall in the front row. */
    TANK("tank"),
    /** Sturdy Whirlwind / Soul Leech monsters: off-tanks, front row. */
    BRUISER("off-tank"),
    /** Sneak Attack monsters: melee damage dealers, front row. */
    MELEE("melee"),
    /** Aimed Shot / Fireball monsters: damage dealers from the back row. */
    RANGED("ranged"),
    /** Heal / Flash Heal monsters: back row. */
    HEALER("healer");

    companion object {
        fun of(t: CardType): Role = when (t.skill) {
            Skill.SHIELD_UP -> TANK
            Skill.HEAL, Skill.FLASH_HEAL -> HEALER
            Skill.AIMED_SHOT, Skill.FIREBALL -> RANGED
            Skill.SNEAK_ATTACK -> MELEE
            Skill.WHIRLWIND, Skill.SOUL_LEECH -> if (t.baseHp >= 34) BRUISER else MELEE
        }
    }
}

/**
 * The Contest (opens at player level [UNLOCK_LEVEL]): an endless fight for diamonds. Each entry costs a ticket (3 at the
 * start, then 1 every day). Wave 1 is about as hard as stage 7-1 ([START_TIER]); every next wave is [TIERS_PER_WAVE]
 * tiers harder (the screens don't say how hard – that's for the player to find out). The monsters are random, but they
 * stand in a sensible [formations] (tanks in front, healers behind).
 * Beaten monsters give no gold and no cards, only diamonds ([diamonds]); the party keeps them when it falls.
 */
object Contest {
    const val UNLOCK_LEVEL = 50
    const val START_TICKETS = 3
    const val TICKETS_PER_DAY = 1
    /** Tier of wave 1 (= stage 7-1). */
    const val START_TIER = 60
    const val TIERS_PER_WAVE = 3

    /** Diamonds per monster: one for every [HP_PER_DIAMOND] HP plus one for every [ATK_PER_DIAMOND] attack. Tuned with ContestReport. */
    const val HP_PER_DIAMOND = 1500.0
    const val ATK_PER_DIAMOND = 300.0

    // ---- shop (fallback uses of diamonds)
    const val GOLD_PACK_PRICE = 100L
    const val GOLD_PACK_GOLD = 10_000L
    const val LEGENDARY_CAKE_PRICE = 200L

    /** Cheering crowd along the arena (big outer and small inner decorations). */
    val crowd = listOf("🙌", "🥳", "📣", "👏", "🤩")
    val crowdInner = listOf("🎉", "✨", "🎈", "🎊")

    private const val A1 = 0
    private const val A2 = 1
    private const val A3 = 2
    private const val B1 = 3
    private const val B2 = 4
    private const val B3 = 5

    /** Positions of the monsters: spot -> role. Each wave picks one at random. */
    val formations: List<Map<Int, Role>> = listOf(
        mapOf(A2 to Role.TANK, B1 to Role.RANGED, B2 to Role.RANGED, B3 to Role.RANGED),
        mapOf(A1 to Role.TANK, A3 to Role.BRUISER, B1 to Role.HEALER, B2 to Role.RANGED, B3 to Role.HEALER),
        mapOf(A1 to Role.BRUISER, A2 to Role.TANK, A3 to Role.BRUISER, B2 to Role.HEALER),
        mapOf(A1 to Role.MELEE, A2 to Role.TANK, A3 to Role.MELEE, B1 to Role.RANGED, B3 to Role.RANGED),
        mapOf(A1 to Role.TANK, A2 to Role.MELEE, A3 to Role.TANK, B1 to Role.RANGED, B2 to Role.HEALER, B3 to Role.RANGED),
        mapOf(A1 to Role.MELEE, A2 to Role.BRUISER, A3 to Role.MELEE, B2 to Role.RANGED),
        mapOf(A1 to Role.TANK, A2 to Role.TANK, A3 to Role.TANK, B1 to Role.RANGED, B3 to Role.RANGED),
        mapOf(A1 to Role.BRUISER, A2 to Role.MELEE, B1 to Role.RANGED, B2 to Role.RANGED, B3 to Role.HEALER),
    )

    /** Regular (non-boss) monsters by role. */
    val pool: Map<Role, List<CardType>> =
        CardType.entries.filter { !it.hero && !it.boss && !it.food }.groupBy { Role.of(it) }

    /** Difficulty tier of wave [wave] (0-based). */
    fun tier(wave: Int) = START_TIER + TIERS_PER_WAVE * wave

    /** The stage a wave is about as hard as ("7-1", "7-4", …; past the last dungeon it keeps counting). For ContestReport only. */
    fun stageLabel(wave: Int): String = tier(wave).let { "${it / 10 + 1}-${it % 10 + 1}" }

    /**
     * The monsters of wave [wave]: a random formation filled with random monsters of the right roles. They are as strong as
     * a dungeon monster of that tier in a wave of the same size (effective level = threat − L(count), ±1), at the rarity
     * of that tier (one "+" more whenever the level would be above the rarity's max).
     */
    fun wave(wave: Int, random: Random): List<MonsterSpec> {
        val tier = tier(wave)
        val formation = formations.random(random)
        val eff = threat(tier) - levels(formation.size.toDouble())
        return formation.entries.sortedBy { it.key }.map { (slot, role) ->
            val type = pool.getValue(role).random(random)
            val e = eff + random.nextInt(-1, 2)
            var step = (tier / 10).coerceAtMost(LADDER.size - 1)
            fun levelAt(step: Int) = (e - LADDER[step].let { Balance.effectiveLevel(0, it.first, it.second) }).roundToInt()
            while (levelAt(step) > LADDER[step].first.maxLevel && step + 1 < LADDER.size) step++
            val (rarity, evo) = LADDER[step]
            MonsterSpec(type, levelAt(step).coerceAtLeast(1), rarity, evo, listOf(slot), dropChance = 0.0, tier = tier)
        }
    }

    /** Diamonds for beating a monster with [hp] and [atk] (at least 1). */
    fun diamonds(hp: Int, atk: Int): Long = (hp / HP_PER_DIAMOND + atk / ATK_PER_DIAMOND).roundToLong().coerceAtLeast(1)
}
