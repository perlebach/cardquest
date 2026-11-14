package cz.erlebach.cardrpg.content

import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.BossTrait
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.CardType.*
import cz.erlebach.cardrpg.model.Rarity
import cz.erlebach.cardrpg.model.Rarity.*
import cz.erlebach.cardrpg.model.Skill
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

data class MonsterSpec(
    val type: CardType,
    val level: Int,
    val rarity: Rarity,
    val evo: Int,
    val slots: List<Int>,
    /** Chance to drop its card (a boss drops a food card instead, see [Balance.FOOD_DROP_CHANCE]). */
    val dropChance: Double,
    /** Difficulty tier of the stage. */
    val tier: Int,
    /** Boss helpers ([BossTrait.SWARM], [BossTrait.COMMANDER]) are weaker or more fragile than regular monsters. */
    val hpFactor: Double = 1.0,
    val atkFactor: Double = 1.0,
    /**
     * Gold for beating it (only if it doesn't drop its card; bosses always pay): set by the stage, see [withStageGold].
     * Doesn't depend on the monster's level or rarity.
     */
    val gold: Long = 0,
) {
    /** Regular monsters have skill level 1 ([LEGENDARY_MONSTER_SKILL_LEVEL] if legendary), bosses the max level (with its special bonus). */
    val skillLevel: Int get() = if (type.boss) Balance.MAX_SKILL_LEVEL else if (rarity == LEGENDARY) LEGENDARY_MONSTER_SKILL_LEVEL else 1
}

/**
 * Splits the stage gold among its monsters: together they pay [Balance.STAGE_GOLD_FEES]× [entryFee] (equal shares),
 * a boss gets one more [entryFee] on top of its share.
 */
private fun withStageGold(waves: List<List<MonsterSpec>>, entryFee: Long): List<List<MonsterSpec>> {
    val share = Balance.STAGE_GOLD_FEES * entryFee / waves.sumOf { it.size }
    return waves.map { w ->
        w.map { m -> m.copy(gold = (share + if (m.type.boss) entryFee else 0).roundToLong().coerceAtLeast(1)) }
    }
}

/** Skill level of regular legendary monsters (dungeons 9-10). */
const val LEGENDARY_MONSTER_SKILL_LEVEL = 4

data class StageDef(
    val dungeon: Int,
    val stage: Int,
    val waves: List<List<MonsterSpec>>,
) {
    val key get() = "$dungeon-$stage"
    /** Overall difficulty: every dungeon starts 10 tiers above the previous one, so 2-1 ≈ 1-11 and 3-1 ≈ 2-11. */
    val tier get() = Dungeons.tier(dungeon, stage)
    /** 5 gold at tier 0, ×2 every 10 tiers (1-11 / 2-1: 10, 2-11 / 3-1: 20, …). */
    val entryFee: Long get() = (5 * 2.0.pow(tier / 10.0)).roundToLong()
    /** Gold if every monster is beaten and none of them drops its card (first win doubles it). */
    val maxGold: Long get() = waves.flatten().sumOf { it.gold }
    val playerXp: Long get() = Balance.playerXpReward(tier)
    val cardXp: Long get() = Balance.cardXpReward(tier)
    val hasBoss get() = waves.last().any { it.type.boss }
    val bossTrait get() = waves.last().firstOrNull { it.type.boss }?.type?.trait
}

/** Cards given by a star milestone. */
data class CardReward(val type: CardType, val rarity: Rarity, val evo: Int, val count: Int = 1, val level: Int = 1) {
    val label get() = "${if (count > 1) "$count× " else ""}${rarity.label}${"+".repeat(evo)} ${type.title}${if (level > 1) " (Lv $level)" else ""}"
}

/** Reward for collecting [stars] stars in one dungeon (given once, right after the fight that reaches it). */
data class Milestone(val stars: Int, val gold: Long = 0, val cards: List<CardReward> = emptyList()) {
    val label get() = (listOfNotNull(if (gold > 0) "🪙 $gold" else null) + cards.map { it.label }).joinToString(", ")
}

