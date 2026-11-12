package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.Phase
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity
import kotlin.random.Random

/** A simple bot that plays the game greedily. Used to check the balance. */
class Bot(
    starter: CardType, seed: Int, val log: Boolean = false, val buyHeroes: Boolean = true,
    /** Field only hero cards (strategy comparison). */
    val heroesOnly: Boolean = false,
    /** Bring the counter skill to a boss with a trait (and buy the counter hero when there is no such card). */
    val useCounters: Boolean = true,
    /** Collect stars: fight without skills when that gives more stars, and replay cleared stages for missing stars when stuck. */
    val huntStars: Boolean = true,
    /** Spend spare gold on cakes (food cards from the shop) for cards below their level cap. */
    val buyCakes: Boolean = true,
    /** Buy blue+ hero copies from the shop as evolution material for the party heroes. */
    val buyRareHeroes: Boolean = true,
    /** The old feeding: all spare loot at once into the strongest party card below its cap (most of it overflowed). */
    val oldFeeding: Boolean = false,
) {
    /** Free card slots below which held food (that would mostly overflow now) is fed or sold after all. */
    private val HOLD_SLACK = 4
    var s: GameState = Rules.newGame(starter)
    val random = Random(seed)
    var fights = 0
    /** Fights by purpose: a new stage, replaying a cleared stage for more stars, farming XP/gold on a cleared stage, lost. */
    var clearFights = 0; var starFights = 0; var farmFights = 0; var lostFights = 0
    /** Gold spent on cakes. */
    var cakeGold = 0L
    /** Why the bot can't play on (the run ends early), or null. */
    var blocked: String? = null
    /** Gold spent on blue+ heroes from the shop, and how many were bought. */
    var rareHeroGold = 0L; var rareHeroesBought = 0
    /** Gold spent on evolutions. */
    var evoGold = 0L
    /** Card XP actually gained (total XP) from fights, from feeding cards (loot) and from cakes. */
    var xpFights = 0L; var xpFeed = 0L; var xpCakes = 0L
    /** Feeding XP lost above the eater's level cap. */
    var xpWasted = 0L
    /** At the first clear of 4-15 (where the human testers are): XP fights/feed/cakes/wasted, heroes bought, cake gold, shop-rare gold. */
    var early: DoubleArray? = null
    /** Average skill level of the party heroes at the first clear of some stages. */
    val skillAt = linkedMapOf<String, Double>()

    private fun lifetime(id: Long) = s.card(id)?.totalXp ?: 0L
    val firstClearAt = linkedMapOf<String, Int>()
    /** stage -> average effective level of the party when the stage was first cleared */
    val effAt = linkedMapOf<String, Double>()
    val allStages = Dungeons.all.flatMap { it.stages }

    /**
     * Party for [stage]: the strongest cards (one hero per class, at most 2 monsters of a type). Before a boss with a trait
     * it also tries the party with the boss's counter skill and keeps it if that one wins (or if neither wins).
     * Every such try is counted in [traitTries] (stage -> [plain wins, counter wins, tries]).
     */
    fun arrangeParty(stage: cz.erlebach.cardrpg.content.StageDef? = null) {
        val counter = stage?.bossTrait?.counter
        place(pickParty(null))
        if (counter == null || !useCounters) return
        val plain = s.party
        val plainWins = wins(stage)
        place(pickParty(counter))
        if (s.party.toSet() == plain.toSet()) { restore(plain); return }
        val counterWins = wins(stage)
        if (stage.key !in s.cleared) traitTries.getOrPut(stage.key) { IntArray(3) }.let { it[0] += if (plainWins) 1 else 0; it[1] += if (counterWins) 1 else 0; it[2]++ }
        if (plainWins && !counterWins) restore(plain)
    }

    val traitTries = linkedMapOf<String, IntArray>()

    private fun wins(st: cz.erlebach.cardrpg.content.StageDef): Boolean = predictStars(st, SkillMode.AUTO) > 0

    /** Stars the current party would get on [st] in [mode] (0 = loss). Battles are deterministic, only the loot is random. */
    private fun predictStars(st: cz.erlebach.cardrpg.content.StageDef, mode: SkillMode): Int {
        if (!s.isUnlocked(st) || s.gold < st.entryFee || s.inventoryFull) return 0
        val (_, b) = Rules.startFight(s, st)
        return if (play(b, mode) == Phase.WON) Rules.starsFor(b) else 0
    }

    /** Best (stars, mode) for [st] with the current party: no skills when that gives more stars, otherwise auto. */
    private fun bestMode(st: cz.erlebach.cardrpg.content.StageDef): Pair<Int, SkillMode> {
        val auto = predictStars(st, SkillMode.AUTO)
        if (!huntStars || auto == 3) return auto to SkillMode.AUTO
        val never = predictStars(st, SkillMode.NEVER)
        return if (never > auto) never to SkillMode.NEVER else auto to SkillMode.AUTO
    }

    /**
     * A cleared stage where the party (arranged for it) can now get more stars than it has, the highest tier first.
     * Returns the stage and the skill mode and leaves the party arranged for it.
     */
    private fun starHunt(stages: List<cz.erlebach.cardrpg.content.StageDef>): Pair<cz.erlebach.cardrpg.content.StageDef, SkillMode>? {
        val saved = s
        for (st in stages.filter { it.key in s.cleared && s.stars(it.key) < 3 }.sortedByDescending { it.tier }) {
            arrangeParty(st)
            val (stars, mode) = bestMode(st)
            if (stars > s.stars(st.key)) return st to mode
            s = saved
        }
        return null
    }

    private fun pickParty(counter: cz.erlebach.cardrpg.model.Skill?): List<OwnedCard> {
        val pool = s.cards.filter { !it.type.food && (!heroesOnly || it.type.hero) }
        val picked = pool.sortedByDescending { it.power }.groupBy { it.type }.values
            .flatMap { it.take(if (it.first().type.hero) 1 else 2) }
            .sortedByDescending { it.power }.take(5).toMutableList()
        if (counter != null && picked.none { it.hasSkill && it.type.skill == counter }) {
            val best = pool.filter { it.hasSkill && it.type.skill == counter && it !in picked }.maxByOrNull { it.power }
            if (best != null) { if (picked.size >= 5) picked.removeAt(picked.lastIndex); picked += best }
        }
        return picked
    }

    private fun place(cards: List<OwnedCard>) {
        val ordered = cards.sortedByDescending { it.hp }
        val slots = listOf(0, 1, 2, 4, 3)
        val party = MutableList<Long?>(6) { null }
        ordered.forEachIndexed { i, c -> party[slots[i]] = c.id }
        s = s.copy(party = party)
    }

    private fun restore(party: List<Long?>) { s = s.copy(party = party) }

    /** [arrange] = pick the party for the stage first (otherwise the current party fights). */
    fun wouldWin(stageKey: String, arrange: Boolean = true): Boolean {
        val st = Dungeons.stage(stageKey)!!
        if (!s.isUnlocked(st) || s.gold < st.entryFee) return false
        val saved = s
        if (arrange) arrangeParty(st)
        val (_, b) = Rules.startFight(s, st)
        s = saved
        return b.runToEnd() == Phase.WON
    }

    fun step() {
        arrangeParty()
        makeRoom()
        // no way to fight any more: a full inventory it can't clear, or not enough gold for any stage
        if (s.inventoryFull || s.gold < minFee()) { blocked = if (s.inventoryFull) "inventory full" else "no gold"; return }
        val unlocked = allStages.filter { s.isUnlocked(it) && s.gold >= it.entryFee }.sortedByDescending { (if (it.key in s.cleared) 0 else 10000) + it.tier * 100 + it.dungeon }
        var target = unlocked.firstOrNull { it.key !in s.cleared && wouldWin(it.key) }
        var mode = SkillMode.AUTO
        var purpose = "clear"
        val hunt = if (target == null && huntStars) starHunt(unlocked) else null
        if (target != null) {
            arrangeParty(target)
            mode = bestMode(target).second
        } else if (hunt != null) {
            target = hunt.first; mode = hunt.second; purpose = "stars"
        } else {
            purpose = "farm"
            // stuck: take a spare hero along (instead of the weakest card) to level it up on a stage we can still win
            val reserved = reserved()
            val trainee = s.cards.filter { it.type.hero && !s.inParty(it.id) && it.id !in reserved && it.level < Rules.levelCap(s, it) }
                .maxByOrNull { it.rarity.ordinal * 100 + it.evo * 40 + it.level }
            if (trainee != null) {
                val twin = s.partyCards.firstOrNull { it.type == trainee.type }
                val weakest = twin ?: s.partyCards.minBy { it.power }
                s = s.copy(party = s.party.map { if (it == weakest.id) trainee.id else it })
            }
            target = unlocked.firstOrNull { wouldWin(it.key, arrange = false) } ?: unlocked.minByOrNull { it.tier }
                ?: error("no stage to fight: gold=${s.gold}, cards=${s.cards.size}/${s.inventorySize}, party=${s.partyCards.size}, fights=$fights (lost $lostFights), cleared=${s.cleared.size}, cakes=$cakeGold, evo=$evoGold, slots=${s.inventoryUpgrades}")
        }
        val (s1, b) = Rules.startFight(s, target, random)
        play(b, mode)
        val (s2, rewards) = Rules.finishFight(s1, target, b)
        xpFights += s2.partyCards.sumOf { it.totalXp - (s1.card(it.id)?.totalXp ?: 0L) }
        s = s2
        fights++
        when {
            rewards == null -> lostFights++
            purpose == "clear" -> clearFights++
            purpose == "stars" -> starFights++
            else -> farmFights++
        }
        if (rewards != null && rewards.milestones.isNotEmpty() && log)
            println("fight $fights: dungeon ${target.dungeon} star milestone ${rewards.milestones.joinToString { "${it.stars}*" }}")
        if (rewards != null && rewards.firstClear) {
            firstClearAt[target.key] = fights
            if (target.key in listOf("2-16", "4-15", "6-20", "8-20")) skillAt[target.key] = s.partyCards.filter { it.type.hero }.map { it.skillLevel }.average()
            if (target.key == "4-15" && System.getenv("SIM_DEBUG_PARTY") != null) println("4-15 party: " + s.partyCards.joinToString { "${it.fullName} L${it.level} S${it.skillLevel}" } + " | bench heroes: " + s.cards.filter { it.type.hero && !s.inParty(it.id) }.joinToString { "${it.fullName} L${it.level} S${it.skillLevel}" })
            if (target.key == "4-15") early = doubleArrayOf(xpFights.toDouble(), xpFeed.toDouble(), xpCakes.toDouble(), xpWasted.toDouble(),
                s.heroesBought.toDouble(), cakeGold.toDouble(), rareHeroGold.toDouble(), s.cards.sumOf { it.sellPrice }.toDouble())
            effAt[target.key] = s.partyCards.map { Balance.effectiveLevel(it.level, it.rarity, it.evo) }.average()
            if (log) println("fight $fights: cleared ${target.key}  P${s.playerLevel} gold=${s.gold} party=${s.partyCards.joinToString { "${it.fullName} L${it.level}" }}")
        }
        manage()
    }

    /**
     * Spare cards kept as evolution material for a stronger card of the same type and rarity
     * (party cards first, then the strongest). They are not fed, sold or evolved themselves.
     */
    fun reserved(): Set<Long> {
        val res = mutableSetOf<Long>()
        val order = s.partyCards + s.cards.filter { !s.inParty(it.id) }.sortedByDescending { it.rarity.ordinal * 100 + it.evo * 40 + it.level }
        for (c in order) {
            if (c.id in res || (c.atMaxEvo && !(c.type.hero && c.rarity.next != null))) continue
            val need = if (c.atMaxEvo) 0 else Balance.evolveMaterials(c.evo)
            Rules.evolveMaterialCandidates(s, c).filter { it.id !in res && it.evo == 0 }.take(need).forEach { res += it.id }
        }
        return res
    }

    fun manage() {
        arrangeParty()
        // evolve whatever can be evolved (party and bench), strongest first
        var evolved = true
        while (evolved) {
            evolved = false
            val reserved = reserved()
            for (c in s.cards.filter { it.id !in reserved }.sortedByDescending { it.rarity.ordinal * 10 + it.evo }) {
                val chk = Rules.evolveCheck(s, c)
                if (chk is Rules.EvolveCheck.Ok && s.gold >= chk.gold + maxFee()) {
                    val mats = Rules.evolveMaterialCandidates(s, c).take(chk.materials)
                    if (mats.size == chk.materials) {
                        evoGold += chk.gold
                        s = Rules.evolve(s, c.id, mats.map { it.id })
                        if (log) println("   evolved ${c.fullName} -> ${s.card(c.id)!!.fullName}")
                        evolved = true
                        break
                    }
                }
            }
        }
        arrangeParty()
        if (buyHeroes) buy()
        val party = s.partyCards
        val keep = reserved()
        val spare = s.cards.filter { !s.inParty(it.id) && it.id !in keep && !it.type.hero }
        val (food, junk) = spare.partition { it.sacrificeValue > 0 }
        if (junk.isNotEmpty()) s = Rules.sell(s, junk.map { it.id })
        if (oldFeeding) {
            if (food.isNotEmpty()) {
                // party first, then the best bench hero (it grows towards an evolution)
                val target = party.filter { it.level < Rules.levelCap(s, it) }.maxByOrNull { it.power }
                    ?: s.cards.filter { it.type.hero && !s.inParty(it.id) && it.id !in keep && it.level < Rules.levelCap(s, it) }
                        .maxByOrNull { it.rarity.ordinal * 100 + it.evo * 40 + it.level }
                if (target != null) {
                    val value = food.sumOf { Rules.feedXp(target, it) }
                    val before = lifetime(target.id)
                    s = Rules.feed(s, target.id, food.map { it.id }, random)
                    xpFeed += lifetime(target.id) - before
                    xpWasted += value - (lifetime(target.id) - before)
                }
            }
        } else feedLoot()
        if (buyCakes) buyCakes()
    }

    /** XP [target] would really keep from eating [food] (the part above its level cap is lost). */
    private fun keptXp(target: OwnedCard, food: OwnedCard): Long =
        Rules.addCardXp(s, target, Rules.feedXp(target, food)).totalXp - target.totalXp

    /** XP [target] can still take before it hits its level cap. */
    private fun room(target: OwnedCard): Long = Rules.addCardXp(s, target, Long.MAX_VALUE / 4).totalXp - target.totalXp

    /**
     * Feeds the spare loot the way a human does: one card at a time, each to a card that can take all of its XP –
     * a party hero that can still learn its skill from the card first (even if XP overflows), then the party (the tightest fit, so the big cards are left
     * for the cards with a lot of room), then the bench heroes being raised (the green copies growing into evolution
     * material). A card that would lose more than half of its XP is kept for later (a freshly evolved card starts at
     * level 1) while there are free slots; if almost all of it would be lost, it is sold.
     */
    fun feedLoot() {
        while (true) {
            val keep = reserved()
            val food = s.cards.filter { !s.inParty(it.id) && it.id !in keep && !it.type.hero && it.sacrificeValue > 0 }
                .sortedByDescending { it.sacrificeValue }
            if (food.isEmpty()) return
            val targets = s.partyCards.filter { !it.type.food } +
                s.cards.filter { it.type.hero && !s.inParty(it.id) && it.id !in keep }
            val freeSlots = s.inventorySize - s.cards.size
            val mainHeroes = s.cards.filter { it.type.hero }.groupBy { it.type }.values.map { g -> g.maxBy { it.rank }.id }.toSet()
            var changed = false
            for (f in food) {
                val options = targets.map { t -> Triple(t, keptXp(t, f), Rules.feedXp(t, f)) }
                if (options.isEmpty()) return
                // the main card of a hero type (its best one) that can learn its skill from this card gets it even if XP
                // overflows (the skill is worth more and goes along through every evolution)
                val learner = options.filter { it.first.id in mainHeroes && Rules.skillUpChance(it.first, listOf(f)) > 0 }
                    .maxByOrNull { it.first.rank }
                val fits = options.filter { it.second >= 0.95 * it.third }
                // party first, then the most advanced bench hero (a freshly evolved one at level 1), then the tightest fit
                val pick = learner ?: fits.maxWithOrNull(compareBy<Triple<OwnedCard, Long, Long>>(
                    { s.inParty(it.first.id) }, { it.first.rarity.ordinal * 10 + it.first.evo }, { -room(it.first) },
                )) ?: options.maxBy { it.second.toDouble() / it.third }
                val (t, kept, value) = pick
                if (pick !== learner && kept < 0.5 * value) {
                    if (freeSlots > HOLD_SLACK) continue // keep it for a card with more room
                    if (kept < 0.1 * value) { s = Rules.sell(s, listOf(f.id)); changed = true; break }
                }
                s = Rules.feed(s, t.id, listOf(f.id), random)
                xpFeed += kept; xpWasted += value - kept
                changed = true
                break
            }
            if (!changed) return
        }
    }

    private fun xpToCap(c: OwnedCard): Long =
        (c.level until Rules.levelCap(s, c)).sumOf { Balance.cardXpToNext(it, c.type.hero) } - c.xp

    /**
     * Spends the gold above a reserve (entry fees, two heroes, the party's next evolution) on cakes: party cards below
     * their level cap first (lowest level first), then the bench heroes being raised. Buys the biggest cake that doesn't
     * overshoot the cap (a white one if even that does).
     */
    fun buyCakes() {
        // keep gold for the fees, two green heroes, the party's next evolution and - saving up - the next shop copy a raised hero needs
        val saving = if (buyRareHeroes) shopNeeds().minOfOrNull { s.heroPrice(it.rarity) } ?: 0 else 0
        val keepGold = feeReserve() + 2 * s.heroPrice + (s.partyCards.maxOfOrNull { Balance.evolveGoldCost(it.rarity, it.evo) } ?: 0) + saving
        repeat(500) {
            if (s.inventoryFull) return
            val keep = reserved()
            val target = s.partyCards.filter { it.level < Rules.levelCap(s, it) }.minByOrNull { it.level }
                ?: s.cards.filter { it.type.hero && !s.inParty(it.id) && it.id !in keep && it.level < Rules.levelCap(s, it) }
                    .maxByOrNull { it.rarity.ordinal * 100 + it.evo * 40 + it.level }
                ?: return
            val affordable = Rarity.entries.filter { Balance.foodPrice(it) <= s.gold - keepGold }
            if (affordable.isEmpty()) return
            val need = xpToCap(target)
            val r = affordable.lastOrNull { Balance.sacrificeBonus(it, 0) * Balance.FOOD_XP_FACTOR <= need } ?: affordable.first()
            val before = s.cards.map { it.id }.toSet()
            s = Rules.buyFood(s, r)
            cakeGold += Balance.foodPrice(r)
            val xpBefore = lifetime(target.id)
            s = Rules.feed(s, target.id, s.cards.filter { it.id !in before }.map { it.id }, random)
            xpCakes += lifetime(target.id) - xpBefore
        }
    }

    /** The highest entry fee of an open stage: every purchase leaves at least this much (so the bot can't go broke). */
    private fun maxFee() = allStages.filter { s.isUnlocked(it) }.maxOf { it.entryFee }
    private fun minFee() = allStages.filter { s.isUnlocked(it) }.minOf { it.entryFee }
    private fun feeReserve() = maxFee() * 5

    /**
     * Keeps a free card slot (a fight can't start with a full inventory): buys more slots when it can afford them,
     * otherwise sells the least valuable spare monster card (even evolution material).
     */
    fun makeRoom() {
        while (s.inventoryFull) {
            if (s.gold >= s.inventoryUpgradeCost + feeReserve()) { s = Rules.upgradeInventory(s); continue }
            val spare = s.cards.filter { !s.inParty(it.id) && !it.type.hero }.minByOrNull { it.sacrificeValue }
            if (spare != null) s = Rules.sell(s, listOf(spare.id))
            else if (s.gold >= s.inventoryUpgradeCost + minFee()) s = Rules.upgradeInventory(s)
            else return
        }
    }

    /**
     * Heroes we raise (the party heroes and the two strongest heroes outside it - a hero pushed out by monsters still has to
     * grow towards legendary) that miss evolution material the shop sells (blue or better).
     */
    private fun shopNeeds(): List<OwnedCard> {
        val keep = reserved()
        val raised = s.partyCards.filter { it.type.hero } + s.cards.filter { it.type.hero && !s.inParty(it.id) && it.id !in keep }
            .sortedByDescending { it.rarity.ordinal * 100 + it.evo * 40 + it.level }.distinctBy { it.type }
            .filter { b -> s.partyCards.none { it.type == b.type } }.take(2)
        return raised.filter {
            it.rarity != Balance.HERO_BUY_RARITY && !it.fullyUpgraded && it.rarity in s.heroShopRarities(it.type) &&
                Rules.evolveMaterialCandidates(s, it).size < Balance.evolveMaterials(it.evo)
        }
    }

    /** Room for one more bought card while one slot stays free for fighting. */
    private fun canBuyCard() = s.cards.size + 2 <= s.inventorySize

    /** Buys spare copies of the party heroes (evolution material), keeping a reserve for entry fees. */
    fun buy() {
        val reserve = feeReserve()
        // more card slots once the inventory is nearly full and there's gold to spare
        if (s.cards.size + 3 > s.inventorySize && s.gold >= s.inventoryUpgradeCost * 2 + reserve) s = Rules.upgradeInventory(s)
        // a new hero type to raise (healers first) once there is plenty of gold and the party has a weak spot
        val owned = s.cards.filter { it.type.hero }.map { it.type }.toSet()
        val party = s.partyCards
        // the hero that counters the next trait boss, if we have no card with that skill yet
        if (useCounters) {
            val next = allStages.firstOrNull { it.key !in s.cleared && it.bossTrait != null }?.bossTrait
            if (next != null && s.cards.none { it.hasSkill && it.type.skill == next.counter } && s.gold >= s.heroPrice + reserve && canBuyCard()) {
                s = Rules.buyHero(s, next.counterHero)
                if (log) println("   bought a counter hero: ${next.counterHero.title}")
                return
            }
        }
        val weakSpot = party.size < 5 || party.minOf { it.power } < party.maxOf { it.power } / 20
        if (weakSpot && owned.size < 4 && s.gold >= s.heroPrice * 10 + reserve && canBuyCard()) {
            val t = listOf(CardType.PRIEST, CardType.PALADIN, CardType.BERSERKER, CardType.WARLOCK, CardType.HUNTER,
                CardType.MAGE, CardType.ROGUE, CardType.WARRIOR).first { it !in owned }
            s = Rules.buyHero(s, t)
            if (log) println("   bought a new hero: ${t.title}")
        }
        // blue+ copies from the shop: the evolution material a hero we raise is missing
        if (buyRareHeroes) for (c in shopNeeds()) {
            val price = s.heroPrice(c.rarity)
            if (s.gold >= price + reserve && canBuyCard()) {
                s = Rules.buyHero(s, c.type, c.rarity)
                rareHeroGold += price; rareHeroesBought++
                if (log) println("   bought a ${c.rarity.label} ${c.type.title} for $price")
            }
        }
        // spare copies of the heroes we raise (evolution material)
        val heroTypes = s.cards.filter { it.type.hero }.map { it.type }.distinct()
        for (t in heroTypes) {
            val bench = s.cards.count { it.type == t && !s.inParty(it.id) && it.rarity == Rarity.UNCOMMON && it.evo == 0 }
            if (bench < 3 && s.gold >= s.heroPrice + reserve && canBuyCard()) {
                s = Rules.buyHero(s, t)
                if (log) println("   bought ${t.title} for ${s.heroPrice}")
            }
        }
    }
}

