package cz.erlebach.cardrpg

import cz.erlebach.cardrpg.battle.Affliction
import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.BattleEvent
import cz.erlebach.cardrpg.battle.Fighter
import cz.erlebach.cardrpg.battle.Phase
import cz.erlebach.cardrpg.battle.Side
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.CardReward
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.game.GameException
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.BossTrait
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity
import cz.erlebach.cardrpg.model.Skill
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RulesTest {
    private var uid = 0
    private fun f(side: Side, slot: Int, hp: Int = 100, atk: Int = 10, skill: Skill? = null, power: Int = 0, level: Int = 1) =
        Fighter(++uid, side, CardType.BAT, Rarity.COMMON, 0, 1, hp, atk, skill, power, listOf(slot), skillLevel = level)
    private val MAX = Balance.MAX_SKILL_LEVEL

    @Test
    fun `max shield up guards the whole row until the warrior's next turn`() {
        val w = f(Side.PLAYER, 0, atk = 0, skill = Skill.SHIELD_UP, power = 80, level = MAX)
        val mate = f(Side.PLAYER, 1, atk = 0); val back = f(Side.PLAYER, 4, atk = 0)
        val e = f(Side.ENEMY, 1, hp = 10_000, atk = 10)
        val b = Battle(listOf(w, mate, back), listOf { listOf(e) })
        repeat(4) { b.advance() } // wave start, warrior guards, mate attacks, enemy (column 1) hits the mate
        assertEquals(98, mate.hp)
        // a non-max Shield Up protects only the warrior
        val w2 = f(Side.PLAYER, 0, atk = 0, skill = Skill.SHIELD_UP, power = 80, level = MAX - 1)
        val mate2 = f(Side.PLAYER, 1, atk = 0)
        val b2 = Battle(listOf(w2, mate2), listOf { listOf(f(Side.ENEMY, 1, hp = 10_000, atk = 10)) })
        repeat(4) { b2.advance() }
        assertEquals(90, mate2.hp)
    }

    @Test
    fun `max fireball burns every turn, without stacking, and can kill`() {
        val m = f(Side.PLAYER, 0, atk = 10, skill = Skill.FIREBALL, power = 100, level = MAX)
        val e = f(Side.ENEMY, 0, hp = 1000, atk = 0); val weak = f(Side.ENEMY, 1, hp = 13, atk = 0)
        val b = Battle(listOf(m), listOf { listOf(e, weak) })
        val ev = b.advance() + b.advance() // wave start, fireball
        assertEquals(990, e.hp); assertEquals(3, weak.hp)
        assertEquals(5, e.burning)
        assertEquals(2, ev.count { it is BattleEvent.Afflicted })
        val ev2 = b.advance() // e's turn: it burns first
        assertEquals(985, e.hp)
        assertTrue(ev2.any { it is BattleEvent.Damage && it.dot == Affliction.BURNING })
        b.advance() // weak burns to death before it acts
        assertTrue(!weak.alive)
        // a second fireball doesn't add a second burn
        while (m.cooldown > 0) b.advance()
        val hpBefore = e.hp
        b.advance(); b.advance(); b.advance() // e burns, fireball again, e burns (still 5, not 10)
        assertEquals(hpBefore - 5 - 10 - 5, e.hp)
        assertEquals(5, e.burning)
        // a non-max fireball doesn't burn
        val e3 = f(Side.ENEMY, 0, hp = 1000, atk = 0)
        val b3 = Battle(listOf(f(Side.PLAYER, 0, atk = 10, skill = Skill.FIREBALL, power = 100, level = MAX - 1)), listOf { listOf(e3) })
        b3.advance(); b3.advance()
        assertEquals(0, e3.burning)
    }

    @Test
    fun `max sneak attack poisons for 40 percent`() {
        val r = f(Side.PLAYER, 0, atk = 10, skill = Skill.SNEAK_ATTACK, power = 400, level = MAX)
        val e = f(Side.ENEMY, 0, hp = 1000, atk = 0)
        val b = Battle(listOf(r), listOf { listOf(e) })
        b.advance(); b.advance(); b.advance()
        assertEquals(1000 - 40 - 16, e.hp)
        assertEquals(16, e.poison)
    }

    @Test
    fun `max aimed shot splits over the row and stuns everyone hit`() {
        val h = f(Side.PLAYER, 0, atk = 10, skill = Skill.AIMED_SHOT, power = 300, level = MAX)
        val front = f(Side.ENEMY, 0, hp = 1000, atk = 0); val b1 = f(Side.ENEMY, 3, hp = 1000, atk = 0); val b3 = f(Side.ENEMY, 5, hp = 1000, atk = 0)
        val b = Battle(listOf(h), listOf { listOf(front, b1, b3) })
        b.advance(); b.advance()
        assertEquals(listOf(1000, 985, 985), listOf(front.hp, b1.hp, b3.hp))
        assertTrue(b1.stunned && b3.stunned && !front.stunned)
        // alone in its row: the full damage
        val solo = f(Side.ENEMY, 4, hp = 1000, atk = 0)
        val bb = Battle(listOf(f(Side.PLAYER, 0, atk = 10, skill = Skill.AIMED_SHOT, power = 300, level = MAX)), listOf { listOf(f(Side.ENEMY, 0, hp = 1000, atk = 0), solo) })
        bb.advance(); bb.advance()
        assertEquals(970, solo.hp)
    }

    @Test
    fun `max soul leech also hits the enemy behind a front-row target`() {
        val wl = f(Side.PLAYER, 0, hp = 100, atk = 10, skill = Skill.SOUL_LEECH, power = 200, level = MAX).apply { hp = 50 }
        val a1 = f(Side.ENEMY, 0, hp = 1000, atk = 0); val b1 = f(Side.ENEMY, 3, hp = 1000, atk = 0); val b2 = f(Side.ENEMY, 4, hp = 1000, atk = 0)
        val b = Battle(listOf(wl), listOf { listOf(a1, b1, b2) })
        b.advance(); b.advance()
        assertEquals(listOf(980, 980, 1000), listOf(a1.hp, b1.hp, b2.hp))
        assertEquals(90, wl.hp)
        // nobody right behind: only the target
        val c1 = f(Side.ENEMY, 0, hp = 1000, atk = 0); val c2 = f(Side.ENEMY, 4, hp = 1000, atk = 0)
        val bc = Battle(listOf(f(Side.PLAYER, 0, atk = 10, skill = Skill.SOUL_LEECH, power = 200, level = MAX)), listOf { listOf(c1, c2) })
        bc.advance(); bc.advance()
        assertEquals(listOf(980, 1000), listOf(c1.hp, c2.hp))
    }

    @Test
    fun `max flash heal heals the paladin first, then the most wounded`() {
        val friend = f(Side.PLAYER, 0, hp = 100, atk = 0).apply { hp = 40 }
        val pal = f(Side.PLAYER, 1, hp = 100, atk = 10, skill = Skill.FLASH_HEAL, power = 300, level = MAX).apply { hp = 20 }
        val b = Battle(listOf(friend, pal), listOf { listOf(f(Side.ENEMY, 0, hp = 10_000, atk = 0)) })
        while (pal.cooldown == 0) b.advance()
        // +30 itself (50), then the most wounded is the friend (40): +30
        assertEquals(50, pal.hp); assertEquals(70, friend.hp)
        // still the most wounded after healing itself: heals itself twice
        val friend2 = f(Side.PLAYER, 0, hp = 100, atk = 0).apply { hp = 90 }
        val pal2 = f(Side.PLAYER, 1, hp = 100, atk = 10, skill = Skill.FLASH_HEAL, power = 300, level = MAX).apply { hp = 20 }
        val b2 = Battle(listOf(friend2, pal2), listOf { listOf(f(Side.ENEMY, 0, hp = 10_000, atk = 0)) })
        while (pal2.cooldown == 0) b2.advance()
        assertEquals(80, pal2.hp); assertEquals(90, friend2.hp)
    }

    @Test
    fun `max heal removes all debuffs from every friend`() {
        val a = f(Side.PLAYER, 0, hp = 100, atk = 0).apply { burning = 5; poison = 3 }
        val b0 = f(Side.PLAYER, 2, hp = 100, atk = 0).apply { stunned = true }
        val priest = f(Side.PLAYER, 1, hp = 100, atk = 20, skill = Skill.HEAL, power = 50, level = MAX)
        val b = Battle(listOf(a, b0, priest), listOf { listOf(f(Side.ENEMY, 0, hp = 10_000, atk = 0)) })
        b.advance() // wave start
        a.burning = 5; a.poison = 3; b0.stunned = true // (a new wave clears them, so set them again)
        val ev = (1..3).flatMap { b.advance() } // a burns + attacks, enemy, priest heals (nobody wounded but debuffs)
        assertTrue(priest.cooldown > 0)
        assertEquals(0, a.burning); assertEquals(0, a.poison); assertTrue(!b0.stunned)
        assertTrue(ev.any { it is BattleEvent.Cleansed && it.uid == b0.uid })
        // below max: no cleansing
        val c = f(Side.PLAYER, 0, hp = 100, atk = 0)
        val p2 = f(Side.PLAYER, 1, hp = 100, atk = 20, skill = Skill.HEAL, power = 50, level = MAX - 1)
        val b2 = Battle(listOf(c, p2), listOf { listOf(f(Side.ENEMY, 0, hp = 10_000, atk = 0)) })
        b2.advance(); c.poison = 3
        repeat(3) { b2.advance() }
        assertEquals(3, c.poison) // p2 healed the poisoned card, but the poison stays
    }

    @Test
    fun `max whirlwind stuns the row`() {
        val p = f(Side.PLAYER, 0, atk = 10, skill = Skill.WHIRLWIND, power = 100, level = MAX)
        val a1 = f(Side.ENEMY, 0, hp = 1000, atk = 0); val a3 = f(Side.ENEMY, 2, hp = 1000, atk = 0); val b2 = f(Side.ENEMY, 4, hp = 1000, atk = 0)
        play(Battle(listOf(p), listOf { listOf(a1, a3, b2) }))
        assertTrue(a1.stunned && a3.stunned && !b2.stunned)
    }

    @Test
    fun `bosses fight at the max skill level, regular monsters at level 1`() {
        val st = Dungeons.stage("2-5")!!
        val boss = Rules.monsterFighter(st.waves.last().first { it.type.boss })
        assertEquals(MAX, boss.skillLevel)
        assertEquals(boss.skill!!.power(boss.rarity, MAX), boss.skillPower)
        assertEquals(1, Rules.monsterFighter(st.waves.first().first()).skillLevel)
    }

    @Test
    fun `stats - rarity is worth 5 levels and evolution 7_5`() {
        fun eff(l: Int, r: Rarity, e: Int) = Balance.effectiveLevel(l, r, e)
        val ref = eff(25, Rarity.COMMON, 0)
        assertEquals(ref, eff(20, Rarity.UNCOMMON, 0))
        assertEquals(ref, eff(15, Rarity.RARE, 0))
        assertEquals(ref, eff(10, Rarity.EPIC, 0))
        assertEquals(ref, eff(5, Rarity.LEGENDARY, 0))
        assertEquals(eff(20, Rarity.UNCOMMON, 0), eff(25, Rarity.UNCOMMON, 1) - 12.5)
        // one level ~ +10 %
        val a = Balance.hp(CardType.WARRIOR, 10, Rarity.COMMON, 0).toDouble()
        val b = Balance.hp(CardType.WARRIOR, 11, Rarity.COMMON, 0).toDouble()
        assertTrue(abs(b / a - 1.1) < 0.02)
    }

    @Test
    fun `targeting follows the column order and front row first`() {
        val p = f(Side.PLAYER, 2) // A3
        val e1 = f(Side.ENEMY, 0, hp = 5) // A1
        val e2 = f(Side.ENEMY, 3, hp = 5) // B1 (back row)
        val e3 = f(Side.ENEMY, 5, hp = 5) // B3
        val b = Battle(listOf(p), listOf { listOf(e1, e2, e3) })
        b.advance() // wave start
        fun nextPlayerAttack(): BattleEvent.Attack {
            while (true) b.advance().filterIsInstance<BattleEvent.Attack>().firstOrNull { it.attacker == p.uid }?.let { return it }
        }
        assertEquals(listOf(e1.uid), nextPlayerAttack().targets) // A3 -> A3, A2, A1
        // no front row left: next target for A3 is B3
        assertEquals(listOf(e3.uid), nextPlayerAttack().targets)
    }

    @Test
    fun `turn order is A1 player then A1 enemy then A2 player`() {
        val p1 = f(Side.PLAYER, 0, atk = 1); val p2 = f(Side.PLAYER, 1, atk = 1)
        val e1 = f(Side.ENEMY, 0, atk = 1); val e2 = f(Side.ENEMY, 4, atk = 1)
        val b = Battle(listOf(p1, p2), listOf { listOf(e1, e2) })
        b.advance()
        val order = (1..4).flatMap { b.advance() }.filterIsInstance<BattleEvent.TurnStart>().map { it.uid }
        assertEquals(listOf(p1.uid, e1.uid, p2.uid, e2.uid), order)
    }

    @Test
    fun `skill cooldown blocks its rounds and manual mode asks`() {
        val p = f(Side.PLAYER, 0, atk = 1, skill = Skill.SNEAK_ATTACK, power = 400)
        val e = f(Side.ENEMY, 0, hp = 1000, atk = 0)
        val b = Battle(listOf(p), listOf { listOf(e) })
        b.skillMode = SkillMode.ASK
        b.advance()
        val usedRounds = mutableListOf<Int>()
        repeat(40) {
            if (b.phase == Phase.AWAITING_DECISION) { usedRounds += b.round; b.decide(true) } else b.advance()
        }
        val cd = Balance.SKILL_COOLDOWN
        assertEquals(listOf(1, 2 + cd, 3 + 2 * cd), usedRounds.take(3))
    }

    @Test
    fun `never mode uses no skills and does not ask`() {
        val p = f(Side.PLAYER, 0, atk = 1, skill = Skill.SNEAK_ATTACK, power = 400)
        val e = f(Side.ENEMY, 0, hp = 1000, atk = 0)
        val b = Battle(listOf(p), listOf { listOf(e) })
        b.skillMode = SkillMode.NEVER
        val events = (1..40).flatMap { assertTrue(b.phase != Phase.AWAITING_DECISION); b.advance() }
        assertTrue(events.filterIsInstance<BattleEvent.Attack>().filter { it.attacker == p.uid }.all { it.skill == null })
    }

    @Test
    fun `guard blocks damage until own next turn`() {
        val p = f(Side.PLAYER, 0, hp = 100, atk = 1, skill = Skill.SHIELD_UP, power = 80)
        val e = f(Side.ENEMY, 0, hp = 1000, atk = 10)
        val b = Battle(listOf(p), listOf { listOf(e) })
        b.advance(); b.advance() // player guards
        val ev = b.advance()     // enemy hits
        assertEquals(2, ev.filterIsInstance<BattleEvent.Damage>().single().amount)
        b.advance(); val ev2 = b.advance() // player attacks, enemy hits again without guard
        assertEquals(10, ev2.filterIsInstance<BattleEvent.Damage>().single().amount)
    }

    @Test
    fun `1-1 is beatable with every starter`() {
        for (t in CardType.starters) {
            val s = Rules.newGame(t)
            val (_, b) = Rules.startFight(s, Dungeons.stage("1-1")!!)
            assertEquals(Phase.WON, b.runToEnd(), "starter $t")
        }
    }

    @Test
    fun `card level is capped by player level and rarity`() {
        val s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 3)
        val c = Rules.addCardXp(s, s.cards[0], 1_000_000)
        assertEquals(3, c.level)
        assertEquals(c.xpToNext, c.xp) // bar is full, rest is lost
        val s2 = s.copy(playerLevel = 99)
        assertEquals(30, Rules.addCardXp(s2, s2.cards[0], Long.MAX_VALUE / 4).level)
    }

    @Test
    fun `feeding gives 80 percent of the xp of the food's level plus bonus and levels skill`() {
        var s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 50)
        val food = OwnedCard(10, CardType.SNAIL, Rarity.UNCOMMON, level = 5)
        s = s.copy(cards = s.cards + food)
        assertEquals(Math.round(0.8 * Balance.cardXpTotal(5, false)) + Balance.sacrificeBonus(Rarity.UNCOMMON, 0), food.sacrificeValue)
        val fed = Rules.feed(s, 1, listOf(10), alwaysLucky)
        assertEquals(1, fed.cards.size)
        // snail shares Shield Up with warrior: +20 % XP
        assertEquals((food.sacrificeValue * (1 + Balance.SAME_SKILL_XP_BONUS)).toLong(), fed.cards[0].totalXp)
        assertEquals(2, fed.cards[0].skillLevel)
        assertEquals(1, Rules.feed(s, 1, listOf(10), neverLucky).cards[0].skillLevel)
    }

    private val alwaysLucky = object : Random() { override fun nextBits(bitCount: Int) = 0 }
    private val neverLucky = object : Random() { override fun nextBits(bitCount: Int) = -1 ushr (32 - bitCount) }

    @Test
    fun `skill level-up chances add up and are capped`() {
        val rogue = OwnedCard(1, CardType.ROGUE, Rarity.UNCOMMON)
        fun fox(r: Rarity) = OwnedCard(2, CardType.FOX, r)
        assertEquals(0.0, Rules.skillUpChance(rogue, listOf(fox(Rarity.COMMON))))
        assertEquals(0.1, Rules.skillUpChance(rogue, listOf(fox(Rarity.UNCOMMON))), 1e-9)
        assertEquals(0.3, Rules.skillUpChance(rogue, List(3) { fox(Rarity.UNCOMMON) }), 1e-9)
        assertEquals(0.5, Rules.skillUpChance(rogue, listOf(fox(Rarity.RARE), fox(Rarity.EPIC))), 1e-9)
        assertEquals(1.0, Rules.skillUpChance(rogue, List(3) { fox(Rarity.LEGENDARY) }))
        assertEquals(0.0, Rules.skillUpChance(rogue, listOf(OwnedCard(3, CardType.BAT, Rarity.EPIC)))) // other skill
        assertEquals(0.0, Rules.skillUpChance(rogue.copy(skillLevel = Balance.MAX_SKILL_LEVEL), listOf(fox(Rarity.EPIC))))
    }

    @Test
    fun `each skill level adds 15 percent power`() {
        assertEquals(400, Skill.SNEAK_ATTACK.power(Rarity.UNCOMMON, 1))
        assertEquals(460, Skill.SNEAK_ATTACK.power(Rarity.UNCOMMON, 2))
        assertEquals(520, Skill.SNEAK_ATTACK.power(Rarity.UNCOMMON, 3))
        assertTrue(Skill.SHIELD_UP.power(Rarity.UNCOMMON, 5) in 81..99)
    }

    private fun play(b: Battle, turns: Int = 1) = (0..turns).flatMap { b.advance() }

    @Test
    fun `aimed shot prefers the back row and stuns`() {
        val p = f(Side.PLAYER, 0, atk = 10, skill = Skill.AIMED_SHOT, power = 250)
        val front = f(Side.ENEMY, 0, hp = 1000, atk = 0); val back2 = f(Side.ENEMY, 4, hp = 1000, atk = 0); val back3 = f(Side.ENEMY, 5, hp = 1000, atk = 0)
        val b = Battle(listOf(p), listOf { listOf(front, back2, back3) })
        val ev = play(b)
        assertEquals(975, back2.hp) // column 1 order with rows swapped: B1, B2, B3, A1...
        assertTrue(back2.stunned)
        assertEquals(1000, front.hp)
        assertTrue(ev.any { it is BattleEvent.Stun && it.uid == back2.uid })
    }

    @Test
    fun `soul leech heals by the damage dealt`() {
        val p = f(Side.PLAYER, 0, hp = 100, atk = 10, skill = Skill.SOUL_LEECH, power = 200).apply { hp = 50 }
        val e = f(Side.ENEMY, 0, hp = 15, atk = 0)
        play(Battle(listOf(p), listOf({ listOf(e) }, { listOf(f(Side.ENEMY, 0, hp = 1000, atk = 0)) })))
        assertEquals(65, p.hp) // 20 damage, but the enemy only had 15 HP
    }

    @Test
    fun `flash heal picks the lowest hp, not the lowest percent`() {
        val warrior = f(Side.PLAYER, 0, hp = 150).apply { hp = 30 }
        val mage = f(Side.PLAYER, 1, hp = 30).apply { hp = 29 }
        val paladin = f(Side.PLAYER, 3, hp = 100, atk = 10, skill = Skill.FLASH_HEAL, power = 300)
        val e = f(Side.ENEMY, 0, hp = 10_000, atk = 0)
        val b = Battle(listOf(warrior, mage, paladin), listOf { listOf(e) })
        b.advance()
        while (b.fighter(paladin.uid)!!.cooldown == 0) b.advance()
        assertEquals(30, mage.hp)
        assertEquals(30, warrior.hp)
    }

    @Test
    fun `whirlwind hits the whole front row, else the back row`() {
        val p = f(Side.PLAYER, 0, atk = 10, skill = Skill.WHIRLWIND, power = 100)
        val a1 = f(Side.ENEMY, 0, hp = 1000, atk = 0); val a3 = f(Side.ENEMY, 2, hp = 1000, atk = 0); val b2 = f(Side.ENEMY, 4, hp = 1000, atk = 0)
        play(Battle(listOf(p), listOf { listOf(a1, a3, b2) }))
        assertEquals(listOf(990, 990, 1000), listOf(a1.hp, a3.hp, b2.hp))
        val c1 = f(Side.ENEMY, 3, hp = 1000, atk = 0); val c2 = f(Side.ENEMY, 5, hp = 1000, atk = 0)
        play(Battle(listOf(f(Side.PLAYER, 0, atk = 10, skill = Skill.WHIRLWIND, power = 100)), listOf { listOf(c1, c2) }))
        assertEquals(listOf(990, 990), listOf(c1.hp, c2.hp))
    }

    @Test
    fun `heal heals every friend`() {
        val a = f(Side.PLAYER, 0, hp = 100).apply { hp = 50 }
        val priest = f(Side.PLAYER, 1, hp = 100, atk = 20, skill = Skill.HEAL, power = 50).apply { hp = 95 }
        val e = f(Side.ENEMY, 0, hp = 10_000, atk = 0)
        val b = Battle(listOf(a, priest), listOf { listOf(e) })
        b.advance()
        while (b.fighter(priest.uid)!!.cooldown == 0) b.advance()
        assertEquals(60, a.hp)
        assertEquals(100, priest.hp)
    }

    @Test
    fun `every hero can win 1-1 alone`() {
        for (t in CardType.heroes) {
            val s = Rules.newGame(t)
            val (_, b) = Rules.startFight(s, Dungeons.stage("1-1")!!)
            assertEquals(Phase.WON, b.runToEnd(), "${t.title} should beat 1-1")
        }
    }

    @Test
    fun `evolution needs max level, materials and gold`() {
        var s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 50, gold = 1_000_000)
        assertFailsWith<GameException> { Rules.evolve(s, 1, emptyList()) }
        s = s.copy(cards = listOf(s.cards[0].copy(level = 30)) + listOf(
            OwnedCard(2, CardType.WARRIOR, Rarity.UNCOMMON),
            OwnedCard(3, CardType.WARRIOR, Rarity.UNCOMMON),
            OwnedCard(4, CardType.WARRIOR, Rarity.UNCOMMON),
        ))
        s = s.copy(cards = s.cards.map { if (it.id == 1L) it.copy(skillLevel = 3) else it })
        s = Rules.evolve(s, 1, listOf(2))
        assertEquals(1, s.card(1)!!.evo); assertEquals(1, s.card(1)!!.level)
        assertEquals(3, s.card(1)!!.skillLevel) // evolution keeps the skill level
        s = s.copy(cards = s.cards.map { if (it.id == 1L) it.copy(level = 30) else it })
        assertFailsWith<GameException> { Rules.evolve(s, 1, listOf(3)) } // needs 2 now
        s = Rules.evolve(s, 1, listOf(3, 4))
        assertEquals(Rarity.RARE, s.card(1)!!.rarity); assertEquals(0, s.card(1)!!.evo)
        assertEquals(1, s.cards.size)
    }

    @Test
    fun `monster cards cannot go above their rarity`() {
        val s = Rules.newGame(CardType.WARRIOR)
        val c = OwnedCard(5, CardType.BAT, Rarity.UNCOMMON, evo = 1, level = 30)
        assertTrue(Rules.evolveCheck(s, c) is Rules.EvolveCheck.No)
    }

    @Test
    fun `bought hero cards get more expensive up to the limit`() {
        assertEquals((0..20).map { 500L }, (0..20).map { Balance.heroPrice(it) })
        var s = Rules.newGame(CardType.WARRIOR).copy(gold = 1010)
        s = Rules.buyHero(s, CardType.PRIEST)
        assertEquals(510, s.gold)
        val bought = s.cards.last()
        assertEquals(CardType.PRIEST, bought.type); assertEquals(Rarity.UNCOMMON, bought.rarity); assertEquals(1, bought.level)
        s = Rules.buyHero(s, CardType.PRIEST)
        assertEquals(10, s.gold)
        assertEquals(2, s.heroesBought)
        assertFailsWith<GameException> { Rules.buyHero(s, CardType.MAGE) }
        assertFailsWith<GameException> { Rules.buyHero(s.copy(gold = 99999), CardType.BAT) }
    }

    // ---------------------------------------------------------------- dungeons & gold

    private fun stage(key: String) = Dungeons.stage(key)!!
    private fun steps(key: String, boss: Boolean) =
        stage(key).waves.flatten().filter { it.type.boss == boss }.map { Balance.ladderStep(it.rarity, it.evo) }

    @Test
    fun `fees grow 2x every 10 stages and the next dungeon starts at the level of stage 11`() {
        assertEquals(5, stage("1-1").entryFee)
        assertEquals(10, stage("1-11").entryFee)
        assertEquals(10, stage("2-1").entryFee)
        assertEquals(20, stage("2-11").entryFee)
        assertEquals(20, stage("3-1").entryFee)
        assertEquals(10, Dungeons.all.size)
        assertTrue(Dungeons.all.all { it.stages.size == 20 })
        assertEquals(listOf(null, "1-10", "2-10", "3-10", "4-10", "5-10", "6-10", "7-10", "8-10", "9-10"), Dungeons.all.map { it.unlockedBy })
    }

    @Test
    fun `higher rarities show up where planned`() {
        val green = Balance.ladderStep(Rarity.UNCOMMON, 0)
        // woods: white until 1-5, green from 1-6, green+ from 1-16, the only blue is the 1-20 boss
        assertTrue((1..5).all { s -> steps("1-$s", false).all { it == 0 } })
        // (with MIXED_RARITIES a stage's top rarity can be missing by chance, so: never above it, and it shows up in its band)
        fun top(stages: IntRange, d: Int = 1) = stages.maxOf { s -> steps("$d-$s", false).max() }
        assertTrue(steps("1-6", false).max() <= green)
        assertEquals(green, top(6..15))
        assertEquals(green + 1, top(16..19))
        assertEquals(listOf(Balance.ladderStep(Rarity.RARE, 0)), steps("1-20", true))
        assertTrue(Dungeons.all[0].stages.flatMap { it.waves.flatten() }.filter { it.rarity == Rarity.RARE }.all { it.type.boss })
        // first collectible blue in 2-16, blue+ boss in 2-20; first collectible blue+ in 3-16, blue++ boss in 3-20
        assertEquals(Balance.ladderStep(Rarity.RARE, 0), top(16..19, 2))
        assertTrue((1..15).all { s -> steps("2-$s", false).max() < Balance.ladderStep(Rarity.RARE, 0) })
        assertEquals(listOf(Balance.ladderStep(Rarity.RARE, 1)), steps("2-20", true))
        assertEquals(Balance.ladderStep(Rarity.RARE, 1), top(16..19, 3))
        assertTrue(steps("3-16", false).max() <= Balance.ladderStep(Rarity.RARE, 1))
        assertEquals(listOf(Balance.ladderStep(Rarity.RARE, 2)), steps("3-20", true))
        // the very last boss is legendary
        assertEquals(Rarity.LEGENDARY, stage("8-20").waves.last().first { it.type.boss }.rarity)
        // more monsters with a higher stage number, fewer (but stronger) ones in the next dungeon
        assertTrue(stage("1-11").waves.flatten().size > stage("2-1").waves.flatten().size)
        assertTrue(stage("2-1").waves.flatten().maxOf { Balance.effectiveLevel(it.level, it.rarity, it.evo) } >
            stage("1-11").waves.flatten().maxOf { Balance.effectiveLevel(it.level, it.rarity, it.evo) })
    }

    @Test
    fun `beaten monsters give gold unless they drop their card, first win doubles it`() {
        val st = stage("1-3")
        var s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 30, gold = 100, cleared = setOf("1-1", "1-2"))
        s = s.copy(cards = s.cards.map { it.copy(level = 25) })
        // a fight where nothing drops: all monsters pay
        val (s1, b) = Rules.startFight(s, st, Random(1))
        val noDrops = Battle(b.party, st.waves.map { w -> { w.map { Rules.monsterFighter(it.copy(dropChance = 0.0)) } } }, Random(1))
        assertEquals(Phase.WON, noDrops.runToEnd())
        assertEquals(st.maxGold, noDrops.gold)
        val (s2, r) = Rules.finishFight(s1, st, noDrops)
        assertEquals(2 * st.maxGold, r!!.gold)
        assertEquals(s1.gold + 2 * st.maxGold, s2.gold)
        // second win: single gold; a monster that drops its card gives none
        val allDrop = Battle(Rules.startFight(s2, st).second.party,
            st.waves.map { w -> { w.map { Rules.monsterFighter(it.copy(dropChance = 1.0)) } } }, Random(1))
        assertEquals(Phase.WON, allDrop.runToEnd())
        assertEquals(0, allDrop.gold)
        assertEquals(st.waves.flatten().size, allDrop.loot.size)
        assertEquals(1, Rules.goldMultiplier(s2, st))
    }

    @Test
    fun `gold and sell price follow level, rarity and evolution`() {
        assertTrue(Balance.cardGold(10, Rarity.COMMON, 0) > Balance.cardGold(1, Rarity.COMMON, 0))
        assertTrue(Balance.cardGold(1, Rarity.UNCOMMON, 0) > Balance.cardGold(1, Rarity.COMMON, 0))
        assertTrue(Balance.cardGold(1, Rarity.UNCOMMON, 1) > Balance.cardGold(1, Rarity.UNCOMMON, 0))
        // selling is slowed down by 0.5 per ladder step (rare+ = step 4)
        assertEquals(Math.round(Balance.SELL_FACTOR * Balance.cardGold(7, Rarity.RARE, 1) / 16.0), Balance.sellPrice(Rarity.RARE, 1, 7))
        assertEquals(Balance.SELL_FACTOR * Balance.cardGold(7, Rarity.COMMON, 0), Balance.sellPrice(Rarity.COMMON, 0, 7))
    }

    @Test
    fun `stage gold keeps its ratio to the entry fee although both grow slower`() {
        // gold of a monster = cardGold × 0.5^(tier/10); the old gold/fee ratio stays the same
        val st = stage("3-11")
        val m = st.waves.first().first()
        val expected = Balance.cardGold(m.level, m.rarity, m.evo) * 0.5.pow(st.tier / 10.0)
        assertTrue(abs(m.gold - expected) <= 1.0)
        assertEquals(100L, Balance.evolveGoldCost(Rarity.UNCOMMON, 0))
    }

    // ---------------------------------------------------------------- food cards

    @Test
    fun `food prices give the same XP per gold, a bit more for rarer food`() {
        assertEquals(500L, Balance.foodPrice(Rarity.COMMON))
        val perGold = Rarity.entries.map { OwnedCard(0, CardType.FOOD, it).sacrificeValue.toDouble() / Balance.foodPrice(it) }
        val white = perGold.first()
        assertTrue(perGold.all { it >= white - 1e-9 && it <= white * 1.1 }, perGold.toString())
        // 10x the XP of a normal card
        val normal = OwnedCard(0, CardType.BAT, Rarity.RARE, 1).sacrificeValue
        assertEquals(10 * normal, OwnedCard(0, CardType.FOOD, Rarity.RARE, 1).sacrificeValue)
    }

    @Test
    fun `food cards stay level 1, can't fight, evolve or eat, and are bought in the shop`() {
        var s = Rules.newGame(CardType.WARRIOR).copy(gold = 1000, playerLevel = 30)
        s = Rules.buyFood(s, Rarity.COMMON)
        assertEquals(500, s.gold)
        val food = s.cards.last()
        assertEquals(CardType.FOOD, food.type)
        assertEquals(1, Rules.addCardXp(s, food, 1_000_000).level)
        assertTrue(Rules.evolveCheck(s, food) is Rules.EvolveCheck.No)
        assertTrue(!food.hasSkill)
        assertFailsWith<GameException> { Rules.addToParty(s, food.id) }
        assertFailsWith<GameException> { Rules.feed(s, food.id, listOf(1L)) }
        val fed = Rules.feed(s, 1L, listOf(food.id))
        assertEquals(food.sacrificeValue, fed.card(1L)!!.totalXp)
        assertFailsWith<GameException> { Rules.buyFood(s, Rarity.UNCOMMON) }
    }

    @Test
    fun `bosses drop food of their rarity and still pay gold`() {
        val st = stage("1-20")
        val boss = st.waves.last().first { it.type.boss }
        assertEquals(Balance.FOOD_DROP_CHANCE, boss.dropChance)
        val s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 45, gold = 1000, cleared = (1..19).map { "1-$it" }.toSet())
            .let { it.copy(cards = it.cards.map { c -> c.copy(rarity = Rarity.LEGENDARY, evo = 4, level = 45) }) }
        val (s1, b) = Rules.startFight(s, st, Random(1))
        val battle = Battle(b.party, st.waves.map { w -> { w.map { Rules.monsterFighter(if (it.type.boss) it.copy(dropChance = 1.0) else it.copy(dropChance = 0.0)) } } }, Random(1))
        assertEquals(Phase.WON, battle.runToEnd())
        assertEquals(st.maxGold, battle.gold)
        val (_, r) = Rules.finishFight(s1, st, battle)
        val drop = r!!.drops.single()
        assertEquals(CardType.FOOD, drop.type)
        assertEquals(boss.rarity, drop.rarity)
        assertEquals(boss.evo, drop.evo)
        assertEquals(1, drop.level)
    }

    @Test
    fun `same-skill food is recognised only for cards with a skill`() {
        val target = OwnedCard(1, CardType.WARRIOR, Rarity.UNCOMMON)
        assertTrue(Rules.sharesSkill(target, OwnedCard(2, CardType.SNAIL, Rarity.UNCOMMON)))
        assertTrue(!Rules.sharesSkill(target, OwnedCard(3, CardType.SNAIL, Rarity.COMMON)))
        assertTrue(!Rules.sharesSkill(target, OwnedCard(4, CardType.BAT, Rarity.RARE)))
        assertTrue(!Rules.sharesSkill(target.copy(rarity = Rarity.COMMON), OwnedCard(5, CardType.SNAIL, Rarity.RARE)))
    }

    // ---------------------------------------------------------------- stars

    @Test
    fun `three stars per stage - win, no card lost, no skill used`() {
        val st = stage("1-1")
        var s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 30, gold = 100)
        s = s.copy(cards = s.cards.map { it.copy(level = 25) })
        // auto skills: the warrior uses Shield Up, so no "no skill" star
        val (s1, b1) = Rules.startFight(s, st, Random(1))
        assertEquals(Phase.WON, b1.runToEnd())
        val (s2, r1) = Rules.finishFight(s1, st, b1)
        assertEquals(2, r1!!.fightStars)
        assertEquals(2, s2.stars("1-1"))
        // stars don't add up across fights: no skills but a card lost in another fight is still 2 stars
        val lossNoSkill = s2.copy(bestStars = mapOf("1-1" to 2))
        assertEquals(2, lossNoSkill.stars("1-1"))
        // skills off and no losses in one fight: 3 stars
        val (s3, b2) = Rules.startFight(s2, st, Random(1))
        b2.skillMode = SkillMode.NEVER
        while (!b2.finished) b2.advance()
        assertTrue(b2.noCardLost)
        val (s4, r2) = Rules.finishFight(s3, st, b2)
        assertEquals(3, r2!!.fightStars)
        assertEquals(2, r2.starsBefore)
        assertEquals(3, s4.stars("1-1"))
        // a worse fight later doesn't lower it
        val (s5, b3) = Rules.startFight(s4, st, Random(1))
        assertEquals(Phase.WON, b3.runToEnd())
        assertEquals(3, Rules.finishFight(s5, st, b3).first.stars("1-1"))
    }

    @Test
    fun `star milestones give their rewards once`() {
        val d = Dungeons.all[0]
        assertEquals(listOf(20, 40, 60), d.milestones.map { it.stars })
        assertEquals(1000L, d.milestones[0].gold)
        assertEquals(CardReward(CardType.FOOD, Rarity.COMMON, 0, 5), d.milestones[1].cards.single())
        assertEquals(CardReward(CardType.SNAIL, Rarity.RARE, 0, level = Rarity.RARE.maxLevel), d.milestones[2].cards.single())
        val d2 = Dungeons.all[1]
        assertEquals(2000L, d2.milestones[0].gold)
        assertEquals(CardReward(CardType.FOOD, Rarity.UNCOMMON, 0, 5), d2.milestones[1].cards.single())
        assertEquals(CardReward(CardType.SKELETON, Rarity.RARE, 1, level = Rarity.RARE.maxLevel), d2.milestones[2].cards.single())
        // dungeons 8-10: gold instead of a (legendary) monster
        assertEquals(listOf(1_000_000L, 2_000_000L, 4_000_000L), Dungeons.all.drop(7).map { it.milestones[2].gold })
        assertTrue(Dungeons.all.drop(7).all { it.milestones[2].cards.isEmpty() })

        // all stars of the woods except the 3 of stage 1-1; then win 1-1 without skills and losses
        val keys = d.stages.map { it.key }.toSet() - "1-1"
        var s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 30, gold = 100, cleared = keys, bestStars = keys.associateWith { 3 })
        s = s.copy(cards = s.cards.map { it.copy(level = 25) })
        val st = stage("1-1")
        val (s1, b) = Rules.startFight(s, st, Random(1))
        b.skillMode = SkillMode.NEVER
        while (!b.finished) b.advance()
        val (s2, r) = Rules.finishFight(s1, st, b)
        assertEquals(60, s2.dungeonStars(d))
        assertTrue(s2.dungeonComplete(d))
        assertEquals(3, r!!.milestones.size)
        assertEquals(1000L, r.milestoneGold)
        assertEquals(6, r.milestoneCards.size)
        assertEquals(s1.gold + r.gold + 1000, s2.gold)
        // another win gives nothing more
        val (s3, b2) = Rules.startFight(s2, st, Random(1))
        assertEquals(Phase.WON, b2.runToEnd())
        assertEquals(0, Rules.finishFight(s3, st, b2).second!!.milestones.size)
    }

    @Test
    fun `legendary heroes get extra effective levels, legendary monsters don't`() {
        val hero = OwnedCard(0, CardType.PALADIN, Rarity.LEGENDARY, 0, level = 45)
        val purple = OwnedCard(0, CardType.PALADIN, Rarity.EPIC, 3, level = 40)
        assertEquals(65.0 + Balance.LEGENDARY_HERO_BONUS, Balance.cardEffectiveLevel(hero.type, hero.level, hero.rarity, hero.evo))
        assertTrue(hero.hp > purple.hp && hero.atk > purple.atk) // a maxed legendary beats the maxed purple+++ it came from
        assertEquals(65.0, Balance.cardEffectiveLevel(CardType.ROCKET, 45, Rarity.LEGENDARY, 0))
        assertEquals(Balance.hp(CardType.ROCKET, 45, Rarity.LEGENDARY, 0), (CardType.ROCKET.baseHp * 1.1.pow(64)).roundToInt())
    }

    @Test
    fun `the shop sells a hero at every rarity the player has evolved it to`() {
        val s0 = Rules.newGame(CardType.PALADIN).copy(gold = 1_000_000)
        assertEquals(listOf(Rarity.UNCOMMON), s0.heroShopRarities(CardType.PALADIN))
        assertFailsWith<GameException> { Rules.buyHero(s0, CardType.PALADIN, Rarity.RARE) }
        // the paladin became blue: blue paladins are for sale, other heroes and purple aren't
        val s1 = s0.copy(cards = s0.cards.map { it.copy(rarity = Rarity.RARE) })
        assertEquals(listOf(Rarity.UNCOMMON, Rarity.RARE), s1.heroShopRarities(CardType.PALADIN))
        assertEquals(listOf(Rarity.UNCOMMON), s1.heroShopRarities(CardType.MAGE))
        assertFailsWith<GameException> { Rules.buyHero(s1, CardType.PALADIN, Rarity.EPIC) }
        val s2 = Rules.buyHero(s1, CardType.PALADIN, Rarity.RARE)
        val bought = s2.cards.last()
        assertEquals(Rarity.RARE, bought.rarity); assertEquals(1, bought.level); assertEquals(0, bought.evo)
        assertEquals(s1.gold - Balance.HERO_PRICE_BY_RARITY.getValue(Rarity.RARE), s2.gold)
        assertEquals(s1.heroesBought, s2.heroesBought) // only green purchases raise the green price
        // and it is evolution material for the blue paladin
        assertEquals(listOf(bought.id), Rules.evolveMaterialCandidates(s2, s2.cards.first()).map { it.id })
    }

    @Test
    fun `a card's value depends only on its level, rarity and evo`() {
        val wolf = Rules.monsterFighter(Dungeons.stage("1-3")!!.waves.flatten().first().copy(type = CardType.WOLF, level = 13))
        val loot = Rules.lootCard(wolf, 50)
        assertEquals((13 * Balance.LOOT_LEVEL_SHARE).toInt(), loot.level)
        val s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 50)
        val raised = Rules.addCardXp(s, OwnedCard(0, loot.type, loot.rarity, loot.evo), Balance.cardXpTotal(loot.level, false))
        assertEquals(loot.level, raised.level)
        assertEquals(raised.sacrificeValue, loot.sacrificeValue)
        assertEquals(raised.sellPrice, loot.sellPrice)
        // an evolved card is worth the same as a dropped one of the same stage: its history doesn't count
        val evolved = OwnedCard(0, CardType.WOLF, Rarity.RARE, evo = 1, level = 7)
        assertEquals(Balance.sacrificeValue(7, Rarity.RARE, 1), evolved.sacrificeValue)
        // above the rarity's max level it counts as the max level
        assertEquals(OwnedCard(0, CardType.WOLF, Rarity.COMMON, level = 25).sacrificeValue, OwnedCard(0, CardType.WOLF, Rarity.COMMON, level = 40).sacrificeValue)
    }

    @Test
    fun `looted cards join at LOOT_LEVEL_SHARE of the monster's level`() {
        assertEquals(1, Balance.lootLevel(1, Rarity.COMMON, 50))
        assertEquals(maxOf(1, (3 * Balance.LOOT_LEVEL_SHARE).toInt()), Balance.lootLevel(3, Rarity.COMMON, 50))
        assertEquals((21 * Balance.LOOT_LEVEL_SHARE).toInt(), Balance.lootLevel(21, Rarity.RARE, 50))
        assertEquals(7, Balance.lootLevel(21, Rarity.RARE, 7))       // not above the player level
        assertEquals(50, Balance.lootLevel(60, Rarity.COMMON, 50))   // above the rarity's max level (mixed-in monsters), not above the player's
        val st = Dungeons.stage("1-3")!!
        val s = Rules.newGame(CardType.WARRIOR).copy(playerLevel = 30, cleared = setOf("1-1", "1-2"), gold = 100)
            .let { it.copy(cards = it.cards.map { c -> c.copy(level = 25) }) }
        val (s1, b) = Rules.startFight(s, st, Random(1))
        val allDrop = Battle(b.party, st.waves.map { w -> { w.map { Rules.monsterFighter(it.copy(dropChance = 1.0)) } } }, Random(1))
        assertEquals(Phase.WON, allDrop.runToEnd())
        val (_, r) = Rules.finishFight(s1, st, allDrop)
        assertEquals(allDrop.loot.map { maxOf(1, (it.level * Balance.LOOT_LEVEL_SHARE).toInt()) }, r!!.drops.map { it.level })
    }

    // ---------------------------------------------------------------- one hero per class, boss traits

    @Test
    fun `only one hero of a class fits into the party`() {
        var s = Rules.newGame(CardType.WARRIOR).copy(gold = 10_000)
        s = Rules.buyHero(s, CardType.WARRIOR)
        val second = s.cards.last()
        assertFailsWith<GameException> { Rules.addToParty(s, second.id) }
        // swapping it with the first warrior is fine
        val swapped = Rules.placeInParty(s, second.id, 0)
        assertEquals(listOf(second.id), swapped.partyCards.map { it.id })
        // monsters can still be doubled
        s = s.copy(cards = s.cards + OwnedCard(90, CardType.SNAIL, Rarity.COMMON) + OwnedCard(91, CardType.SNAIL, Rarity.COMMON))
        s = Rules.addToParty(Rules.addToParty(s, 90), 91)
        assertEquals(3, s.partyCards.size)
    }

    private fun boss(type: CardType, slot: Int = 0, hp: Int = 1000, atk: Int = 10) =
        Fighter(++uid, Side.ENEMY, type, Rarity.RARE, 0, 1, hp, atk, null, 0, listOf(slot), skillLevel = MAX)

    private fun events(b: Battle, turns: Int) = (1..turns).flatMap { b.advance() }

    @Test
    fun `an undying boss gets back up three times unless soul leech drained it`() {
        assertEquals(BossTrait.UNDYING, CardType.LICH.trait)
        val lich = boss(CardType.LICH, hp = 50)
        val b = Battle(listOf(f(Side.PLAYER, 0, hp = 1000, atk = 60)), listOf({ listOf(lich) }))
        assertEquals(3, events(b, 6).count { it is BattleEvent.Revived })
        assertEquals(50, lich.hp)
        events(b, 2)
        assertEquals(Phase.WON, b.phase)
        // a soul leech drains the spare lives and the boss stays down
        val lich2 = boss(CardType.LICH, hp = 50)
        val b2 = Battle(listOf(f(Side.PLAYER, 0, atk = 60, skill = Skill.SOUL_LEECH, power = 200)), listOf({ listOf(lich2) }))
        val ev2 = events(b2, 2)
        assertTrue(ev2.any { it is BattleEvent.TraitBroken })
        assertTrue(ev2.none { it is BattleEvent.Revived })
        assertEquals(Phase.WON, b2.phase)
    }

    @Test
    fun `stone skin takes a tenth from single target hits but full area damage`() {
        val rex = boss(CardType.LAVA_REX)
        Battle(listOf(f(Side.PLAYER, 0, atk = 40)), listOf({ listOf(rex) })).also { events(it, 2) }
        assertEquals(996, rex.hp)
        val rex2 = boss(CardType.LAVA_REX)
        Battle(listOf(f(Side.PLAYER, 0, atk = 40, skill = Skill.WHIRLWIND, power = 100)), listOf({ listOf(rex2) })).also { events(it, 2) }
        assertEquals(960, rex2.hp)
    }

    @Test
    fun `a swarm boss takes a tenth while its helpers live`() {
        val wyrm = boss(CardType.DESERT_WYRM, slot = 4)
        val helper = f(Side.ENEMY, 0, hp = 1000)
        Battle(listOf(f(Side.PLAYER, 0, atk = 40, skill = Skill.FIREBALL, power = 100)), listOf({ listOf(wyrm, helper) })).also { events(it, 2) }
        assertEquals(996, wyrm.hp)
        assertEquals(960, helper.hp)
    }

    @Test
    fun `a crusher is armored until it smashes into a shield`() {
        val yeti = boss(CardType.YETI, atk = 10)
        val small = f(Side.PLAYER, 0, hp = 100)
        val big = f(Side.PLAYER, 1, hp = 200)
        Battle(listOf(small, big), listOf({ listOf(yeti) })).also { events(it, 4) }
        assertEquals(170, big.hp) // the biggest card, triple damage
        assertEquals(100, small.hp)
        assertEquals(998, yeti.hp) // armor: two hits of a tenth of 10
        // a raised shield: no damage, the yeti is stunned and its armor cracks
        val yeti2 = boss(CardType.YETI, atk = 10)
        val warrior = f(Side.PLAYER, 0, hp = 200, skill = Skill.SHIELD_UP, power = 80)
        val ev = Battle(listOf(warrior), listOf({ listOf(yeti2) })).let { events(it, 4) }
        assertEquals(200, warrior.hp)
        assertTrue(ev.any { it is BattleEvent.Stun && it.uid == yeti2.uid })
        assertTrue(ev.any { it is BattleEvent.TraitBroken })
        assertEquals(990, yeti2.hp) // full damage from then on
    }

    @Test
    fun `an ambusher hits the card with the lowest hp twice as hard`() {
        val croc = boss(CardType.SAND_CROC, slot = 2, atk = 10)
        val front = f(Side.PLAYER, 2, hp = 100)
        val weak = f(Side.PLAYER, 3, hp = 30)
        Battle(listOf(front, weak), listOf({ listOf(croc) })).also { events(it, 4) }
        assertEquals(10, weak.hp)
        assertEquals(100, front.hp)
    }

    @Test
    fun `a regenerating boss heals itself until a sneak attack wounds it`() {
        val lev = boss(CardType.LEVIATHAN, hp = 1000)
        Battle(listOf(f(Side.PLAYER, 0, hp = 1000, atk = 400)), listOf({ listOf(lev) })).also { events(it, 3) }
        assertEquals(850, lev.hp) // -400, then +250 at the start of its turn
        val lev2 = boss(CardType.LEVIATHAN, hp = 1000)
        Battle(listOf(f(Side.PLAYER, 0, hp = 1000, atk = 200, skill = Skill.SNEAK_ATTACK, power = 400)), listOf({ listOf(lev2) }))
            .also { events(it, 3) }
        assertEquals(200, lev2.hp)
    }

    @Test
    fun `heal at any level puts out the fire`() {
        val burning = f(Side.PLAYER, 0, hp = 100)
        val priest = f(Side.PLAYER, 1, hp = 100, skill = Skill.HEAL, power = 50)
        val b = Battle(listOf(priest, burning), listOf({ listOf(f(Side.ENEMY, 5, hp = 1000, atk = 1)) }))
        b.advance() // the wave starts (it clears all debuffs)
        burning.burning = 5
        val ev = events(b, 4)
        assertTrue(ev.any { it is BattleEvent.Extinguished && it.uid == burning.uid })
        assertEquals(0, burning.burning)
    }

    @Test
    fun `swarm and commander bosses bring their own wave layout`() {
        val swarm = Dungeons.stage("5-20")!!.waves.last()
        assertEquals(BossTrait.SWARM, Dungeons.stage("5-20")!!.bossTrait)
        assertEquals(6, swarm.sumOf { it.slots.size })
        assertTrue(swarm.filter { !it.type.boss }.all { it.hpFactor < 1 && it.atkFactor < 1 })
        val cmd = Dungeons.stage("4-20")!!.waves.last()
        assertEquals(listOf(0, 1, 2), cmd.first { it.type.boss }.slots)
        assertTrue(cmd.filter { !it.type.boss }.all { it.slots.single() >= 3 && it.atkFactor > 1 })
        assertEquals(null, Dungeons.stage("2-20")!!.bossTrait)
    }

    @Test
    fun `inventory holds 20 cards, upgrades add 10 slots and double in price`() {
        var s = Rules.newGame(CardType.WARRIOR).copy(gold = 100_000)
        assertEquals(20, s.inventorySize)
        assertEquals(1000, s.inventoryUpgradeCost)
        val full = s.copy(cards = s.cards + List(19) { OwnedCard(100L + it, CardType.BAT, Rarity.COMMON) })
        assertTrue(full.inventoryFull)
        assertFailsWith<GameException> { Rules.startFight(full, Dungeons.stage("1-1")!!) }
        assertFailsWith<GameException> { Rules.buyFood(full, Rarity.COMMON) }
        assertFailsWith<GameException> { Rules.buyHero(full, CardType.MAGE) }
        s = Rules.upgradeInventory(full)
        assertEquals(30, s.inventorySize)
        assertEquals(99_000, s.gold)
        Rules.startFight(s, Dungeons.stage("1-1")!!)
        s = Rules.upgradeInventory(s)
        assertEquals(40, s.inventorySize)
        assertEquals(97_000, s.gold)
        assertEquals(4000, s.inventoryUpgradeCost)
        assertFailsWith<GameException> { Rules.upgradeInventory(s.copy(gold = 3999)) }
    }

    // ---------------------------------------------------------------- meditation

    private fun medState(vararg cards: OwnedCard) =
        Rules.newGame(CardType.WARRIOR).let { it.copy(cards = it.cards + cards, nextId = 100) }

    @Test
    fun `clues per night by rarity`() {
        assertEquals(listOf(1L, 2L, 3L), (0..2).map { Balance.cluesPerDay(Rarity.RARE, it) })
        assertEquals(listOf(7L, 10L, 13L, 16L), (0..3).map { Balance.cluesPerDay(Rarity.EPIC, it) })
        assertEquals(listOf(25L, 30L, 35L, 40L, 45L), (0..4).map { Balance.cluesPerDay(Rarity.LEGENDARY, it) })
        assertEquals(0L, Balance.cluesPerDay(Rarity.UNCOMMON, 1))
        assertEquals(listOf(1L, 10L, 100L, 1000L), (0..3).map { Balance.cooldownCutCost(it) })
    }

    @Test
    fun `meditation unlocks with the first rare hero and collects clues per midnight`() {
        var s = Rules.newGame(CardType.WARRIOR)
        assertEquals(s, Rules.collectClues(s, 100).first) // only an uncommon hero: still locked
        s = s.copy(cards = s.cards + OwnedCard(10, CardType.MAGE, Rarity.RARE, evo = 1) + OwnedCard(11, CardType.MAGE, Rarity.EPIC)
            + OwnedCard(12, CardType.ROGUE, Rarity.RARE) + OwnedCard(13, CardType.BEAR, Rarity.LEGENDARY))
        s = Rules.collectClues(s, 100).first
        assertTrue(s.meditationUnlocked)
        assertEquals(100L, s.lastClueDay)
        assertTrue(s.clues.isEmpty()) // nothing on the day it opens
        assertEquals(setOf(CardType.MAGE, CardType.ROGUE), s.meditators.keys)
        assertEquals(11L, s.meditators[CardType.MAGE]!!.id) // the best mage meditates
        assertEquals(s, Rules.collectClues(s, 100).first)
        assertEquals(s, Rules.collectClues(s, 99).first) // clock went back: nothing
        val (after, gained) = Rules.collectClues(s, 107) // a week later
        assertEquals(mapOf(CardType.MAGE to 49L, CardType.ROGUE to 7L), gained)
        assertEquals(49L, after.clues(CardType.MAGE))
        assertEquals(107L, after.lastClueDay)
        // stays unlocked even without a rare hero
        val noRare = after.copy(cards = after.cards.filter { !Rules.canMeditate(it) })
        assertTrue(Rules.collectClues(noRare, 108).first.meditationUnlocked)
    }

    @Test
    fun `cooldown cuts cost clues of every class, in order, and shorten the battle cooldown`() {
        val card = OwnedCard(10, CardType.ROGUE, Rarity.RARE)
        var s = medState(card, OwnedCard(11, CardType.ROGUE, Rarity.UNCOMMON))
        s = s.copy(clues = CardType.heroes.associateWith { 11L })
        assertTrue(Rules.cooldownCutProblem(s, s.card(11)!!) != null) // uncommon can't
        s = Rules.cutCooldown(s, 10)
        assertEquals(1, s.card(10)!!.cooldownCuts)
        assertEquals(3, s.card(10)!!.skillCooldown)
        assertTrue(CardType.heroes.all { s.clues(it) == 10L })
        s = Rules.cutCooldown(s, 10)
        assertTrue(s.clues.isEmpty())
        assertFailsWith<GameException> { Rules.cutCooldown(s, 10) } // 100 each needed now
        s = s.copy(clues = CardType.heroes.associateWith { 100L } - CardType.PRIEST)
        assertFailsWith<GameException> { Rules.cutCooldown(s, 10) } // missing one class
        s = s.copy(clues = CardType.heroes.associateWith { 1100L })
        s = Rules.cutCooldown(Rules.cutCooldown(s, 10), 10)
        assertEquals(0, s.card(10)!!.skillCooldown)
        assertFailsWith<GameException> { Rules.cutCooldown(s, 10) } // max reached
        assertEquals(0, Rules.fighterFor(s.card(10)!!, 0).skillCooldown)
    }

    @Test
    fun `a card with cooldown 0 uses its skill every turn`() {
        val p = Fighter(++uid, Side.PLAYER, CardType.ROGUE, Rarity.RARE, 0, 1, 100, 1, Skill.SNEAK_ATTACK, 400, listOf(0), skillCooldown = 0)
        val e = f(Side.ENEMY, 0, hp = 100_000, atk = 0)
        val b = Battle(listOf(p), listOf { listOf(e) })
        val events = (1..20).flatMap { b.advance() }
        val attacks = events.filterIsInstance<BattleEvent.Attack>().filter { it.attacker == p.uid }
        assertTrue(attacks.size >= 5 && attacks.all { it.skill == Skill.SNEAK_ATTACK })
    }

    @Test
    fun `evolving keeps cooldown cuts and the stronger twin is found`() {
        val weak = OwnedCard(10, CardType.MAGE, Rarity.RARE, level = 35, cooldownCuts = 2)
        val strong = OwnedCard(11, CardType.MAGE, Rarity.EPIC)
        val mat = OwnedCard(12, CardType.MAGE, Rarity.RARE)
        val s = medState(weak, strong, mat).copy(gold = 1_000_000)
        assertEquals(11L, Rules.strongerTwin(s, weak)?.id)
        assertEquals(null, Rules.strongerTwin(s, strong))
        assertEquals(2, Rules.evolve(s, 10, listOf(12)).card(10)!!.cooldownCuts)
    }
}
