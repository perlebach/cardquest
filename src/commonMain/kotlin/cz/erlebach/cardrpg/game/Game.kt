@file:OptIn(ExperimentalAtomicApi::class)

package cz.erlebach.cardrpg.game

import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.Fighter
import cz.erlebach.cardrpg.battle.Side
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.Contest
import cz.erlebach.cardrpg.content.DungeonDef
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.content.Milestone
import cz.erlebach.cardrpg.content.MonsterSpec
import cz.erlebach.cardrpg.content.StageDef
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@Serializable
data class GameState(
    val started: Boolean = false,
    val playerLevel: Int = 1,
    val playerXp: Long = 0,
    val gold: Long = 0,
    val cards: List<OwnedCard> = emptyList(),
    /** Card id per grid spot (0..5 = A1..A3, B1..B3). */
    val party: List<Long?> = List(6) { null },
    /** Won stages (1st star). */
    val cleared: Set<String> = emptySet(),
    /**
     * Best star rating per stage, from a single fight: 1 = won, +1 for no card knocked out, +1 for no skill used.
     * The conditions don't add up across fights (no skills in one win + no losses in another = still 2 stars).
     */
    val bestStars: Map<String, Int> = emptyMap(),
    /** Star milestones already given, as "dungeon:stars" (e.g. "1:20"). */
    val claimedMilestones: Set<String> = emptySet(),
    val nextId: Long = 1,
    val skillMode: SkillMode = SkillMode.AUTO,
    val battleSpeed: Int = 1,
    val wins: Int = 0,
    val losses: Int = 0,
    /** Number of hero cards bought so far (each one makes the next more expensive). */
    val heroesBought: Int = 0,
    /** Number of bought inventory upgrades (+[Balance.INVENTORY_STEP] card slots each). */
    val inventoryUpgrades: Int = 0,
    /** Meditation clues per hero class (spent on shortening skill cooldowns). */
    val clues: Map<CardType, Long> = emptyMap(),
    /** The Meditation tab is open (once the player owned a rare or better hero; it stays open). */
    val meditationUnlocked: Boolean = false,
    /** Local day (epoch day) up to which the meditation clues were collected; null = never. */
    val lastClueDay: Long? = null,
    /** The Contest tab is open (once the player reached [Contest.UNLOCK_LEVEL]; it stays open). */
    val contestUnlocked: Boolean = false,
    /** Contest entry tickets. */
    val contestTickets: Int = 0,
    /** Local day (epoch day) up to which the daily contest tickets were given; null = never. */
    val lastTicketDay: Long? = null,
    /** Contest currency: buys passive abilities for hero cards (and gold / food as a fallback). */
    val diamonds: Long = 0,
    /** Most waves beaten in one contest. */
    val contestBestWaves: Int = 0,
) {
    fun card(id: Long?) = cards.firstOrNull { it.id == id }
    val partyCards get() = party.mapNotNull { card(it) }
    /** Sum of HP + ATK of the party cards (shown in the saved-games list). */
    val partyStrength get() = partyCards.sumOf { it.hp.toLong() + it.atk }
    fun inParty(id: Long) = id in party
    val playerXpToNext get() = Balance.playerXpToNext(playerLevel)
    val heroPrice get() = Balance.heroPrice(heroesBought)
    fun heroPrice(rarity: Rarity) = Balance.heroPrice(rarity, heroesBought)
    /**
     * Rarities of the hero [type] the shop sells: always [Balance.HERO_BUY_RARITY], plus every higher rarity up to the best
     * card of that hero the player owns (evolving a hero to a new rarity puts that rarity into the shop).
     */
    fun heroShopRarities(type: CardType): List<Rarity> {
        val best = cards.filter { it.type == type }.maxOfOrNull { it.rarity.ordinal } ?: -1
        return Rarity.entries.filter { it == Balance.HERO_BUY_RARITY || it in Balance.HERO_PRICE_BY_RARITY && it.ordinal <= best }
    }
    val inventorySize get() = Balance.inventorySize(inventoryUpgrades)
    val inventoryUpgradeCost get() = Balance.inventoryUpgradeCost(inventoryUpgrades)
    /** No free card slot: fights can't be started and cards can't be bought (loot may still go above the limit). */
    val inventoryFull get() = cards.size >= inventorySize

    fun clues(type: CardType) = clues[type] ?: 0L

    /** The meditating hero of each class: the best rare-or-better card of that hero (classes without one are missing). */
    val meditators: Map<CardType, OwnedCard> get() = CardType.heroes.mapNotNull { t ->
        cards.filter { it.type == t && Rules.canMeditate(it) }.maxByOrNull { it.rank }?.let { t to it }
    }.toMap()

    /** Clues found every night, per class. */
    val cluesPerDay: Map<CardType, Long> get() = meditators.mapValues { (_, c) -> Balance.cluesPerDay(c.rarity, c.evo) }

    fun isUnlocked(stage: StageDef): Boolean {
        val dungeon = Dungeons.all.first { it.id == stage.dungeon }
        val dungeonOpen = dungeon.unlockedBy == null || dungeon.unlockedBy in cleared
        // (also checked for later stages: in old saves a dungeon could have been opened earlier)
        return dungeonOpen && (stage.stage == 1 || "${stage.dungeon}-${stage.stage - 1}" in cleared)
    }

    fun isDungeonUnlocked(id: Int) = isUnlocked(Dungeons.all.first { it.id == id }.stages.first())

    /** Stars of a stage (0..3): the best single fight. */
    fun stars(key: String) = maxOf(bestStars[key] ?: 0, if (key in cleared) 1 else 0)
    fun dungeonStars(d: DungeonDef) = d.stages.sumOf { stars(it.key) }
    /** All stars of the dungeon collected. */
    fun dungeonComplete(d: DungeonDef) = dungeonStars(d) >= d.maxStars
}

