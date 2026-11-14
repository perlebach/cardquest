package cz.erlebach.cardrpg.battle

import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.BossTrait
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity
import cz.erlebach.cardrpg.model.Skill
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Side { PLAYER, ENEMY }

/** Grid spots: 0..2 = front row A1..A3, 3..5 = back row B1..B3. */
object Grid {
    val names = listOf("A1", "A2", "A3", "B1", "B2", "B3")

    /** Target priority depending on the attacker's column. */
    private val orders = listOf(
        listOf(0, 1, 2, 3, 4, 5),
        listOf(1, 0, 2, 4, 3, 5),
        listOf(2, 1, 0, 5, 4, 3),
    )

    fun targetOrder(column: Int) = orders[column]
}

/** A card taking part in a battle. */
class Fighter(
    val uid: Int,
    val side: Side,
    val type: CardType,
    val rarity: Rarity,
    val evo: Int,
    val level: Int,
    val maxHp: Int,
    val atk: Int,
    /** null for common cards (no skill). */
    val skill: Skill?,
    val skillPower: Int,
    /** Grid spots this fighter occupies (bosses can take more than one). */
    val slots: List<Int>,
    /** Id of the owned card (player side only). */
    val cardId: Long? = null,
    val dropChance: Double = 0.0,
    /** Gold for beating it (enemies only), unless it drops its card. */
    val gold: Long = 0,
    /** Skill level; at [Balance.MAX_SKILL_LEVEL] the skill gets its special bonus (see [Skill.maxBonus]). */
    val skillLevel: Int = 1,
    /** Rounds to wait after using the skill (player heroes can shorten it by meditating). */
    val skillCooldown: Int = Balance.SKILL_COOLDOWN,
    /** Passive ability level of a player hero (0 = none, see [Passive]). */
    val passiveLevel: Int = 0,
    /** Diamonds for beating it (contest monsters only). */
    val diamonds: Long = 0,
) {
    /** The passive ability working in this fight (player heroes that bought one). */
    val passive: Passive? = if (side == Side.PLAYER && passiveLevel > 0) Passive.of(type) else null
    var hp = maxHp
    var cooldown = 0
    var guard = 0
    /** Shield Up at max skill level: the guard also covers every friend in this card's row(s). */
    var rowGuard = false
    var stunned = false
    /** Damage taken at the start of each of its turns (from a max-level Fireball; doesn't stack). */
    var burning = 0
    /** Damage taken at the start of each of its turns (from a max-level Sneak Attack; doesn't stack). */
    var poison = 0
    /** Boss mechanic (enemies only). */
    val trait: BossTrait? get() = if (side == Side.ENEMY) type.trait else null
    /** [BossTrait.UNDYING]: times it gets back up with full HP (a Soul Leech hit takes them all away). */
    var spareLives = if (side == Side.ENEMY && type.trait == BossTrait.UNDYING) UNDYING_LIVES else 0
    /** The counter has done its job: a crusher's armor is cracked, a regenerating boss is deeply wounded, an undying one drained. */
    var traitBroken = false
    val alive get() = hp > 0
    val anchor get() = slots.min()
    val column get() = anchor % 3
    /** Rows it stands in (0 = front, 1 = back; a big boss can stand in both). */
    val rows get() = slots.map { it / 3 }.toSet()
    val name get() = type.title
    val maxSkill get() = skill != null && skillLevel >= Balance.MAX_SKILL_LEVEL
    /** Has any debuff (stun, burning, poison – add new debuffs here and to [cleanse]). */
    val debuffed get() = stunned || burning > 0 || poison > 0

    /** Removes every debuff (max-level Heal). */
    fun cleanse() {
        stunned = false
        burning = 0
        poison = 0
    }
}

/** Damage over time put on a card by a max-level skill. */
enum class Affliction(val icon: String, val label: String) { BURNING("🔥", "Burning"), POISON("🧪", "Poisoned") }