/** [unlockedBy] = stage key that has to be won to open this dungeon (null = open from the start). */
data class DungeonDef(
    val id: Int, val name: String, val emoji: String, val bg: Long, val unlockedBy: String?,
    /** Decorations along the corridor between waves (big outer ones and small inner ones). */
    val scenery: List<String>, val sceneryInner: List<String>,
    val stages: List<StageDef>,
    val milestones: List<Milestone>,
) {
    /** 3 stars per stage. */
    val maxStars get() = stages.size * 3
}

/**
 * The monsters of one dungeon, in the order they show up (the first few from stage 1, all of them by stage 11).
 * Front-row types are the sturdier ones; healers are limited to one per wave.
 */
private class Roster(val front: List<CardType>, val back: List<CardType>, val midBoss: CardType, val finalBoss: CardType)

/**
 * Mixed rarities: every regular monster of a stage first gets the stage's rarity (ladder step) as before, then a step picked evenly
 * from white up to that one – a "rare" dungeon is about ¼ rare, ¼ green+, ¼ green, ¼ white. It keeps the strength (effective level)
 * of the original, so a lower rarity means a higher level, often above the rarity's max (white L52); it pays the original's gold.
 * false = every monster has the stage's rarity (the old way).
 */
const val MIXED_RARITIES = true
/** Extra levels of a mixed-in monster per rarity below the original's (lower rarities have weaker skills). Tuned with the bot. */
private const val MIX_LEVELS_PER_RARITY = 0.5
/** Extra levels of a mixed-in white (common) monster: it has no skill at all. Tuned with the bot. */
private const val MIX_NO_SKILL_LEVELS = 2.0

/** white, green, green+, blue, blue+, blue++, purple, …, purple+++, legendary, … */
internal val LADDER = Rarity.entries.flatMap { r -> (0..r.maxEvo).map { r to it } }

private const val A1 = 0
private const val A2 = 1
private const val A3 = 2
private const val B1 = 3
private const val B2 = 4
private const val B3 = 5

/**
 * How dangerous a stage is, as "effective level of one monster + L(monsters per wave)", where L(n) = log₁.₁(n)
 * (n monsters are about as dangerous as one monster with n× the hp and attack, i.e. L(n) levels higher).
 * Piecewise linear by tier: X-10 monsters are about effective level 11, 22, 31, 39, 46, 52, 55, 58 in dungeons 1..8.
 * It flattens out because late cards grow slowly (every "+" needs more copies of the card). Tuned with the bot.
 */
private val THREAT = listOf(
    0 to 4.0, 9 to 22.7, 19 to 33.7, 29 to 42.7, 39 to 50.7, 49 to 57.7, 59 to 63.7, 69 to 66.7, 79 to 69.7, 89 to 72.7, 99 to 85.0, 109 to 93.0,
)

internal fun threat(tier: Int): Double {
    val i = THREAT.indexOfLast { it.first <= tier }.coerceIn(0, THREAT.size - 2)
    val (t0, v0) = THREAT[i]
    val (t1, v1) = THREAT[i + 1]
    return v0 + (v1 - v0) * (tier - t0).toDouble() / (t1 - t0)
}

internal fun levels(n: Double) = ln(n) / ln(1.1)

/** Average monsters per wave: 1.33 in stage 1 (1, 1, 2) up to 5 in stage 20 – the same in every dungeon. */
private fun perWave(s: Int) = 1.33 + 0.19 * (s - 1)

/** The boss wave is about this many times as dangerous as an average wave of its stage (bosses also hit harder with their skill). */
private const val BOSS_WAVE_THREAT = 1.0

/**
 * Bosses always fight with their skill at the max level (+60 % power and the max-level bonus), so they get this many
 * levels fewer than their wave's threat would give them. Fireball bosses burn the whole party every turn. Tuned with the bot.
 */
private val BOSS_SKILL_LEVELS = mapOf(
    Skill.FIREBALL to 7.0,
    Skill.WHIRLWIND to 3.0,
    Skill.SNEAK_ATTACK to 1.5,
    Skill.SOUL_LEECH to 1.5,
).withDefault { 1.0 }