/** Results of a contest (it always ends with the party beaten or running away). */
data class ContestResult(val waves: Int, val diamonds: Long, val newBest: Boolean)

/** Results of a won fight, for the summary screen. */
data class FightRewards(
    val gold: Long,
    val playerXp: Long,
    val cardXp: Long,
    val drops: List<OwnedCard>,
    val playerLevelBefore: Int,
    val playerLevelAfter: Int,
    /** card id -> (level before, level after) */
    val cardLevels: Map<Long, Pair<Int, Int>>,
    val firstClear: Boolean,
    /** Star rating of this fight (1..3) and the stage's best rating before it. */
    val fightStars: Int = 1,
    val starsBefore: Int = 0,
    /** Star milestones reached by this fight; their rewards are [milestoneGold] and [milestoneCards] (not in [gold] / [drops]). */
    val milestones: List<Milestone> = emptyList(),
    val milestoneGold: Long = 0,
    val milestoneCards: List<OwnedCard> = emptyList(),
)

class GameException(msg: String) : Exception(msg)

/** All game rules operating on the immutable [GameState]. */
object Rules {
    fun newGame(starter: CardType): GameState {
        require(starter.hero)
        val card = OwnedCard(1, starter, Rarity.UNCOMMON)
        return GameState(started = true, gold = 50, cards = listOf(card), party = listOf(1L, null, null, null, null, null), nextId = 2)
    }

    // ---------------------------------------------------------------- XP

    /** Highest level a card can currently reach (food cards stay at level 1). */
    fun levelCap(s: GameState, c: OwnedCard) = if (c.type.food) 1 else minOf(c.rarity.maxLevel, s.playerLevel)

    /**
     * Adds XP and levels the card up as far as allowed. When the card hits a cap, XP fills
     * up to the next level requirement and the rest is lost (so it is ready to level once the cap rises).
     */
    fun addCardXp(s: GameState, c: OwnedCard, amount: Long): OwnedCard {
        if (c.type.food) return c
        var level = c.level
        var xp = c.xp + amount
        val cap = levelCap(s, c)
        while (level < cap && xp >= Balance.cardXpToNext(level, c.type.hero)) {
            xp -= Balance.cardXpToNext(level, c.type.hero)
            level++
        }
        val limit = if (level >= c.rarity.maxLevel) 0 else Balance.cardXpToNext(level, c.type.hero)
        val kept = minOf(xp, limit)
        val gained = amount - (xp - kept)
        return c.copy(level = level, xp = kept)
    }