sealed interface BattleEvent {
    data class WaveStart(val wave: Int) : BattleEvent
    data class TurnStart(val uid: Int) : BattleEvent
    data class SkipTurn(val uid: Int) : BattleEvent
    data class Attack(val attacker: Int, val targets: List<Int>, val skill: Skill?) : BattleEvent
    /**
     * [dot] = the damage comes from burning/poison at the start of the card's turn; [blocked] = a raised shield cut it;
     * [shieldedBy] = a boss trait cut it (the boss's own, or the one of the boss its helper serves).
     */
    data class Damage(
        val target: Int, val amount: Int, val hpAfter: Int, val blocked: Boolean,
        val dot: Affliction? = null, val shieldedBy: BossTrait? = null,
        /** [Passive.KEEN_EYE] doubled it. */
        val crit: Boolean = false,
    ) : BattleEvent
    /** [Passive.EVASION]: [uid] dodged a hit (no damage). */
    data class Dodged(val uid: Int) : BattleEvent
    /** [Passive.IRON_WILL]: [uid] shrugged off a stun / burning / poison (null = stun). */
    data class Resisted(val uid: Int, val affliction: Affliction?) : BattleEvent
    /** A beaten contest monster gave [amount] diamonds (kept however the contest ends). */
    data class Diamonds(val uid: Int, val amount: Long) : BattleEvent
    /** [uid] starts burning / is poisoned (it takes damage at the start of each of its turns). */
    data class Afflicted(val uid: Int, val affliction: Affliction) : BattleEvent
    /** All debuffs of [uid] were removed (max-level Heal). */
    data class Cleansed(val uid: Int) : BattleEvent
    data class Heal(val source: Int, val target: Int, val amount: Int, val hpAfter: Int) : BattleEvent
    data class GuardUp(val uid: Int, val percent: Int) : BattleEvent
    data class Stun(val uid: Int) : BattleEvent
    data class Defeated(val uid: Int) : BattleEvent
    /** An undying boss got back up with full HP. */
    data class Revived(val uid: Int, val hpAfter: Int) : BattleEvent
    /**
     * The boss's trait stopped working: the counter skill broke it (spare lives drained, armor cracked, regeneration
     * stopped, the team made fire-proof…) or a swarm's / commander's last helper fell.
     */
    data class TraitBroken(val uid: Int) : BattleEvent
    /** Player card [uid] used the counter skill of [boss]'s trait, but the skill didn't reach the boss. */
    data class CounterMissed(val uid: Int, val boss: Int) : BattleEvent
    /** Heal put out the fire on [uid] (any level). */
    data class Extinguished(val uid: Int) : BattleEvent
    /** The defeated monster [uid] dropped its card (it joins the player if the fight is won). */
    data class Looted(val uid: Int) : BattleEvent
    /** The defeated monster [uid] didn't drop its card, so it gave [amount] gold (kept if the fight is won). */
    data class Coins(val uid: Int, val amount: Long) : BattleEvent
    data class WaveCleared(val wave: Int) : BattleEvent
    data object Victory : BattleEvent
    data object Defeat : BattleEvent
}

/** How player cards use their skills: always when ready, ask the player, or never. */
enum class SkillMode { AUTO, ASK, NEVER }

enum class Phase { NOT_STARTED, RUNNING, AWAITING_DECISION, WON, LOST }

/**
 * Deterministic battle. The only choice is whether a player's card uses its skill.
 * Call [advance] repeatedly; when [phase] is AWAITING_DECISION call [decide].
 */
