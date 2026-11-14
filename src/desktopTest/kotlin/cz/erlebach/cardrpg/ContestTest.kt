package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.BattleEvent
import cz.erlebach.cardrpg.battle.Fighter
import cz.erlebach.cardrpg.battle.Phase
import cz.erlebach.cardrpg.battle.Side
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.Contest
import cz.erlebach.cardrpg.content.Role
import cz.erlebach.cardrpg.game.GameException
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity
import cz.erlebach.cardrpg.model.Skill
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContestTest {
    private var uid = 1000
    private fun hero(
        type: CardType, slot: Int, passive: Int = 0, hp: Int = 1000, atk: Int = 100, skill: Skill? = null, power: Int = 0,
    ) = Fighter(++uid, Side.PLAYER, type, Rarity.RARE, 0, 1, hp, atk, skill, power, listOf(slot), passiveLevel = passive)
    private fun monster(slot: Int, hp: Int = 100_000, atk: Int = 10, type: CardType = CardType.BAT, skill: Skill? = null, power: Int = 0) =
        Fighter(++uid, Side.ENEMY, type, Rarity.COMMON, 0, 1, hp, atk, skill, power, listOf(slot))

    private fun state(vararg cards: OwnedCard, level: Int = Contest.UNLOCK_LEVEL) = GameState(
        started = true, playerLevel = level, cards = cards.toList(), party = listOf(cards.first().id, null, null, null, null, null),
        nextId = 1000,
    )

    // ---------------------------------------------------------------- tickets

    @Test
    fun `the contest opens at level 50 with 3 tickets and adds one per midnight`() {
        val card = OwnedCard(1, CardType.WARRIOR, Rarity.RARE)
        val low = state(card, level = Contest.UNLOCK_LEVEL - 1)
        assertFalse(Rules.collectTickets(low, 100).first.contestUnlocked)
        val (open, _) = Rules.collectTickets(state(card), 100)
        assertTrue(open.contestUnlocked)
        assertEquals(Contest.START_TICKETS, open.contestTickets)
        assertEquals(open, Rules.collectTickets(open, 100).first) // same day: nothing
        val (later, added) = Rules.collectTickets(open, 102)
        assertEquals(2, added)
        assertEquals(Contest.START_TICKETS + 2, later.contestTickets)
    }

    @Test
    fun `entering costs a ticket and the diamonds are kept when the party falls`() {
        val card = OwnedCard(1, CardType.WARRIOR, Rarity.RARE)
        val s = Rules.collectTickets(state(card), 0).first
        val (s2, battle) = Rules.startContest(s, Random(1))
        assertEquals(Contest.START_TICKETS - 1, s2.contestTickets)
        assertEquals(null, battle.waveCount)
        assertEquals(Phase.LOST, battle.runToEnd()) // a level 1 rare warrior against stage 7-1 monsters
        val (s3, r) = Rules.finishContest(s2, battle)
        assertEquals(s2.diamonds + battle.diamonds, s3.diamonds)
        assertEquals(r.waves, s3.contestBestWaves)
        assertFailsWith<GameException> { Rules.startContest(s3.copy(contestTickets = 0)) }
        assertFailsWith<GameException> { Rules.startContest(state(card).copy(contestTickets = 5)) } // not unlocked
    }

    @Test
    fun `waves keep coming, get stronger and pay diamonds instead of gold`() {
        // a party far too strong to lose quickly: it beats many waves, collecting diamonds
        val party = listOf(hero(CardType.WARRIOR, 1, hp = 50_000_000, atk = 5_000_000))
        val b = Battle(party, { w -> Contest.wave(w, Random(w)).map { Rules.monsterFighter(it, contest = true) } }, null, Random(1))
        b.skillMode = SkillMode.NEVER
        val diamondsPerWave = mutableListOf<Long>()
        var last = 0L
        while (b.wavesCleared < 12) {
            val ev = b.advance()
            if (ev.any { it is BattleEvent.WaveCleared }) { diamondsPerWave += b.diamonds - last; last = b.diamonds }
            assertTrue(ev.none { it is BattleEvent.Coins || it is BattleEvent.Looted })
        }
        assertEquals(0L, b.gold)
        assertTrue(b.loot.isEmpty())
        assertTrue(diamondsPerWave.all { it > 0 })
        // stronger waves pay more (averaged, as the formations differ in size)
        assertTrue(diamondsPerWave.takeLast(4).sum() > diamondsPerWave.take(4).sum())
        assertTrue(Contest.wave(15, Random(1)).all { it.slots.size == 1 })
    }

    @Test
    fun `formations put tanks, off-tanks and melee in front, ranged and healers behind`() {
        for (f in Contest.formations) for ((slot, role) in f) {
            if (role == Role.RANGED || role == Role.HEALER) assertTrue(slot >= 3, "$role at $slot")
            else assertTrue(slot < 3, "$role at $slot")
        }
        repeat(50) { i ->
            for (m in Contest.wave(i % 10, Random(i))) {
                val role = Role.of(m.type)
                assertFalse(m.type.boss || m.type.hero || m.type.food)
                assertEquals(role == Role.RANGED || role == Role.HEALER, m.slots.single() >= 3)
            }
        }
        // the first wave is about as strong as stage 7-1, every next one 3 stages harder
        assertEquals("7-1", Contest.stageLabel(0))
        assertEquals("7-4", Contest.stageLabel(1))
    }

    // ---------------------------------------------------------------- buying passives

    @Test
    fun `passives cost 100, 1000, 10000 diamonds and only heroes learn them`() {
        assertEquals(listOf(100L, 1000L, 10_000L), (0..2).map(Passive::cost))
        val rogue = OwnedCard(1, CardType.ROGUE, Rarity.UNCOMMON)
        val fox = OwnedCard(2, CardType.FOX, Rarity.RARE)
        val s = state(rogue, fox).copy(diamonds = 1150)
        val s1 = Rules.upgradePassive(s, 1)
        assertEquals(1, s1.card(1)!!.passiveLevel)
        assertEquals(Passive.EVASION, s1.card(1)!!.passive)
        val s2 = Rules.upgradePassive(s1, 1)
        assertEquals(2, s2.card(1)!!.passiveLevel)
        assertEquals(50L, s2.diamonds)
        assertFailsWith<GameException> { Rules.upgradePassive(s2, 1) } // 10 000 needed
        assertFailsWith<GameException> { Rules.upgradePassive(s, 2) } // a monster
        // evolving keeps it
        val maxed = s2.copy(gold = 1_000_000, cards = listOf(s2.card(1)!!.copy(level = 30), OwnedCard(3, CardType.ROGUE, Rarity.UNCOMMON)))
        assertEquals(2, Rules.evolve(maxed, 1, listOf(3)).card(1)!!.passiveLevel)
    }

    @Test
    fun `the diamond shop sells gold and legendary cakes`() {
        val s = state(OwnedCard(1, CardType.WARRIOR, Rarity.RARE)).copy(diamonds = Contest.GOLD_PACK_PRICE + Contest.LEGENDARY_CAKE_PRICE)
        val s1 = Rules.buyGoldWithDiamonds(s)
        assertEquals(Contest.GOLD_PACK_GOLD, s1.gold)
        val s2 = Rules.buyCakeWithDiamonds(s1)
        assertEquals(0L, s2.diamonds)
        assertTrue(s2.cards.any { it.type == CardType.FOOD && it.rarity == Rarity.LEGENDARY })
        assertFailsWith<GameException> { Rules.buyGoldWithDiamonds(s2) }
    }

    @Test
    fun `blessing and arcane power raise the card's stats`() {
        val priest = OwnedCard(1, CardType.PRIEST, Rarity.RARE, level = 20)
        val mage = OwnedCard(2, CardType.MAGE, Rarity.RARE, level = 20)
        assertEquals(Math.round(priest.hp * 1.2).toInt(), priest.copy(passiveLevel = 2).hp)
        assertEquals(priest.atk, priest.copy(passiveLevel = 2).atk)
        assertEquals(Math.round(mage.atk * 1.1).toInt(), mage.copy(passiveLevel = 1).atk)
        assertEquals(mage.hp, mage.copy(passiveLevel = 1).hp)
    }

    // ---------------------------------------------------------------- passives in battle

    @Test
    fun `evasion dodges hits`() {
        val rogue = hero(CardType.ROGUE, 1, passive = Passive.MAX_LEVEL) // 100 % at level 10
        val b = Battle(listOf(rogue), listOf { listOf(monster(1)) })
        repeat(20) { b.advance() }
        assertEquals(rogue.maxHp, rogue.hp)
        val plain = hero(CardType.ROGUE, 1)
        val b2 = Battle(listOf(plain), listOf { listOf(monster(1)) })
        repeat(20) { b2.advance() }
        assertTrue(plain.hp < plain.maxHp)
    }

    @Test
    fun `keen eye crits for double damage`() {
        assertEquals(listOf(0.1, 0.2, 0.3), (1..3).map { Math.round(Passive.KEEN_EYE.critChance(it) * 100) / 100.0 })
        val hunter = hero(CardType.HUNTER, 1, passive = Passive.MAX_LEVEL, atk = 100) // 10 × 10 % = 100 %
        val e = monster(1, atk = 0)
        val ev = Battle(listOf(hunter), listOf { listOf(e) }).let { b -> (1..2).flatMap { b.advance() } }
        val hit = ev.filterIsInstance<BattleEvent.Damage>().single()
        assertEquals(200, hit.amount)
        assertTrue(hit.crit)
    }

    @Test
    fun `iron will resists stuns and cuts damage`() {
        val warrior = hero(CardType.WARRIOR, 1, passive = 4) // 120 % resist (capped), 20 % less damage
        val shooter = monster(4, atk = 100, skill = Skill.AIMED_SHOT, power = 100)
        val b = Battle(listOf(warrior), listOf { listOf(shooter) })
        val ev = (1..4).flatMap { b.advance() }
        assertFalse(warrior.stunned)
        assertTrue(ev.any { it is BattleEvent.Resisted && it.uid == warrior.uid })
        assertEquals(80, ev.filterIsInstance<BattleEvent.Damage>().first { it.target == warrior.uid }.amount)
    }

    @Test
    fun `rage hits harder the more hp is missing`() {
        val berserker = hero(CardType.BERSERKER, 1, passive = 2, atk = 100) // +50 % at 0 hp
        berserker.hp = berserker.maxHp / 2 // half: +25 %
        val ev = Battle(listOf(berserker), listOf { listOf(monster(1, atk = 0)) }).let { b -> (1..2).flatMap { b.advance() } }
        assertEquals(125, ev.filterIsInstance<BattleEvent.Damage>().single().amount)
    }

    @Test
    fun `holy might heals more and hits bosses harder`() {
        val paladin = hero(CardType.PALADIN, 0, passive = 1, atk = 100, skill = Skill.FLASH_HEAL, power = 100)
        val friend = hero(CardType.WARRIOR, 1).also { it.hp = 500 }
        val ev = Battle(listOf(paladin, friend), listOf { listOf(monster(1, atk = 0)) }).let { b -> (1..2).flatMap { b.advance() } }
        assertEquals(120, ev.filterIsInstance<BattleEvent.Heal>().single().amount)
        val pal2 = hero(CardType.PALADIN, 0, passive = 1, atk = 100)
        val boss = Fighter(++uid, Side.ENEMY, CardType.OAK, Rarity.COMMON, 0, 1, 100_000, 0, null, 0, listOf(0))
        val ev2 = Battle(listOf(pal2), listOf { listOf(boss) }).let { b -> (1..2).flatMap { b.advance() } }
        assertEquals(120, ev2.filterIsInstance<BattleEvent.Damage>().single().amount)
    }

    @Test
    fun `blood pact shares the drained life with every other friend`() {
        val warlock = hero(CardType.WARLOCK, 0, passive = 2, atk = 100, skill = Skill.SOUL_LEECH, power = 100).also { it.hp = 10 }
        val friend = hero(CardType.WARRIOR, 1).also { it.hp = 10 }
        val b = Battle(listOf(warlock, friend), listOf { listOf(monster(0, atk = 0)) })
        b.skillMode = SkillMode.AUTO
        (1..2).flatMap { b.advance() }
        assertEquals(110, warlock.hp) // 100 % of the 100 damage
        assertEquals(30, friend.hp) // 20 % of it
    }

    @Test
    fun `passives don't cost the no-skills star`() {
        val party = listOf(
            hero(CardType.HUNTER, 0, passive = 3, atk = 1000, skill = Skill.AIMED_SHOT, power = 250),
            hero(CardType.ROGUE, 1, passive = 3, atk = 1000, skill = Skill.SNEAK_ATTACK, power = 400),
        )
        val b = Battle(party, listOf { listOf(monster(1, hp = 5000)) }, Random(3))
        b.skillMode = SkillMode.NEVER
        while (!b.finished) b.advance()
        assertEquals(Phase.WON, b.phase)
        assertFalse(b.playerUsedSkill)
        assertEquals(3, Rules.starsFor(b))
    }
}