    /** Re-applies stored XP to all cards (after the player level rose). */
    private fun relevelAll(s: GameState) = s.copy(cards = s.cards.map { addCardXp(s, it, 0) })

    private fun addPlayerXp(s: GameState, amount: Long): GameState {
        var level = s.playerLevel
        var xp = s.playerXp + amount
        while (xp >= Balance.playerXpToNext(level)) {
            xp -= Balance.playerXpToNext(level)
            level++
        }
        return relevelAll(s.copy(playerLevel = level, playerXp = xp))
    }

    // ---------------------------------------------------------------- party

    /** Only one hero of each class can be in the party (monsters can be doubled). */
    fun heroTwin(s: GameState, card: OwnedCard, ignoring: Long? = null): OwnedCard? =
        if (!card.type.hero) null
        else s.partyCards.firstOrNull { it.id != card.id && it.id != ignoring && it.type == card.type }

    fun placeInParty(s: GameState, cardId: Long, slot: Int): GameState {
        val card = s.card(cardId) ?: throw GameException("Card not found.")
        if (card.type.food) throw GameException("Food cards can't fight – feed them to another card!")
        if (heroTwin(s, card, ignoring = s.party[slot]) != null) throw GameException("There can be only one ${card.type.title} in the party.")
        val party = s.party.toMutableList()
        val oldSlot = party.indexOf(cardId)
        val occupant = party[slot]
        if (oldSlot >= 0) {
            party[oldSlot] = occupant // swap
        } else if (occupant == null && party.count { it != null } >= Balance.MAX_PARTY) {
            throw GameException("The party is full (max ${Balance.MAX_PARTY} cards).")
        }
        party[slot] = cardId
        return s.copy(party = party)
    }

    fun addToParty(s: GameState, cardId: Long): GameState {
        if (s.inParty(cardId)) return s
        // prefer the front row
        val free = s.party.indexOfFirst { it == null }
        if (free < 0 || s.party.count { it != null } >= Balance.MAX_PARTY) throw GameException("The party is full (max ${Balance.MAX_PARTY} cards).")
        return placeInParty(s, cardId, free)
    }

    fun removeFromParty(s: GameState, cardId: Long) = s.copy(party = s.party.map { if (it == cardId) null else it })

    // ---------------------------------------------------------------- fights

    /** Unique fighter ids (atomic: the balance simulation plays several games at once). */
    private val uidCounter = AtomicInt(0)

    fun fighterFor(c: OwnedCard, slot: Int) = Fighter(
        uidCounter.incrementAndFetch(), Side.PLAYER, c.type, c.rarity, c.evo, c.level, c.hp, c.atk,
        if (c.hasSkill) c.type.skill else null, c.skillPower, listOf(slot), cardId = c.id, skillLevel = c.skillLevel,
        skillCooldown = c.skillCooldown, passiveLevel = if (c.type.hero) c.passiveLevel else 0,
    )

    /**
     * Regular monsters fight with skill level 1, bosses always with the max skill level (including its bonus).
     * [contest] = a contest monster: no gold, diamonds by its strength instead.
     */
    fun monsterFighter(m: MonsterSpec, contest: Boolean = false): Fighter {
        val hp = maxOf(1, (Balance.hp(m.type, m.level, m.rarity, m.evo, m.slots.size) * m.hpFactor).roundToInt())
        val atk = maxOf(1, (Balance.atk(m.type, m.level, m.rarity, m.evo) * m.atkFactor).roundToInt())
        return Fighter(
            uidCounter.incrementAndFetch(), Side.ENEMY, m.type, m.rarity, m.evo, m.level, hp, atk,
            if (m.rarity != Rarity.COMMON) m.type.skill else null,
            m.type.skill.power(m.rarity, m.skillLevel), m.slots, dropChance = m.dropChance,
            gold = if (contest) 0 else m.gold, skillLevel = m.skillLevel,
            diamonds = if (contest) Contest.diamonds(hp, atk) else 0,
        )
    }