/** Swarm helpers: 40 % hp, 50 % attack. Commander helpers (glass cannons): 50 % hp, 130 % attack. */
private const val SWARM_HP = 0.4
private const val SWARM_ATK = 0.5
private const val COMMANDER_HP = 0.5
private const val COMMANDER_ATK = 1.3

/** Levels added to (or taken from) a boss with a trait, on top of its threat. Tuned with the bot. */
private val TRAIT_LEVELS = mapOf(
    BossTrait.FIRESTORM to -2.0,
    BossTrait.CRUSHER to -3.0,
    BossTrait.COMMANDER to -6.0,
    BossTrait.AMBUSHER to -3.0,
    BossTrait.SWARM to -3.0,
    BossTrait.REGENERATION to -2.0,
    BossTrait.UNDYING to -3.0,
).withDefault { 0.0 }

private fun generateStage(d: Int, s: Int, roster: Roster): StageDef {
    val rnd = Random(d * 1000 + s)
    val tier = Dungeons.tier(d, s)
    // the higher the stage number, the more monsters; the higher the dungeon, the stronger they are
    val eff = threat(tier) - levels(perWave(s))
    val band = when { s <= 5 -> 0; s <= 15 -> 1; else -> 2 }
    val topStep = d - 1 + band
    val bossStage = s % 5 == 0
    val sizes = (0..2).map { w -> (perWave(s) - 0.33 + 0.33 * w).roundToInt().coerceIn(1, 6) }
    val bossType = if (!bossStage) null else if (s == 20) roster.finalBoss else roster.midBoss
    val trait = bossType?.trait
    val bossSlots = when {
        trait == BossTrait.SWARM -> listOf(B2) // a small boss in the back, helpers on every other spot
        trait == BossTrait.COMMANDER -> listOf(A1, A2, A3) // the whole front row, glass cannons behind it
        s == 5 -> listOf(A2, A3)
        s == 10 -> listOf(A1, A2, A3)
        else -> listOf(A2, A3, B2, B3)
    }
    // regular monsters per wave (in the boss wave the boss takes the place of one of them, plus its spots)
    val counts = sizes.mapIndexed { w, n ->
        if (w != 2 || !bossStage) n
        else if (trait == BossTrait.SWARM) 6 - bossSlots.size
        else if (trait == BossTrait.COMMANDER) 2 // two helpers in the back row
        else (n - 1).coerceIn(1, 6 - bossSlots.size)
    }
    // how many average monsters the helpers of the boss wave are worth (n monsters ≈ one with n× hp and attack)
    val helperFactor = when (trait) {
        BossTrait.SWARM -> kotlin.math.sqrt(SWARM_HP * SWARM_ATK)
        BossTrait.COMMANDER -> kotlin.math.sqrt(COMMANDER_HP * COMMANDER_ATK)
        else -> 1.0
    }

    // which monsters show the new (top) rarity: a few at the start of a band, nearly all at its end
    val regularCount = counts.sum()
    val topShare = when {
        topStep == 0 -> 1.0
        band == 0 -> 0.4 + 0.6 * (s - 1) / 4
        band == 1 -> 0.15 + 0.85 * (s - 6) / 9
        else -> 0.4 + 0.6 * (s - 16) / 3
    }
    val topCount = (topShare * regularCount).roundToInt().coerceIn(1, regularCount)
    val isTop = List(regularCount) { it < topCount }.shuffled(rnd).toMutableList()

    // monster types available so far
    fun intro(list: List<CardType>): List<CardType> {
        val first = if (d == 1) 2 else 3
        val k = first + ((list.size - first) * (s - 1) / 10.0).roundToInt()
        return list.take(k.coerceIn(1, list.size))
    }
    val front = intro(roster.front)
    val back = intro(roster.back)

    fun monster(type: CardType, slot: Int, top: Boolean, hpF: Double = 1.0, atkF: Double = 1.0): MonsterSpec {
        var step = if (top) topStep else topStep - 1
        val e = eff + rnd.nextInt(-1, 2)
        fun levelAt(step: Int) = (e - LADDER[step].let { Balance.effectiveLevel(0, it.first, it.second) }).roundToInt()
        // too high a level for its rarity: one more "+" instead (same strength)
        while (levelAt(step) > LADDER[step].first.maxLevel && step + 1 < LADDER.size && LADDER[step + 1].first == LADDER[step].first) step++
        var (rarity, evo) = LADDER[step]
        var level = levelAt(step).coerceAtLeast(1)
        if (MIXED_RARITIES && step > 0) {
            val k = rnd.nextInt(0, step + 1)
            if (k < step) {
                val (r, ev) = LADDER[k]
                val extra = MIX_LEVELS_PER_RARITY * (rarity.ordinal - r.ordinal) + if (r == COMMON) MIX_NO_SKILL_LEVELS else 0.0
                level = (e + extra - Balance.effectiveLevel(0, r, ev)).roundToInt().coerceAtLeast(1)
                rarity = r; evo = ev
            }
        }
        val drop = when (rarity) { COMMON -> 0.25; UNCOMMON -> 0.2; RARE -> 0.15; EPIC -> 0.1; LEGENDARY -> 0.05 } * 0.75.pow(evo)
        return MonsterSpec(type, level, rarity, evo, listOf(slot), drop, tier, hpF, atkF)
    }

    fun wave(count: Int, boss: MonsterSpec?): List<MonsterSpec> {
        val free = (0..5).filter { boss == null || it !in boss.slots }
        val list = mutableListOf<MonsterSpec>()
        boss?.let { list += it }
        val frontFree = free.filter { it < 3 }.shuffled(rnd).toMutableList()
        val backFree = free.filter { it >= 3 }.shuffled(rnd).toMutableList()
        var nFront = if (boss != null) minOf(frontFree.size, count / 2) else ((count + 1) / 2).coerceAtMost(3)
        if (count - nFront > backFree.size) nFront = count - backFree.size
        var healer = false
        repeat(count) { i ->
            val inFront = i < nFront
            val pool = (if (inFront) front else back).filter { !healer || !it.heals }.ifEmpty { if (inFront) front else back }
            val type = pool.random(rnd)
            if (type.heals) healer = true
            val slot = if (inFront) frontFree.removeAt(0) else backFree.removeAt(0)
            val (hpF, atkF) = when (boss?.type?.trait) {
                BossTrait.SWARM -> SWARM_HP to SWARM_ATK
                BossTrait.COMMANDER -> COMMANDER_HP to COMMANDER_ATK
                else -> 1.0 to 1.0
            }
            list += monster(type, slot, isTop.removeAt(0), hpF, atkF)
        }
        return list
    }

    val waves = (0..2).map { w ->
        if (w == 2 && bossStage) {
            val type = bossType!!
            // the final boss shows the next rarity step (1-20: the only blue monster of the woods)
            var step = if (s == 20) topStep + 1 else topStep
            // a boss on k spots has 1.6·k× the hp, so it counts as √(1.6·k) monsters; its level makes
            // boss + companions [BOSS_WAVE_THREAT]× as dangerous as an average wave
            val asMonsters = kotlin.math.sqrt(CardType.bossHpFactor(bossSlots.size))
            val bossLevels = levels(((BOSS_WAVE_THREAT * perWave(s) - counts[w] * helperFactor) / asMonsters).coerceAtLeast(0.3)) -
                BOSS_SKILL_LEVELS.getValue(type.skill) + (trait?.let { TRAIT_LEVELS.getValue(it) } ?: 0.0)
            fun levelAt(step: Int) = (eff + bossLevels - LADDER[step].let { Balance.effectiveLevel(0, it.first, it.second) }).roundToInt()
            // too high a level for its rarity: one more "+" instead (same strength)
            if (levelAt(step) > LADDER[step].first.maxLevel && LADDER[step + 1].first == LADDER[step].first) step++
            val (rarity, evo) = LADDER[step]
            val level = levelAt(step).coerceAtLeast(1)
            wave(counts[w], MonsterSpec(type, level, rarity, evo, bossSlots, Balance.FOOD_DROP_CHANCE, tier))
        } else wave(counts[w], null)
    }
    return StageDef(d, s, withStageGold(waves, StageDef(d, s, waves).entryFee))
}