/** [BossTrait.REGENERATION]: share of max HP healed at the start of each of its turns (until a Sneak Attack wounds it). */
const val REGENERATION_SHARE = 0.25
/** [BossTrait.CRUSHER] / [BossTrait.AMBUSHER]: damage multiplier of their normal attacks. */
const val CRUSHER_FACTOR = 3
const val AMBUSHER_FACTOR = 2
/** [BossTrait.UNDYING]: spare lives (a Soul Leech hit drains them all). */
const val UNDYING_LIVES = 3
/** [BossTrait.FIRESTORM]: burning per turn as a share of its Fireball's damage (normal max-level Fireball: [Balance.BURN_SHARE]). */
const val FIRESTORM_BURN_SHARE = 1.0
/**
 * Share of the damage that gets through to a protected boss or helper: [BossTrait.STONE_SKIN] (until a Whirlwind),
 * [BossTrait.CRUSHER] (until it hits a shield), [BossTrait.AMBUSHER] (until a Flash Heal), [BossTrait.SWARM] /
 * [BossTrait.COMMANDER] (while a helper lives); swarm helpers from single-target hits, commander helpers from area hits.
 */
const val TRAIT_DAMAGE_TAKEN = 0.1
/** [BossTrait.COMMANDER]: share of the damage that gets through while one of its helpers lives. */
const val COMMANDER_DAMAGE_TAKEN = 0.25
/** Traits whose counter skill breaks them with a single hit (so a use that leaves the trait intact missed the boss). */
private val INSTANT_COUNTERS = setOf(BossTrait.STONE_SKIN, BossTrait.REGENERATION, BossTrait.UNDYING)

/** The boss's trait still works (for the badge on its card); an undying boss loses it with its last spare life. */
val Fighter.traitActive: Boolean
    get() = trait != null && !traitBroken && (trait != BossTrait.UNDYING || spareLives > 0)