    private fun checkParty(s: GameState) {
        if (s.partyCards.isEmpty()) throw GameException("Put at least one card into your party first.")
        s.partyCards.firstOrNull { heroTwin(s, it) != null }?.let { throw GameException("There can be only one ${it.type.title} in the party.") }
    }

    fun startFight(s: GameState, stage: StageDef, random: Random = Random.Default): Pair<GameState, Battle> {
        if (!s.isUnlocked(stage)) throw GameException("This stage is still locked.")
        checkParty(s)
        if (s.gold < stage.entryFee) throw GameException("You need ${stage.entryFee} gold to enter.")
        checkInventorySpace(s)
        val fighters = s.party.mapIndexedNotNull { slot, id -> s.card(id)?.let { fighterFor(it, slot) } }
        val battle = Battle(fighters, stage.waves.map { w -> { w.map(::monsterFighter) } }, random)
        battle.skillMode = s.skillMode
        return s.copy(gold = s.gold - stage.entryFee) to battle
    }

    /** The first win of a stage doubles its gold. */
    fun goldMultiplier(s: GameState, stage: StageDef) = if (stage.key in s.cleared) 1 else 2

    fun finishFight(s: GameState, stage: StageDef, battle: Battle): Pair<GameState, FightRewards?> {
        check(battle.finished)
        if (!battle.party.any { it.alive } || battle.enemies.any { it.alive }) {
            return s.copy(losses = s.losses + 1) to null
        }
        val firstClear = stage.key !in s.cleared
        // gold of the beaten monsters that didn't drop their card, doubled on the first win
        val gold = battle.gold * goldMultiplier(s, stage)
        // stars: the rating of this one fight; the stage keeps its best
        val fightStars = starsFor(battle)
        val starsBefore = s.stars(stage.key)
        var st = s.copy(
            gold = s.gold + gold, cleared = s.cleared + stage.key, wins = s.wins + 1,
            bestStars = s.bestStars + (stage.key to maxOf(starsBefore, fightStars)),
        )
        val before = st.playerLevel
        st = addPlayerXp(st, stage.playerXp)
        // card XP for every party member (also the knocked out ones)
        val partyIds = battle.party.mapNotNull { it.cardId }.toSet()
        val levels = mutableMapOf<Long, Pair<Int, Int>>()
        st = st.copy(cards = st.cards.map { c ->
            if (c.id in partyIds) addCardXp(st, c, stage.cardXp).also { levels[c.id] = c.level to it.level } else c
        })
        // loot (rolled during the battle, when each monster was beaten)
        var nextId = st.nextId
        val drops = battle.loot.map { lootCard(it, st.playerLevel).copy(id = nextId++) }
        st = st.copy(cards = st.cards + drops, nextId = nextId)
        // star milestones of the dungeon
        val dungeon = Dungeons.all.first { it.id == stage.dungeon }
        val stars = st.dungeonStars(dungeon)
        val reached = dungeon.milestones.filter { it.stars <= stars && milestoneKey(dungeon, it) !in st.claimedMilestones }
        val mGold = reached.sumOf { it.gold }
        val mCards = reached.flatMap { m -> m.cards.flatMap { r -> List(r.count) {
            OwnedCard(nextId++, r.type, r.rarity, r.evo, level = r.level)
        } } }
        st = st.copy(
            gold = st.gold + mGold, cards = st.cards + mCards, nextId = nextId,
            claimedMilestones = st.claimedMilestones + reached.map { milestoneKey(dungeon, it) },
        )
        return st to FightRewards(gold, stage.playerXp, stage.cardXp, drops, before, st.playerLevel, levels, firstClear, fightStars, starsBefore, reached, mGold, mCards)
    }

    /** Star rating of a won fight: 1 + (no card knocked out) + (no skill used). */
    fun starsFor(battle: Battle) = 1 + (if (battle.noCardLost) 1 else 0) + (if (!battle.playerUsedSkill) 1 else 0)

    fun milestoneKey(d: DungeonDef, m: Milestone) = "${d.id}:${m.stars}"