internal val CardType.heals get() = skill == Skill.HEAL || skill == Skill.FLASH_HEAL

private fun dungeon(
    id: Int, name: String, emoji: String, bg: Long, scenery: List<String>, inner: List<String>, roster: Roster,
) = DungeonDef(
    id, name, emoji, bg, if (id == 1) null else "${id - 1}-10", scenery, inner,
    (1..20).map { generateStage(id, it, roster) },
    milestones(id, roster),
)

/**
 * Star rewards of dungeon d: 20★ = 1000 × 2^(d−1) gold, 40★ = 5 food cards of ladder step d−1 (white, green, green+, …),
 * 60★ = the dungeon's first front-row monster at ladder step d+2 (rare Snail, rare+ Skeleton, rare++ Rhino, …), at its max level.
 */
private fun milestones(d: Int, roster: Roster): List<Milestone> {
    val (foodRarity, foodEvo) = LADDER[d - 1]
    val (monRarity, monEvo) = LADDER[(d + 2).coerceAtMost(LADDER.size - 1)]
    return listOf(
        Milestone(20, gold = 1000L * (1L shl (d - 1))),
        Milestone(40, cards = listOf(CardReward(CardType.FOOD, foodRarity, foodEvo, 5))),
        // from dungeon 8 on the monster would be legendary: gold for the legendary heroes instead
        if (d >= 8) Milestone(60, gold = 1_000_000L shl (d - 8))
        else Milestone(60, cards = listOf(CardReward(roster.front.first(), monRarity, monEvo, level = monRarity.maxLevel))),
    )
}

