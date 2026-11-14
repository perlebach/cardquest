package cz.erlebach.cardrpg.model

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Passive abilities of the hero cards, bought with diamonds (Contest tab). Every hero class has one; a card's
 * [OwnedCard.passiveLevel] says how strong it is (0 = none). They work in every fight (dungeons too), never count as a used
 * skill (the "no skills" star stays reachable) and some of them are random (dodge, crit, debuff resistance).
 * Unless said otherwise a bonus is "level 1 value × level" and works for normal attacks and skills alike.
 */
enum class Passive(val hero: CardType, val icon: String, val title: String) {
    IRON_WILL(CardType.WARRIOR, "🪨", "Iron Will"),
    HOLY_MIGHT(CardType.PALADIN, "☀️", "Holy Might"),
    RAGE(CardType.BERSERKER, "💢", "Rage"),
    EVASION(CardType.ROGUE, "💨", "Evasion"),
    KEEN_EYE(CardType.HUNTER, "🦅", "Keen Eye"),
    BLESSING(CardType.PRIEST, "🕊️", "Blessing"),
    ARCANE_POWER(CardType.MAGE, "📖", "Arcane Power"),
    BLOOD_PACT(CardType.WARLOCK, "🩸", "Blood Pact");

    fun describe(level: Int): String = when (this) {
        IRON_WILL -> "${pct(debuffResist(level))}% chance to resist stun, burning and poison; takes ${pct(damageReduction(level))}% less damage."
        HOLY_MIGHT -> "Heals ${pct(healBonus(level))}% more; deals ${pct(bossDamage(level))}% more damage to bosses."
        RAGE -> "The more HP it misses, the harder it hits: up to +${pct(rageBonus(level))}% damage when almost dead."
        EVASION -> "${pct(dodgeChance(level))}% chance to dodge an attack or skill (no damage)."
        KEEN_EYE -> "${pct(critChance(level))}% chance of a critical hit (double damage)."
        BLESSING -> "Heals ${pct(healBonus(level))}% more; +${pct(hpBonus(level))}% HP."
        ARCANE_POWER -> "+${pct(atkBonus(level))}% attack."
        BLOOD_PACT -> "Its skill damage also heals every other friend by ${pct(leechShare(level))}% of it."
    }

    // ---- the numbers (all 0 for other passives)

    /** [IRON_WILL]: chance to shrug off a stun, burning or poison. */
    fun debuffResist(level: Int) = if (this == IRON_WILL) (0.3 * level).coerceAtMost(1.0) else 0.0
    /** [IRON_WILL]: share of incoming damage (normal attacks and skills) taken away. */
    fun damageReduction(level: Int) = if (this == IRON_WILL) (0.05 * level).coerceAtMost(0.9) else 0.0
    /** [HOLY_MIGHT], [BLESSING]: extra healing done. */
    fun healBonus(level: Int) = when (this) { HOLY_MIGHT -> 0.2 * level; BLESSING -> 0.1 * level; else -> 0.0 }
    /** [HOLY_MIGHT]: extra damage dealt to boss cards. */
    fun bossDamage(level: Int) = if (this == HOLY_MIGHT) 0.2 * level else 0.0
    /** [RAGE]: extra damage at (almost) 0 HP; it grows with the missing share of HP (full HP = none). */
    fun rageBonus(level: Int) = if (this == RAGE) 0.25 * level else 0.0
    /** [EVASION]: chance to take no damage from a normal attack or skill. */
    fun dodgeChance(level: Int) = if (this == EVASION && level > 0) (0.1 * level).coerceAtMost(1.0) else 0.0
    /** [KEEN_EYE]: chance to deal double damage – 10 % per level. */
    fun critChance(level: Int) = if (this == KEEN_EYE) (0.1 * level).coerceAtMost(1.0) else 0.0
    /** [BLESSING]: extra max HP. */
    fun hpBonus(level: Int) = if (this == BLESSING) 0.1 * level else 0.0
    /** [ARCANE_POWER]: extra attack. */
    fun atkBonus(level: Int) = if (this == ARCANE_POWER) 0.1 * level else 0.0
    /** [BLOOD_PACT]: share of the skill damage that heals every other friend (the warlock itself heals by all of it). */
    fun leechShare(level: Int) = if (this == BLOOD_PACT) 0.1 * level else 0.0

    companion object {
        /** The passive of the hero [type] (null for monsters and food). */
        fun of(type: CardType): Passive? = entries.firstOrNull { it.hero == type }

        const val MAX_LEVEL = 10

        /**
         * Diamonds for raising a passive from [level] to [level] + 1: 100, 1000, 10 000, … (×10 per level), so the
         * high levels stay out of reach (level 10 Evasion would dodge everything).
         */
        fun cost(level: Int): Long = (100 * 10.0.pow(level)).roundToLong()

        private fun pct(v: Double) = (v * 100).roundToInt()
    }
}