class Battle(
    val party: List<Fighter>,
    /** Creates the monsters of wave n (0-based). */
    private val waveAt: (Int) -> List<Fighter>,
    /** Number of waves; null = endless (the contest: new waves keep coming until the party falls). */
    val waveCount: Int?,
    private val random: Random = Random.Default,
) {
    /** A dungeon fight: a fixed list of waves. */
    constructor(party: List<Fighter>, waveFactory: List<() -> List<Fighter>>, random: Random = Random.Default) :
        this(party, { waveFactory[it]() }, waveFactory.size, random)

    val endless get() = waveCount == null
    var wave = -1; private set
    var round = 1; private set
    var enemies: List<Fighter> = emptyList(); private set
    var phase = Phase.NOT_STARTED; private set
    var skillMode = SkillMode.AUTO
    /** Fighter waiting for the use-skill decision. */
    var pending: Fighter? = null; private set
    /** Defeated monsters that dropped their card (rolled when they are beaten). */
    val loot = mutableListOf<Fighter>()
    /** Gold from the defeated monsters that didn't drop their card (bosses always pay). */
    var gold = 0L; private set
    /** Diamonds from the beaten contest monsters (kept even when the party falls). */
    var diamonds = 0L; private set
    /** Waves fully beaten. */
    var wavesCleared = 0; private set
    /** A Heal made the party fire-proof (no more burning for the rest of the fight). */
    var fireproof = false; private set
    /** True once any player card has used its skill (the "no skills" star is lost). */
    var playerUsedSkill = false; private set
    /** True if no player card was knocked out (for the "no card lost" star). */
    val noCardLost get() = party.all { it.alive }

    private var pointer = 0 // 0..11 within a round: (slot, side) pairs
    val finished get() = phase == Phase.WON || phase == Phase.LOST

    fun fighter(uid: Int): Fighter? = party.firstOrNull { it.uid == uid } ?: enemies.firstOrNull { it.uid == uid }

    /** Runs until the next turn has been resolved (or a decision is needed). */
    fun advance(): List<BattleEvent> {
        val ev = mutableListOf<BattleEvent>()
        when (phase) {
            Phase.NOT_STARTED -> { startWave(0, ev); phase = Phase.RUNNING }
            Phase.RUNNING -> runNextTurn(ev)
            else -> {}
        }
        return ev
    }

    fun decide(useSkill: Boolean): List<BattleEvent> {
        val f = pending ?: return emptyList()
        check(phase == Phase.AWAITING_DECISION)
        pending = null
        phase = Phase.RUNNING
        val ev = mutableListOf<BattleEvent>()
        if (useSkill) useSkill(f, ev) else normalAttack(f, ev)
        afterTurn(ev)
        return ev
    }

    /** Plays the whole battle in auto mode (used for simulation and tests). */
    fun runToEnd(): Phase {
        skillMode = SkillMode.AUTO
        var guard = 0
        while (!finished) {
            if (phase == Phase.AWAITING_DECISION) decide(true) else advance()
            if (++guard > 100_000) error("battle did not finish")
        }
        return phase
    }

    private fun startWave(index: Int, ev: MutableList<BattleEvent>) {
        wave = index
        enemies = waveAt(index)
        round = 1
        pointer = 0
        party.forEach {
            if (Balance.RESET_COOLDOWN_EACH_WAVE) it.cooldown = 0
            it.guard = 0
            it.rowGuard = false
            it.stunned = false
            it.burning = 0
            it.poison = 0
        }
        ev += BattleEvent.WaveStart(index)
    }

    private fun sideList(side: Side) = if (side == Side.PLAYER) party else enemies
    private fun opponents(f: Fighter) = if (f.side == Side.PLAYER) enemies else party
    private fun allies(f: Fighter) = sideList(f.side)

    private fun runNextTurn(ev: MutableList<BattleEvent>) {
        while (true) {
            if (pointer >= 12) {
                pointer = 0
                round++
                if (round > Balance.MAX_ROUNDS_PER_WAVE) { phase = Phase.LOST; ev += BattleEvent.Defeat; return }
            }
            val slot = pointer / 2
            val side = if (pointer % 2 == 0) Side.PLAYER else Side.ENEMY
            pointer++
            val actor = sideList(side).firstOrNull { it.alive && it.anchor == slot } ?: continue
            takeTurn(actor, ev)
            return
        }
    }

    private fun takeTurn(f: Fighter, ev: MutableList<BattleEvent>) {
        ev += BattleEvent.TurnStart(f.uid)
        f.guard = 0
        f.rowGuard = false
        // burning / poison hurt right before the card would act (it can die from it)
        for ((a, amount) in listOf(Affliction.BURNING to f.burning, Affliction.POISON to f.poison)) {
            if (amount > 0 && f.alive) hit(f, amount, ev, dot = a)
        }
        if (!f.alive) { afterTurn(ev); return }
        if (f.trait == BossTrait.REGENERATION && !f.traitBroken) heal(f, f, max(1, (f.maxHp * REGENERATION_SHARE).roundToInt()), ev)
        val cooling = f.cooldown > 0
        if (cooling) f.cooldown--
        if (f.stunned) {
            f.stunned = false
            ev += BattleEvent.SkipTurn(f.uid)
            afterTurn(ev)
            return
        }
        val canUse = !cooling && skillHasTarget(f)
        if (canUse && f.side == Side.PLAYER && skillMode == SkillMode.ASK) {
            pending = f
            phase = Phase.AWAITING_DECISION
            return
        }
        if (canUse && (f.side == Side.ENEMY || skillMode == SkillMode.AUTO)) useSkill(f, ev) else normalAttack(f, ev)
        afterTurn(ev)
    }

    fun canUseSkillNow(f: Fighter) = f.cooldown == 0 && skillHasTarget(f)

    private fun skillHasTarget(f: Fighter): Boolean {
        val s = f.skill ?: return false
        return when (s) {
            Skill.HEAL -> allies(f).any { it.alive && (it.hp < it.maxHp || it.burning > 0 || (f.maxSkill && it.debuffed)) }
            Skill.FLASH_HEAL -> allies(f).any { it.alive && it.hp < it.maxHp }
            Skill.SHIELD_UP -> true
            else -> opponents(f).any { it.alive }
        }
    }

    private fun enemyAt(of: Fighter, slot: Int) = opponents(of).firstOrNull { it.alive && slot in it.slots }

    /**
     * Target of a normal attack: first living enemy in the column's target order (back row only when the front row is empty).
     * An ambusher goes for the card with the lowest HP, a crusher for the one with the most max HP.
     */
    private fun normalTarget(f: Fighter): Fighter? = when (f.trait) {
        BossTrait.AMBUSHER -> opponents(f).filter { it.alive }.minByOrNull { it.hp }
        // it charges at a raised shield, otherwise at the biggest card
        BossTrait.CRUSHER -> opponents(f).filter { it.alive }.maxByOrNull { (if (guardOf(it) > 0) 1e12 else 0.0) + it.maxHp }
        else -> Grid.targetOrder(f.column).firstNotNullOfOrNull { enemyAt(f, it) }
    }

    /** Aimed Shot: the same order with the rows swapped, so the back row comes first (B1, B2, B3, A1, A2, A3 for column 1). */
    private fun aimedSlot(f: Fighter): Int? =
        Grid.targetOrder(f.column).map { (it + 3) % 6 }.firstOrNull { enemyAt(f, it) != null }

    private fun dot(dmg: Int, share: Double) = max(1, (dmg * share).roundToInt())

    /** Burning / poison don't stack: a card that already has it only keeps the stronger of the two. */
    private fun afflict(t: Fighter, a: Affliction, amount: Int, ev: MutableList<BattleEvent>) {
        if (!t.alive) return
        if (a == Affliction.BURNING && t.side == Side.PLAYER && fireproof) return
        val had = if (a == Affliction.BURNING) t.burning else t.poison
        if (resists(t)) { ev += BattleEvent.Resisted(t.uid, a); return }
        val now = max(had, amount)
        if (a == Affliction.BURNING) t.burning = now else t.poison = now
        if (had == 0) ev += BattleEvent.Afflicted(t.uid, a)
    }

    /** [Passive.IRON_WILL]: rolls whether [t] shrugs off a debuff. */
    private fun resists(t: Fighter): Boolean {
        val chance = t.passive?.debuffResist(t.passiveLevel) ?: 0.0
        return chance > 0 && random.nextDouble() < chance
    }

    /** Stuns [t] (if it still stands and doesn't resist). */
    private fun stun(t: Fighter, ev: MutableList<BattleEvent>) {
        if (!t.alive) return
        if (resists(t)) { ev += BattleEvent.Resisted(t.uid, null); return }
        t.stunned = true
        ev += BattleEvent.Stun(t.uid)
    }

    /** Guard protecting [t]: its own Shield Up, or a max-level Shield Up of a friend in the same row. */
    fun guardOf(t: Fighter): Int {
        var g = t.guard
        for (w in allies(t)) if (w.alive && w.rowGuard && w.guard > g && w.rows.any { it in t.rows }) g = w.guard
        return g
    }

    private fun normalAttack(f: Fighter, ev: MutableList<BattleEvent>) {
        val t = normalTarget(f) ?: return
        ev += BattleEvent.Attack(f.uid, listOf(t.uid), null)
        if (f.trait == BossTrait.CRUSHER) {
            // a raised shield stops the blow completely and the crusher is stunned
            if (guardOf(t) > 0) {
                ev += BattleEvent.Damage(t.uid, 0, t.hp, blocked = true)
                crack(f, ev)
            } else hit(t, f.atk * CRUSHER_FACTOR, ev, by = f)
            return
        }
        hit(t, if (f.trait == BossTrait.AMBUSHER) f.atk * AMBUSHER_FACTOR else f.atk, ev, by = f)
    }

    private fun useSkill(f: Fighter, ev: MutableList<BattleEvent>) {
        val s = f.skill!!
        f.cooldown = if (f.trait == BossTrait.FIRESTORM) 1 else f.skillCooldown
        if (f.side == Side.PLAYER) playerUsedSkill = true
        val dmg = max(1, (f.atk * f.skillPower / 100.0).roundToInt())
        when (s) {
            Skill.SNEAK_ATTACK -> {
                val t = normalTarget(f) ?: return
                ev += BattleEvent.Attack(f.uid, listOf(t.uid), s)
                hit(t, dmg, ev, skill = s, by = f)
                if (f.maxSkill) afflict(t, Affliction.POISON, dot(dmg, Balance.POISON_SHARE), ev)
            }
            Skill.AIMED_SHOT -> {
                val slot = aimedSlot(f) ?: return
                // max level: the shot is split over the whole row of the target, and every card hit is stunned
                val targets = if (f.maxSkill) opponents(f).filter { it.alive && slot / 3 in it.rows }
                else listOf(enemyAt(f, slot)!!)
                ev += BattleEvent.Attack(f.uid, targets.map { it.uid }, s)
                val each = max(1, (dmg / targets.size.toDouble()).roundToInt())
                targets.forEach { hit(it, each, ev, skill = s, by = f) }
                targets.forEach { stun(it, ev) }
            }
            Skill.SOUL_LEECH -> {
                val t = normalTarget(f) ?: return
                // max level: a front-row target with an enemy right behind it -> both are hit (and both heal)
                val behind = if (f.maxSkill) t.slots.filter { it < 3 }.firstNotNullOfOrNull { sl ->
                    enemyAt(f, sl + 3)?.takeIf { it !== t }
                } else null
                val targets = listOfNotNull(t, behind)
                ev += BattleEvent.Attack(f.uid, targets.map { it.uid }, s)
                targets.forEach { t ->
                    val dealt = hit(t, dmg, ev, skill = s, by = f)
                    heal(f, f, dealt, ev, bonus = false)
                    // Blood Pact: the drained life also flows to every other friend
                    f.passive?.leechShare(f.passiveLevel)?.takeIf { it > 0 && dealt > 0 }?.let { share ->
                        allies(f).filter { it.alive && it !== f }.forEach { heal(f, it, max(1, (dealt * share).roundToInt()), ev, bonus = false) }
                    }
                }
            }
            Skill.WHIRLWIND -> {
                val alive = opponents(f).filter { it.alive }
                val targets = alive.filter { o -> o.slots.any { it < 3 } }.ifEmpty { alive }
                ev += BattleEvent.Attack(f.uid, targets.map { it.uid }, s)
                targets.forEach { hit(it, dmg, ev, skill = s, by = f) }
                if (f.maxSkill) targets.forEach { stun(it, ev) }
            }
            Skill.FIREBALL -> {
                val targets = opponents(f).filter { it.alive }
                ev += BattleEvent.Attack(f.uid, targets.map { it.uid }, s)
                targets.forEach { hit(it, dmg, ev, skill = s, by = f) }
                val burn = if (f.trait == BossTrait.FIRESTORM) FIRESTORM_BURN_SHARE else Balance.BURN_SHARE
                if (f.maxSkill) targets.forEach { afflict(it, Affliction.BURNING, dot(dmg, burn), ev) }
            }
            Skill.SHIELD_UP -> {
                f.guard = f.skillPower
                f.rowGuard = f.maxSkill
                ev += BattleEvent.GuardUp(f.uid, f.guard)
            }
            Skill.FLASH_HEAL -> {
                // max level: heals itself first, then the friend with the lowest HP (which can be itself again)
                if (f.maxSkill && f.hp < f.maxHp) {
                    ev += BattleEvent.Attack(f.uid, listOf(f.uid), s)
                    heal(f, f, dmg, ev)
                }
                // the wounded friend with the lowest HP (not the lowest %)
                val t = allies(f).filter { it.alive && it.hp < it.maxHp }.minByOrNull { it.hp } ?: return
                ev += BattleEvent.Attack(f.uid, listOf(t.uid), s)
                heal(f, t, dmg, ev)
                // the holy light reveals a hidden ambusher
                if (f.side == Side.PLAYER) enemies.filter { it.alive && it.trait == BossTrait.AMBUSHER && !it.traitBroken }
                    .forEach { it.traitBroken = true; ev += BattleEvent.TraitBroken(it.uid) }
            }
            Skill.HEAL -> {
                val targets = allies(f).filter { it.alive }
                ev += BattleEvent.Attack(f.uid, targets.map { it.uid }, s)
                targets.forEach { heal(f, it, dmg, ev) }
                // any Heal puts out the fire; against a firestorm it also makes the party fire-proof
                targets.filter { it.burning > 0 }.forEach { it.burning = 0; ev += BattleEvent.Extinguished(it.uid) }
                if (f.side == Side.PLAYER && enemies.any { it.trait == BossTrait.FIRESTORM }) {
                    fireproof = true
                    enemies.filter { it.alive && it.trait == BossTrait.FIRESTORM && !it.traitBroken }
                        .forEach { it.traitBroken = true; ev += BattleEvent.TraitBroken(it.uid) }
                }
                // max level: also removes every debuff from all friends
                if (f.maxSkill) targets.filter { it.debuffed }.forEach { it.cleanse(); ev += BattleEvent.Cleansed(it.uid) }
            }
        }
        // a counter that breaks the trait on the spot, yet the boss still has it: the skill hit something else
        if (f.side == Side.PLAYER) enemies.firstOrNull {
            it.alive && !it.traitBroken && it.trait in INSTANT_COUNTERS && it.trait?.counter == s
        }?.let { ev += BattleEvent.CounterMissed(f.uid, it.uid) }
    }

    /** Heals [t]; [bonus] = the healer's passive healing bonus applies (not to life drained by Soul Leech). */
    private fun heal(source: Fighter, t: Fighter, raw0: Int, ev: MutableList<BattleEvent>, bonus: Boolean = true) {
        val raw = if (!bonus) raw0 else (raw0 * (1 + (source.passive?.healBonus(source.passiveLevel) ?: 0.0))).roundToInt()
        val amount = minOf(raw, t.maxHp - t.hp)
        if (amount <= 0 || !t.alive) return
        t.hp += amount
        ev += BattleEvent.Heal(source.uid, t.uid, amount, t.hp)
    }

    /** A crusher hit a raised shield: it is stunned and its armor cracks (for the rest of the fight). */
    private fun crack(f: Fighter, ev: MutableList<BattleEvent>) {
        if (!f.stunned) { f.stunned = true; ev += BattleEvent.Stun(f.uid) }
        if (!f.traitBroken) { f.traitBroken = true; ev += BattleEvent.TraitBroken(f.uid) }
    }

    /**
     * Damages [t] ([skill] = the skill that hits, null for a normal attack; [by] = the attacker of a skill);
     * returns the HP actually taken away.
     */
    private fun hit(t: Fighter, raw0: Int, ev: MutableList<BattleEvent>, dot: Affliction? = null, skill: Skill? = null, by: Fighter? = null): Int {
        var raw = raw0
        var shieldedBy: BossTrait? = null
        var crit = false
        if (dot == null) {
            // passives of the defender (Evasion, Iron Will) and of the attacker (Rage, Holy Might, Keen Eye)
            val dodge = t.passive?.dodgeChance(t.passiveLevel) ?: 0.0
            if (dodge > 0 && random.nextDouble() < dodge) {
                ev += BattleEvent.Dodged(t.uid)
                return 0
            }
            val p = by?.passive
            if (p != null) {
                var factor = 1.0 + p.rageBonus(by.passiveLevel) * (by.maxHp - by.hp) / by.maxHp
                if (t.type.boss) factor += p.bossDamage(by.passiveLevel)
                val critChance = p.critChance(by.passiveLevel)
                if (critChance > 0 && random.nextDouble() < critChance) { crit = true; factor *= 2 }
                raw = max(1, (raw * factor).roundToInt())
            }
            t.passive?.damageReduction(t.passiveLevel)?.takeIf { it > 0 }?.let { raw = max(1, (raw * (1 - it)).roundToInt()) }
        }
        if (dot == null) {
            val area = skill == Skill.FIREBALL || skill == Skill.WHIRLWIND
            if (skill == Skill.WHIRLWIND && t.trait == BossTrait.STONE_SKIN && !t.traitBroken) {
                t.traitBroken = true // the spin shatters the stone skin (this hit already counts in full)
                ev += BattleEvent.TraitBroken(t.uid)
            }
            val boss = if (t.side == Side.ENEMY && !t.type.boss) enemies.firstOrNull { it.alive && it.trait != null } else null
            val protected = when (t.trait) {
                BossTrait.STONE_SKIN, BossTrait.CRUSHER, BossTrait.AMBUSHER -> !t.traitBroken
                BossTrait.SWARM, BossTrait.COMMANDER -> enemies.any { it.alive && it !== t }
                // helpers: swarm ones are too small to hit one by one, a commander's are shielded from area attacks
                else -> (boss?.trait == BossTrait.SWARM && !area) || (boss?.trait == BossTrait.COMMANDER && area)
            }
            val share = if (t.trait == BossTrait.COMMANDER) COMMANDER_DAMAGE_TAKEN else TRAIT_DAMAGE_TAKEN
            if (protected) {
                raw = max(1, (raw * share).roundToInt())
                shieldedBy = t.trait ?: boss?.trait
            }
        }
        // burning / poison go through shields
        val guard = if (dot != null) 0 else guardOf(t)
        if (guard > 0 && by?.trait == BossTrait.CRUSHER) crack(by, ev)
        val dmg = if (guard > 0) max(1, (raw * (100 - guard) / 100.0).roundToInt()) else raw
        val before = t.hp
        t.hp = max(0, t.hp - dmg)
        ev += BattleEvent.Damage(t.uid, dmg, t.hp, guard > 0, dot, shieldedBy, crit)
        if (!t.traitBroken && ((skill == Skill.SOUL_LEECH && t.trait == BossTrait.UNDYING) ||
                (skill == Skill.SNEAK_ATTACK && t.trait == BossTrait.REGENERATION))) {
            t.traitBroken = true
            t.spareLives = 0
            ev += BattleEvent.TraitBroken(t.uid)
        }
        if (!t.alive && t.spareLives > 0) {
            t.spareLives--
            t.hp = t.maxHp
            t.cleanse()
            ev += BattleEvent.Revived(t.uid, t.hp)
            return before
        }
        if (!t.alive) {
            ev += BattleEvent.Defeated(t.uid)
            if (t.side == Side.ENEMY) {
                val dropped = t.dropChance > 0 && random.nextDouble() < t.dropChance
                if (dropped) {
                    // (a boss drops a food card instead of itself)
                    loot += t
                    ev += BattleEvent.Looted(t.uid)
                }
                if ((!dropped || t.type.boss) && t.gold > 0) {
                    gold += t.gold
                    ev += BattleEvent.Coins(t.uid, t.gold)
                }
                if (t.diamonds > 0) {
                    diamonds += t.diamonds
                    ev += BattleEvent.Diamonds(t.uid, t.diamonds)
                }
                // the last helper fell: the swarm / commander loses its protection
                enemies.filter {
                    it.alive && !it.traitBroken && (it.trait == BossTrait.SWARM || it.trait == BossTrait.COMMANDER) &&
                        enemies.none { o -> o.alive && o !== it }
                }.forEach { it.traitBroken = true; ev += BattleEvent.TraitBroken(it.uid) }
            }
        }
        return before - t.hp
    }

    private fun afterTurn(ev: MutableList<BattleEvent>) {
        if (party.none { it.alive }) {
            phase = Phase.LOST
            ev += BattleEvent.Defeat
        } else if (enemies.none { it.alive }) {
            ev += BattleEvent.WaveCleared(wave)
            wavesCleared++
            if (waveCount.let { it == null || wave + 1 < it }) startWave(wave + 1, ev)
            else { phase = Phase.WON; ev += BattleEvent.Victory }
        }
    }
}