object Dungeons {
    /** Every dungeon starts 10 tiers above the previous one. */
    fun tier(dungeon: Int, stage: Int) = 10 * (dungeon - 1) + (stage - 1)

    val all = listOf(
        dungeon(
            1, "Whispering Woods", "🌲", 0xFF2E5E3A, listOf("🌲", "🌳", "🌲", "🍄", "🌳"), listOf("🌼", "🌿", "🍀", "🌷"),
            Roster(
                front = listOf(SNAIL, FOX, WOLF, BEAR, BOAR, HEDGEHOG),
                back = listOf(BAT, FROG, MUSHROOM, OWL, RACCOON, DEER, UNICORN),
                midBoss = OAK, finalBoss = GIANT_SPIDER,
            ),
        ),
        dungeon(
            2, "Crystal Caves", "💎", 0xFF2A3A6A, listOf("💎", "🪨", "💎", "🪨", "💎"), listOf("🕯️", "✨", "🪨", "✨"),
            Roster(
                front = listOf(SKELETON, CRAB, TROLL, TURTLE, LION, GOLEM, ZOMBIE, SCORPION, VAMPIRE),
                back = listOf(IMP, GHOST, FAIRY, OCTOPUS, MERMAID),
                midBoss = DRAGON, finalBoss = KRAKEN,
            ),
        ),
        dungeon(
            3, "Fire Mountain", "🌋", 0xFF6A2A1A, listOf("🌋", "🪨", "🔥", "🪨", "🌋"), listOf("🔥", "✨", "🪨", "🔥"),
            Roster(
                front = listOf(RHINO, OGRE, SNAKE, MAMMOTH, HELLHOUND),
                back = listOf(SALAMANDER, EAGLE, GENIE, PHOENIX_CHICK),
                midBoss = LAVA_REX, finalBoss = LAVA_DRAGON,
            ),
        ),
        dungeon(
            4, "Frozen Peaks", "🏔️", 0xFF3A5A7A, listOf("🏔️", "🌲", "⛄", "🏔️", "🌲"), listOf("❄️", "🧊", "❄️", "✨"),
            Roster(
                front = listOf(SEAL, MOUNTAIN_GOAT, RAM, SNOW_TIGER, ICE_GOLEM, SNOWMAN, WOLF),
                back = listOf(PENGUIN, FROST_SPRITE, SWAN, SNOW_DOVE, OWL),
                midBoss = YETI, finalBoss = FROST_GIANT,
            ),
        ),
        dungeon(
            5, "Scorching Desert", "🏜️", 0xFF8A6A2A, listOf("🌵", "🏜️", "🌴", "🌵", "🪨"), listOf("🦴", "✨", "🪨", "🌾"),
            Roster(
                front = listOf(CAMEL, CACTUS, LEOPARD, GIANT_ANT, SCARAB, SCORPION),
                back = listOf(WASP, SUN_SPRITE, PEACOCK, DESERT_ROSE, SNAKE),
                midBoss = SAND_CROC, finalBoss = DESERT_WYRM,
            ),
        ),
        dungeon(
            6, "Sunken Reef", "🌊", 0xFF1A4A6A, listOf("🪸", "🌊", "🐚", "🪸", "🌊"), listOf("🫧", "✨", "🐚", "🫧"),
            Roster(
                front = listOf(SHARK, LOBSTER, HERMIT_CRAB, ANGLERFISH, CRAB, TURTLE),
                back = listOf(SHRIMP, CLOWNFISH, WATER_SPRITE, DOLPHIN, OTTER, MERMAID),
                midBoss = PUFFERFISH, finalBoss = LEVIATHAN,
            ),
        ),
        dungeon(
            7, "Shadow Realm", "🌑", 0xFF2A1A3A, listOf("🪦", "🕸️", "🏚️", "🪦", "🕸️"), listOf("🕯️", "💀", "✨", "🦴"),
            Roster(
                front = listOf(PUMPKIN_HEAD, SHADOW_CAT, GOBLIN, GRAVESTONE, PLAGUE_RAT, ZOMBIE, VAMPIRE),
                back = listOf(WATCHER, WISP, DARK_ELF, VOID_SPAWN, GHOST),
                midBoss = LICH, finalBoss = VOID_LORD,
            ),
        ),
        dungeon(
            8, "Sky Citadel", "🏰", 0xFF4A5A8A, listOf("☁️", "🏰", "☁️", "🌈", "☁️"), listOf("✨", "⭐", "✨", "🌟"),
            Roster(
                front = listOf(CLOCKWORK_KNIGHT, SKY_KNIGHT, PEGASUS, STORM_CLOUD, UNICORN),
                back = listOf(STORM_SPRITE, COMET, THUNDERBIRD, CHERUB, STAR_SPIRIT, EAGLE),
                midBoss = SKY_WHALE, finalBoss = ELDER_DRAGON,
            ),
        ),
        dungeon(
            9, "Candy Kingdom", "🍭", 0xFF8A3A6A, listOf("🍭", "🍬", "🧁", "🍭", "🍬"), listOf("✨", "🍬", "🌈", "✨"),
            Roster(
                front = listOf(GINGERBREAD, GUMMY_BEAR, CANDY_KNIGHT, CHOCO_GOLEM, PUDDING),
                back = listOf(DONUT, LOLLIPOP, CUPCAKE, STRAWBERRY, ICE_CREAM),
                midBoss = HONEY_MONSTER, finalBoss = SUGAR_KING,
            ),
        ),
        dungeon(
            10, "Cosmic Rift", "🌌", 0xFF1A1036, listOf("🪐", "🌌", "⭐", "🌙", "🪐"), listOf("✨", "⭐", "✨", "💫"),
            Roster(
                front = listOf(PLANET_TITAN, ROCKET, MOON_BEAST, SATELLITE, METEOR),
                back = listOf(UFO, NEBULA_SPRITE, MOON_RABBIT, CRESCENT_SPIRIT),
                midBoss = BLACK_HOLE, finalBoss = SUN_TITAN,
            ),
        ),
    )

    fun stage(key: String): StageDef? = all.flatMap { it.stages }.firstOrNull { it.key == key }
}