    /**
     * The card a beaten monster leaves (id 0): its own card at [Balance.LOOT_LEVEL_SHARE] of its level, or - for a boss - a level 1
     * food card of the boss's rarity and evo stage. A card's value depends only on its level, rarity and evo, so a looted card is worth exactly as
     * much (when fed) as the same card raised to that level by the player.
     */
    fun lootCard(f: Fighter, playerLevel: Int): OwnedCard =
        if (f.type.boss) OwnedCard(0, CardType.FOOD, f.rarity, f.evo)
        else Balance.lootLevel(f.level, f.rarity, playerLevel).let { level ->
            OwnedCard(0, f.type, f.rarity, f.evo, level = level)
        }

    // ---------------------------------------------------------------- contest

    /**
     * Brings the contest up to [today] (local epoch day): opens it at player level [Contest.UNLOCK_LEVEL] with
     * [Contest.START_TICKETS] tickets, then adds [Contest.TICKETS_PER_DAY] for every midnight passed since [GameState.lastTicketDay].
     * Returns the new state and the number of tickets added (the start tickets don't count).
     */
    fun collectTickets(s: GameState, today: Long): Pair<GameState, Int> {
        if (!s.contestUnlocked) {
            if (s.playerLevel < Contest.UNLOCK_LEVEL) return s to 0
            return s.copy(contestUnlocked = true, contestTickets = s.contestTickets + Contest.START_TICKETS, lastTicketDay = today) to 0
        }
        val last = s.lastTicketDay ?: return s.copy(lastTicketDay = today) to 0
        val days = today - last
        if (days <= 0) return s to 0
        val added = (days * Contest.TICKETS_PER_DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return s.copy(contestTickets = s.contestTickets + added, lastTicketDay = today) to added
    }

    /** Uses a ticket and starts an endless contest fight (new random waves until the party falls). */
    fun startContest(s: GameState, random: Random = Random.Default): Pair<GameState, Battle> {
        if (!s.contestUnlocked) throw GameException("The Contest opens at player level ${Contest.UNLOCK_LEVEL}.")
        checkParty(s)
        if (s.contestTickets <= 0) throw GameException("No entry ticket left – a new one comes every day.")
        val fighters = s.party.mapIndexedNotNull { slot, id -> s.card(id)?.let { fighterFor(it, slot) } }
        val battle = Battle(fighters, { w -> Contest.wave(w, random).map { monsterFighter(it, contest = true) } }, null, random)
        battle.skillMode = s.skillMode
        return s.copy(contestTickets = s.contestTickets - 1) to battle
    }

    /** Ends a contest (the party fell or ran away): the diamonds are kept, the best wave count is updated. */
    fun finishContest(s: GameState, battle: Battle): Pair<GameState, ContestResult> {
        val waves = battle.wavesCleared
        val result = ContestResult(waves, battle.diamonds, waves > s.contestBestWaves)
        return s.copy(diamonds = s.diamonds + battle.diamonds, contestBestWaves = maxOf(s.contestBestWaves, waves)) to result
    }

    /** Why the passive of [c] can't be raised now (null = it can). */
    fun passiveProblem(s: GameState, c: OwnedCard): String? {
        if (!c.type.hero) return "Only hero cards can learn a passive ability."
        if (c.passiveLevel >= Passive.MAX_LEVEL) return "This passive is at its max level."
        val cost = Passive.cost(c.passiveLevel)
        if (s.diamonds < cost) return "You need ${cost} diamonds."
        return null
    }

    /** Raises the passive ability of a hero card by one level for [Passive.cost] diamonds. */
    fun upgradePassive(s: GameState, cardId: Long): GameState {
        val c = s.card(cardId) ?: throw GameException("Card not found.")
        passiveProblem(s, c)?.let { throw GameException(it) }
        return s.copy(
            diamonds = s.diamonds - Passive.cost(c.passiveLevel),
            cards = s.cards.map { if (it.id == cardId) it.copy(passiveLevel = it.passiveLevel + 1) else it },
        )
    }

    /** Diamond shop: [Contest.GOLD_PACK_GOLD] gold for [Contest.GOLD_PACK_PRICE] diamonds. */
    fun buyGoldWithDiamonds(s: GameState): GameState {
        if (s.diamonds < Contest.GOLD_PACK_PRICE) throw GameException("You need ${Contest.GOLD_PACK_PRICE} diamonds.")
        return s.copy(diamonds = s.diamonds - Contest.GOLD_PACK_PRICE, gold = s.gold + Contest.GOLD_PACK_GOLD)
    }

    /** Diamond shop: a legendary food card for [Contest.LEGENDARY_CAKE_PRICE] diamonds. */
    fun buyCakeWithDiamonds(s: GameState): GameState {
        if (s.diamonds < Contest.LEGENDARY_CAKE_PRICE) throw GameException("You need ${Contest.LEGENDARY_CAKE_PRICE} diamonds.")
        checkInventorySpace(s)
        val card = OwnedCard(s.nextId, CardType.FOOD, Rarity.LEGENDARY)
        return s.copy(diamonds = s.diamonds - Contest.LEGENDARY_CAKE_PRICE, cards = s.cards + card, nextId = s.nextId + 1)
    }

    // ---------------------------------------------------------------- feeding

    fun canBeFood(s: GameState, target: OwnedCard, food: OwnedCard) =
        food.id != target.id && !s.inParty(food.id)

    /** True if eating [food] gives [target] a chance to raise its skill level (uncommon+ card with the same skill). */
    fun sharesSkill(target: OwnedCard, food: OwnedCard) =
        target.hasSkill && food.hasSkill && food.type.skill == target.type.skill

    /** The card after eating [food] (XP only; the skill level-up is a separate roll, see [skillUpChance]). */
    fun feedPreview(s: GameState, target: OwnedCard, food: List<OwnedCard>): OwnedCard =
        addCardXp(s, target, food.sumOf { feedXp(target, it) })

    /** XP [target] gets from eating [food]: its sacrifice value, +[Balance.SAME_SKILL_XP_BONUS] if it shares the skill. */
    fun feedXp(target: OwnedCard, food: OwnedCard): Long =
        if (sharesSkill(target, food)) (food.sacrificeValue * (1 + Balance.SAME_SKILL_XP_BONUS)).toLong() else food.sacrificeValue

    /**
     * Chance that eating [food] at once raises the skill level of [target] by 1. Every eaten uncommon-or-better card
     * with the same skill adds its rarity's chance (10/30/50/70 %); the sum is capped at 100 %.
     */
    fun skillUpChance(target: OwnedCard, food: List<OwnedCard>): Double {
        if (!target.hasSkill || target.skillLevel >= Balance.MAX_SKILL_LEVEL) return 0.0
        return food.filter { it.hasSkill && it.type.skill == target.type.skill }
            .sumOf { Balance.SKILL_UP_CHANCE[it.rarity.ordinal] }.coerceAtMost(1.0)
    }

    fun feed(s: GameState, targetId: Long, foodIds: List<Long>, random: Random = Random.Default): GameState {
        val target = s.card(targetId) ?: throw GameException("Card not found.")
        val food = foodIds.distinct().map { s.card(it) ?: throw GameException("Card not found.") }
        if (food.isEmpty()) throw GameException("Choose some cards to feed.")
        if (target.type.food) throw GameException("Food cards can't eat other cards.")
        food.forEach { if (!canBeFood(s, target, it)) throw GameException("${it.type.title} can't be fed (is it in your party?).") }
        var fed = feedPreview(s, target, food)
        val chance = skillUpChance(target, food)
        if (chance > 0 && random.nextDouble() < chance) fed = fed.copy(skillLevel = fed.skillLevel + 1)
        val ids = food.map { it.id }.toSet()
        return s.copy(cards = s.cards.filter { it.id !in ids }.map { if (it.id == targetId) fed else it })
    }

    // ---------------------------------------------------------------- evolving

    sealed interface EvolveCheck {
        data class Ok(val result: OwnedCard, val gold: Long, val materials: Int, val materialRarity: Rarity) : EvolveCheck
        data class No(val reason: String) : EvolveCheck
    }

    fun evolveCheck(s: GameState, c: OwnedCard): EvolveCheck {
        if (c.type.food) return EvolveCheck.No("Food cards can't evolve – feed them to another card!")
        val result = when {
            !c.atMaxEvo -> c.copy(evo = c.evo + 1, level = 1, xp = 0)
            c.type.hero && c.rarity.next != null -> c.copy(rarity = c.rarity.next!!, evo = 0, level = 1, xp = 0)
            c.type.hero -> return EvolveCheck.No("This card is at its very best already!")
            else -> return EvolveCheck.No("Monster cards can't go above ${c.stageLabel}.")
        }
        if (!c.atRarityMaxLevel) return EvolveCheck.No("Reach level ${c.rarity.maxLevel} first.")
        return EvolveCheck.Ok(result, Balance.evolveGoldCost(c.rarity, c.evo), Balance.evolveMaterials(c.evo), c.rarity)
    }

    fun evolveMaterialCandidates(s: GameState, c: OwnedCard) =
        s.cards.filter { it.id != c.id && it.type == c.type && it.rarity == c.rarity && !s.inParty(it.id) }
            .sortedWith(compareBy({ it.evo }, { it.level }, { it.xp }))

    fun evolve(s: GameState, cardId: Long, materialIds: List<Long>): GameState {
        val c = s.card(cardId) ?: throw GameException("Card not found.")
        val check = evolveCheck(s, c)
        if (check is EvolveCheck.No) throw GameException(check.reason)
        check as EvolveCheck.Ok
        val candidates = evolveMaterialCandidates(s, c).map { it.id }.toSet()
        val mats = materialIds.distinct()
        if (mats.size != check.materials || !candidates.containsAll(mats))
            throw GameException("You need ${check.materials}× ${c.rarity.label} ${c.type.title} (not in party).")
        if (s.gold < check.gold) throw GameException("You need ${check.gold} gold.")
        return s.copy(
            gold = s.gold - check.gold,
            cards = s.cards.filter { it.id !in mats }.map { if (it.id == cardId) check.result else it },
        )
    }

    // ---------------------------------------------------------------- meditation

    /** Hero cards of [Balance.MEDITATION_RARITY] or better meditate and can get their cooldown shortened. */
    fun canMeditate(c: OwnedCard) = c.type.hero && c.rarity >= Balance.MEDITATION_RARITY

    /**
     * Brings meditation up to [today] (local epoch day): opens the Meditation tab once a rare-or-better hero is owned
     * (starting the count that day), then adds the nightly clues of the current meditators for every midnight passed since
     * [GameState.lastClueDay]. Returns the new state and the clues added per class (empty if none).
     */
    fun collectClues(s: GameState, today: Long): Pair<GameState, Map<CardType, Long>> {
        if (!s.meditationUnlocked) {
            if (s.cards.none(::canMeditate)) return s to emptyMap()
            return s.copy(meditationUnlocked = true, lastClueDay = today) to emptyMap()
        }
        val last = s.lastClueDay ?: return s.copy(lastClueDay = today) to emptyMap()
        val days = today - last
        if (days <= 0) return s to emptyMap() // same day (or the clock went back): nothing yet
        val gained = s.cluesPerDay.mapValues { (_, n) -> n * days }.filterValues { it > 0 }
        val clues = CardType.heroes.associateWith { s.clues(it) + (gained[it] ?: 0L) }.filterValues { it > 0 }
        return s.copy(clues = clues, lastClueDay = today) to gained
    }

    /** Why the cooldown of [c] can't be shortened now (null = it can). */
    fun cooldownCutProblem(s: GameState, c: OwnedCard): String? {
        if (!canMeditate(c)) return "Only ${Balance.MEDITATION_RARITY.label} or better hero cards can shorten their cooldown."
        if (c.cooldownCuts >= Balance.MAX_COOLDOWN_CUTS) return "${c.type.skill.title} can already be used every turn!"
        val cost = Balance.cooldownCutCost(c.cooldownCuts)
        val missing = CardType.heroes.filter { s.clues(it) < cost }
        if (missing.isNotEmpty()) return "You need $cost clues of every class (missing: ${missing.joinToString { it.title }})."
        return null
    }

    /** Shortens the skill cooldown of the card by 1 for [Balance.cooldownCutCost] clues of every hero class. */
    fun cutCooldown(s: GameState, cardId: Long): GameState {
        val c = s.card(cardId) ?: throw GameException("Card not found.")
        cooldownCutProblem(s, c)?.let { throw GameException(it) }
        val cost = Balance.cooldownCutCost(c.cooldownCuts)
        return s.copy(
            clues = CardType.heroes.associateWith { s.clues(it) - cost }.filterValues { it > 0 },
            cards = s.cards.map { if (it.id == cardId) it.copy(cooldownCuts = it.cooldownCuts + 1) else it },
        )
    }

    /** A better card of the same hero than [c] (higher rarity, evolution or level), if the player has one. */
    fun strongerTwin(s: GameState, c: OwnedCard): OwnedCard? =
        s.cards.filter { it.id != c.id && it.type == c.type && it.rank > c.rank }.maxByOrNull { it.rank }

    // ---------------------------------------------------------------- inventory

    private fun checkInventorySpace(s: GameState) {
        if (s.inventoryFull) throw GameException(
            "Your card inventory is full (${s.cards.size} / ${s.inventorySize}). Sell or feed some cards, or buy more slots in the Cards tab."
        )
    }

    /** Buys [Balance.INVENTORY_STEP] more card slots; every upgrade costs twice as much as the one before. */
    fun upgradeInventory(s: GameState): GameState {
        val price = s.inventoryUpgradeCost
        if (s.gold < price) throw GameException("You need $price gold.")
        return s.copy(gold = s.gold - price, inventoryUpgrades = s.inventoryUpgrades + 1)
    }

    // ---------------------------------------------------------------- buying

    /**
     * Buys a level 1 card of the hero [type]: green ([Balance.HERO_BUY_RARITY]) or a higher [rarity] the player has already
     * evolved that hero to ([GameState.heroShopRarities]). Only green purchases count in [GameState.heroesBought].
     */
    fun buyHero(s: GameState, type: CardType, rarity: Rarity = Balance.HERO_BUY_RARITY): GameState {
        if (!type.hero) throw GameException("Only hero cards can be bought.")
        if (rarity !in s.heroShopRarities(type)) throw GameException("Evolve a ${type.title} to ${rarity.label} first.")
        val price = s.heroPrice(rarity)
        if (s.gold < price) throw GameException("You need $price gold.")
        checkInventorySpace(s)
        val card = OwnedCard(s.nextId, type, rarity)
        val green = rarity == Balance.HERO_BUY_RARITY
        return s.copy(gold = s.gold - price, cards = s.cards + card, nextId = s.nextId + 1, heroesBought = s.heroesBought + if (green) 1 else 0)
    }

    /** Buys a level 1 food card of [rarity] for [Balance.foodPrice]. */
    fun buyFood(s: GameState, rarity: Rarity): GameState {
        val price = Balance.foodPrice(rarity)
        if (s.gold < price) throw GameException("You need $price gold.")
        checkInventorySpace(s)
        val card = OwnedCard(s.nextId, CardType.FOOD, rarity)
        return s.copy(gold = s.gold - price, cards = s.cards + card, nextId = s.nextId + 1)
    }

    // ---------------------------------------------------------------- selling

    fun sell(s: GameState, ids: List<Long>): GameState {
        val cards = ids.distinct().map { s.card(it) ?: throw GameException("Card not found.") }
        cards.forEach {
            if (it.type.hero) throw GameException("Hero cards can't be sold.")
            if (it.type.food) throw GameException("Food cards can't be sold.")
            if (s.inParty(it.id)) throw GameException("Remove ${it.type.title} from the party first.")
        }
        val set = cards.map { it.id }.toSet()
        return s.copy(gold = s.gold + cards.sumOf { it.sellPrice }, cards = s.cards.filter { it.id !in set })
    }
}