/** Plays [b] to the end in [mode] (AUTO = every skill as soon as it's ready, NEVER = only normal attacks). */
fun play(b: Battle, mode: SkillMode): Phase {
    if (mode == SkillMode.AUTO) return b.runToEnd()
    b.skillMode = mode
    var guard = 0
    while (!b.finished) { b.advance(); if (++guard > 100_000) error("battle did not finish") }
    return b.phase
}

val milestones = Dungeons.all.flatMap { d -> listOf("${d.id}-10", "${d.id}-20") }

fun main(args: Array<String>) {
    val seeds = args.firstOrNull()?.toInt() ?: 6
    val total = Dungeons.all.sumOf { it.stages.size }
    val maxFights = System.getenv("SIM_MAX_FIGHTS")?.toInt() ?: 6000
    val allKeys = Dungeons.all.flatMap { d -> d.stages.map { it.key } }
    // per stage: fights spent between the previous first clear and this one (where the walls are)
    val wall = mutableMapOf<String, MutableList<Int>>()
    val eff = mutableMapOf<String, MutableList<Double>>()
    val runs = mutableListOf<Int>()
    val purposes = mutableListOf<IntArray>()
    val cakeGolds = mutableListOf<Long>()
    val xpShares = mutableListOf<DoubleArray>()
    val dungeonStars = sortedMapOf<Int, MutableList<Int>>()
    val traitTries = sortedMapOf<String, IntArray>(compareBy({ it.substringBefore('-').toInt() }, { it.substringAfter('-').toInt() }))
    // the runs are independent: play them on all cores, then report them in order
    val jobs = CardType.starters.flatMap { starter -> (1..seeds).map { starter to it } }
    val bots = jobs.parallelStream().map { (starter, seed) ->
        Bot(
            starter, seed, buyHeroes = System.getenv("SIM_NO_BUY") == null,
            heroesOnly = System.getenv("SIM_HEROES_ONLY") != null, useCounters = System.getenv("SIM_NO_COUNTER") == null,
            huntStars = System.getenv("SIM_NO_STARS") == null, buyCakes = System.getenv("SIM_NO_CAKES") == null,
            buyRareHeroes = System.getenv("SIM_NO_RARE_HEROES") == null, oldFeeding = System.getenv("SIM_OLD_FEED") != null,
        ).also { bot -> while (bot.fights < maxFights && bot.s.cleared.size < total && bot.blocked == null) bot.step() }
    }.toList()
    for ((job, bot) in jobs.zip(bots)) {
        val (starter, seed) = job
        run {
            val c = bot.firstClearAt
            runs += bot.fights
            purposes += intArrayOf(bot.clearFights, bot.starFights, bot.farmFights, bot.lostFights)
            cakeGolds += bot.cakeGold
            xpShares += doubleArrayOf(bot.xpFights.toDouble(), bot.xpFeed.toDouble(), bot.xpCakes.toDouble(), bot.xpWasted.toDouble())
            for (d in Dungeons.all) if (bot.s.isUnlocked(d.stages.first())) dungeonStars.getOrPut(d.id) { mutableListOf() } += bot.s.dungeonStars(d)
            bot.traitTries.forEach { (k, v) -> traitTries.getOrPut(k) { IntArray(3) }.let { a -> repeat(3) { a[it] += v[it] } } }
            var prev = 0
            for ((k, f) in c.entries.sortedBy { it.value }) { wall.getOrPut(k) { mutableListOf() } += f - prev; prev = f }
            bot.effAt.forEach { (k, v) -> eff.getOrPut(k) { mutableListOf() } += v }
            val stuck = allKeys.firstOrNull { it !in bot.s.cleared }
            println("$starter seed $seed: " + milestones.joinToString(" ") { "$it@${c[it] ?: "-"}" } +
                "  total=${bot.fights} (new ${bot.clearFights}, stars ${bot.starFights}, farm ${bot.farmFights}, lost ${bot.lostFights}) " +
                "stars=${Dungeons.all.sumOf { bot.s.dungeonStars(it) }}${if (stuck != null) " STUCK at $stuck" else ""}${bot.blocked?.let { " BLOCKED ($it)" } ?: ""} P${bot.s.playerLevel} gold=${fmtG(bot.s.gold)} bought=${bot.s.heroesBought} cakes=${fmtG(bot.cakeGold)} rareHeroes=${bot.rareHeroesBought}/${fmtG(bot.rareHeroGold)} | " +
                bot.s.partyCards.joinToString { "${it.fullName} L${it.level} S${it.skillLevel}" })
        }
    }
    if (System.getenv("SIM_EFF") != null) {
        println()
        println("Party effective level at first clear (stage: base level of the stage -> avg party effective level):")
        for (d in Dungeons.all) println("  " + d.stages.joinToString("  ") { st ->
            "${st.key}:${st.waves.flatten().maxOf { Balance.effectiveLevel(it.level, it.rarity, it.evo) }.toInt()}->${eff[st.key]?.average()?.toInt() ?: "-"}"
        })
    }
    println()
    println("Average fights per stage (first clears), regular stages vs boss stages (X-5/10/15/20):")
    for (d in Dungeons.all) {
        fun avg(boss: Boolean) = d.stages.filter { (it.stage % 5 == 0) == boss }.flatMap { wall[it.key].orEmpty() }.average()
        val worst = d.stages.maxBy { wall[it.key]?.average() ?: 0.0 }
        val bosses = d.stages.filter { it.stage % 5 == 0 }.joinToString(" ") { "${it.key}=%.1f".format(wall[it.key]?.average() ?: 0.0) }
        println("  dungeon ${d.id}: regular %.1f  boss %.1f  (worst ${worst.key}: %.1f)   $bosses".format(avg(false), avg(true), wall[worst.key]?.average() ?: 0.0))
    }
    if (traitTries.isNotEmpty()) {
        println("Trait bosses before their first clear: how often the plain party / the party with the counter skill would win")
        for ((k, v) in traitTries) {
            val tr = Dungeons.stage(k)!!.bossTrait!!
            println("  $k ${tr.title} (${tr.counter.title}): plain %3.0f %%, counter %3.0f %%  (%d tries)".format(100.0 * v[0] / v[2], 100.0 * v[1] / v[2], v[2]))
        }
    }
    bots.mapNotNull { it.early }.takeIf { it.isNotEmpty() }?.let { e ->
        fun m(i: Int) = e.map { it[i] }.sorted()[e.size / 2]
        val x = (0..2).map { i -> e.sumOf { it[i] } }
        println("Up to 4-15: card XP fights %.0f %%, feeding %.0f %%, cakes %.0f %%, lost above cap %.0f %% of fed; median heroes bought %.0f, cake gold %.0f, shop-hero gold %.0f"
            .format(100 * x[0] / x.sum(), 100 * x[1] / x.sum(), 100 * x[2] / x.sum(), 100 * e.sumOf { it[3] } / (x[1] + e.sumOf { it[3] }), m(4), m(5), m(6)))
    }
    println("Party heroes' average skill level at first clear: " + listOf("2-16", "4-15", "6-20", "8-20").joinToString("  ") { k ->
        "$k: %.1f".format(bots.mapNotNull { it.skillAt[k]?.takeIf { v -> !v.isNaN() } }.average()) })
    val clears = bots.map { it.firstClearAt }
    println("First clear (median fight number, incl. star replays/farming): " + listOf("2-16", "4-15", "6-20", "8-20", "10-20").joinToString("  ") { k ->
        val v = clears.mapNotNull { it[k] }.sorted(); "$k@${if (v.isEmpty()) "-" else v[v.size / 2]}" })
    val totals = runs.sorted()
    fun med(i: Int) = purposes.map { it[i] }.sorted()[purposes.size / 2]
    println("Fights by purpose (median): new stage ${med(0)}, replay for stars ${med(1)}, farming ${med(2)}, lost ${med(3)}")
    println("Gold spent on cakes (median): ${fmtG(cakeGolds.sorted()[cakeGolds.size / 2])}")
    val xpSum = (0..2).map { i -> xpShares.sumOf { it[i] } }
    println("Card XP gained (all runs): fights %.0f %%, feeding cards %.0f %%, cakes %.0f %%".format(*xpSum.map { 100 * it / xpSum.sum() }.toTypedArray()) +
        "; feeding XP lost above the level cap: %.0f %% of the fed loot's XP".format(100 * xpShares.sumOf { it[3] } / (xpSum[1] + xpShares.sumOf { it[3] })))
    println("Stars per dungeon at the end (average of the runs that reached it, max 60): " +
        dungeonStars.entries.joinToString("  ") { (d, v) -> "$d: %.0f".format(v.average()) })
    println("Total fights: median ${totals[totals.size / 2]}, range ${totals.first()}..${totals.last()}")
    println()
    println("Walls (average fights since the previous first clear - farming and star replays included; top 15):")
    wall.entries.sortedByDescending { it.value.average() }.take(15)
        .forEach { (k, v) -> println("  $k: %.1f  (max ${v.max()}, cleared in ${v.size} runs)".format(v.average())) }
}

private fun fmtG(g: Long) = if (g >= 1_000_000) "%.1fM".format(g / 1e6) else if (g >= 10_000) "${g / 1000}k" else "$g"
