package cz.erlebach.cardrpg.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.battle.Affliction
import cz.erlebach.cardrpg.battle.Battle
import cz.erlebach.cardrpg.battle.BattleEvent
import cz.erlebach.cardrpg.battle.Fighter
import cz.erlebach.cardrpg.battle.Phase
import cz.erlebach.cardrpg.battle.Side
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.game.FightRewards
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.BossTrait
import cz.erlebach.cardrpg.model.Skill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.sin

private data class Floater(val id: Long, val uid: Int, val text: String, val color: Color)
private data class Projectile(val id: Long, val from: Int, val to: Int, val emoji: String)

private sealed interface Outcome {
    data class Won(val rewards: FightRewards) : Outcome
    data object Lost : Outcome
}

/** All animation state of one battle. The engine runs one turn ahead; this holds what is *shown*. */
private class BattleUi(val battle: Battle, val goldMultiplier: Int) {
    val hp = mutableStateMapOf<Int, Int>()
    val removed = mutableStateMapOf<Int, Boolean>()
    val shakes = mutableStateMapOf<Int, Int>()
    var enemies by mutableStateOf<List<Fighter>>(emptyList())
    var walking by mutableStateOf(true)
    val walk = Animatable(0f)
    val deal = Animatable(0f)
    var actor by mutableStateOf<Int?>(null)
    var lunge by mutableStateOf<Pair<Int, Int>?>(null)
    val lungeProgress = Animatable(0f)
    val floaters = mutableStateListOf<Floater>()
    val projectiles = mutableStateListOf<Projectile>()
    val projectileProgress = Animatable(0f)
    var banner by mutableStateOf<String?>(null)
    var skillCall by mutableStateOf<Pair<Int, String>?>(null)
    var decision by mutableStateOf<Fighter?>(null)
    var decisionAnswer: CompletableDeferred<Boolean>? = null
    var outcome by mutableStateOf<Outcome?>(null)
    var tick by mutableStateOf(0)
    var speed by mutableStateOf(1f)
    var mode by mutableStateOf(battle.skillMode)
    /** Monster whose dropped card is flying into the loot tray. */
    var flying by mutableStateOf<Int?>(null)
    val flyProgress = Animatable(0f)
    /** Dropped cards collected so far (shown in the loot tray). */
    val loot = mutableStateListOf<Fighter>()
    /** Gold collected so far (including the first-win bonus). */
    var gold by mutableStateOf(0L)
    var shownWave by mutableStateOf(0)
    private var ids = 0L

    init { battle.party.forEach { hp[it.uid] = it.maxHp } }

    fun fighter(uid: Int) = battle.party.firstOrNull { it.uid == uid } ?: enemies.firstOrNull { it.uid == uid }

    fun switchMode(m: SkillMode) {
        mode = m
        battle.skillMode = m
        when (m) {
            SkillMode.AUTO -> decisionAnswer?.complete(true)
            SkillMode.NEVER -> decisionAnswer?.complete(false)
            SkillMode.ASK -> {}
        }
    }

    private suspend fun wait(ms: Int) = delay((ms / speed).toLong())
    private fun tw(ms: Int) = tween<Float>((ms / speed).toInt(), easing = LinearEasing)

    fun float(uid: Int, text: String, color: Color) { floaters += Floater(ids++, uid, text, color) }
    fun dropFloater(id: Long) { floaters.removeAll { it.id == id } }

    suspend fun run(onEnd: (Battle) -> Outcome) {
        while (!battle.finished) {
            val events = if (battle.phase == Phase.AWAITING_DECISION) {
                val f = battle.pending!!
                val use = if (battle.skillMode != SkillMode.ASK) battle.skillMode == SkillMode.AUTO else {
                    actor = f.uid
                    decision = f
                    val d = CompletableDeferred<Boolean>()
                    decisionAnswer = d
                    d.await().also { decision = null; decisionAnswer = null }
                }
                battle.decide(use)
            } else battle.advance()
            play(events)
            tick++
        }
        actor = null
        wait(500)
        outcome = onEnd(battle)
    }

    /** Set by the dev "skip" button: the animated [run] is cancelled and [skipToEnd] takes over. */
    var skipped by mutableStateOf(false)

    /**
     * Dev: plays the rest of the fight without any animation (as if the speed were infinite) and shows the result.
     * Skills follow the current mode; "I decide" counts as auto, since there's no one to ask.
     */
    suspend fun skipToEnd(onEnd: (Battle) -> Outcome) {
        decision = null; decisionAnswer = null
        actor = null; lunge = null; skillCall = null; banner = null; flying = null
        projectiles.clear()
        while (!battle.finished) {
            val events = if (battle.phase == Phase.AWAITING_DECISION) battle.decide(battle.skillMode != SkillMode.NEVER) else battle.advance()
            for (e in events) when (e) {
                is BattleEvent.WaveStart -> {
                    shownWave = e.wave
                    battle.enemies.forEach { hp[it.uid] = it.hp; removed.remove(it.uid) }
                    enemies = battle.enemies
                }
                is BattleEvent.Damage -> hp[e.target] = e.hpAfter
                is BattleEvent.Heal -> hp[e.target] = e.hpAfter
                is BattleEvent.Revived -> { hp[e.uid] = e.hpAfter; removed.remove(e.uid) }
                is BattleEvent.Defeated -> removed[e.uid] = true
                is BattleEvent.Looted -> { fighter(e.uid)?.let { loot += it }; removed[e.uid] = true }
                is BattleEvent.Coins -> gold += e.amount * goldMultiplier
                else -> {}
            }
        }
        walk.snapTo(1f); deal.snapTo(1f)
        walking = false
        tick++
        outcome = onEnd(battle)
    }

    private suspend fun play(events: List<BattleEvent>) {
        var returnLunge = false
        suspend fun finishLunge() {
            if (returnLunge) {
                lungeProgress.animateTo(1f, tw(150))
                lunge = null
                returnLunge = false
            }
        }
        val lootedNow = events.filterIsInstance<BattleEvent.Looted>().map { it.uid }.toSet()
        for (e in events) {
            if (e !is BattleEvent.Damage && e !is BattleEvent.Defeated && e !is BattleEvent.Stun && e !is BattleEvent.Looted) finishLunge()
            when (e) {
                is BattleEvent.WaveStart -> startWave(e.wave)
                is BattleEvent.TurnStart -> { actor = e.uid; tick++; wait(90) }
                is BattleEvent.SkipTurn -> {
                    shakes[e.uid] = (shakes[e.uid] ?: 0) + 1
                    float(e.uid, "😵 Stunned!", Color(0xFFFFE066))
                    wait(700)
                }
                is BattleEvent.Attack -> {
                    val skill = e.skill
                    if (skill == null) {
                        lunge = e.attacker to e.targets.first()
                        lungeProgress.snapTo(0f)
                        lungeProgress.animateTo(0.5f, tw(150))
                        returnLunge = true
                    } else {
                        skillCall = e.attacker to "${skill.icon} ${skill.title}!"
                        wait(450)
                        shoot(e.attacker, e.targets, skillProjectile(skill))
                        skillCall = null
                    }
                }
                is BattleEvent.Damage -> {
                    hp[e.target] = e.hpAfter
                    shakes[e.target] = (shakes[e.target] ?: 0) + 1
                    val dot = e.dot
                    when {
                        dot != null -> float(e.target, "${dot.icon} -" + fmt(e.amount), if (dot == Affliction.BURNING) Color(0xFFFFA040) else Color(0xFFB0F060))
                        else -> float(e.target, (if (e.blocked) "🛡️ -" else "-") + fmt(e.amount), if (e.blocked) Color(0xFF9FD3FF) else Color(0xFFFF6B6B))
                    }
                    if (dot != null) wait(250)
                    wait(90)
                }
                is BattleEvent.Heal -> {
                    hp[e.target] = e.hpAfter
                    float(e.target, "+${fmt(e.amount)}", Palette.good)
                    wait(120)
                }
                is BattleEvent.GuardUp -> {
                    skillCall = e.uid to "🛡️ Shield Up!"
                    tick++
                    wait(650)
                    skillCall = null
                }
                is BattleEvent.Cleansed -> {
                    float(e.uid, "✨ Cleansed!", Color(0xFFBFE0FF))
                    tick++
                    wait(200)
                }
                is BattleEvent.Afflicted -> {
                    wait(120)
                    float(e.uid, "${e.affliction.icon} ${e.affliction.label}!", if (e.affliction == Affliction.BURNING) Color(0xFFFFA040) else Color(0xFFB0F060))
                    tick++
                    wait(250)
                }
                is BattleEvent.Revived -> {
                    wait(200)
                    hp[e.uid] = e.hpAfter
                    float(e.uid, "⚰️ Back up!", Color(0xFFD0B0FF))
                    tick++
                    wait(500)
                }
                is BattleEvent.TraitBroken -> {
                    val text = when (battle.fighter(e.uid)?.trait) {
                        BossTrait.UNDYING -> "🔮 Spare lives drained!"
                        BossTrait.CRUSHER -> "💥 Armor cracked!"
                        BossTrait.REGENERATION -> "🩸 Deep wound – no more healing!"
                        BossTrait.STONE_SKIN -> "💥 Stone skin shattered!"
                        BossTrait.AMBUSHER -> "💡 Revealed!"
                        else -> "💥 Weakened!"
                    }
                    float(e.uid, text, Color(0xFFD0B0FF))
                    tick++
                    wait(500)
                }
                is BattleEvent.Extinguished -> { float(e.uid, "💧 Fire out!", Color(0xFF9FD3FF)); wait(150) }
                is BattleEvent.Stun -> { wait(120); float(e.uid, "💫 Stunned!", Color(0xFFFFE066)); tick++; wait(350) }
                is BattleEvent.Defeated -> if (e.uid !in lootedNow) { wait(150); removed[e.uid] = true; wait(250) }
                is BattleEvent.Looted -> {
                    // instead of vanishing, the card flies into the loot tray
                    finishLunge()
                    wait(150)
                    float(e.uid, "🎁 Got it!", Palette.gold)
                    flyProgress.snapTo(0f)
                    flying = e.uid
                    wait(300)
                    flyProgress.animateTo(1f, tween((650 / speed).toInt(), easing = FastOutSlowInEasing))
                    fighter(e.uid)?.let { loot += it }
                    removed[e.uid] = true
                    flying = null
                    wait(100)
                }
                is BattleEvent.Coins -> {
                    val amount = e.amount * goldMultiplier
                    float(e.uid, "🪙 +${fmt(amount)}", Palette.gold)
                    gold += amount
                    wait(150)
                }
                is BattleEvent.WaveCleared -> {
                    finishLunge()
                    actor = null
                    wait(400)
                    banner = if (e.wave + 1 < battle.waveCount) "Wave ${e.wave + 1} cleared! 🎉" else "Victory! 🏆"
                    wait(1100)
                    banner = null
                }
                BattleEvent.Victory -> {}
                BattleEvent.Defeat -> { banner = "Oh no… 😢"; wait(1200); banner = null }
            }
        }
        finishLunge()
        wait(120)
    }

    private fun skillProjectile(s: Skill) = s.icon

    private suspend fun shoot(from: Int, to: List<Int>, emoji: String) {
        projectiles.clear()
        to.forEach { projectiles += Projectile(ids++, from, it, emoji) }
        projectileProgress.snapTo(0f)
        projectileProgress.animateTo(1f, tw(380))
        projectiles.clear()
    }

    private suspend fun startWave(index: Int) {
        // the party walks down the corridor until it meets the next wave
        enemies = emptyList()
        shownWave = index
        actor = null
        walking = true
        walk.snapTo(0f)
        coroutineScope {
            launch { walk.animateTo(1f, tween((1800 / speed).toInt(), easing = FastOutSlowInEasing)) }
            delay((300 / speed).toLong())
            banner = "Wave ${index + 1} / ${battle.waveCount}" + if (battle.enemies.any { it.type.boss }) "  👑 BOSS!" else ""
        }
        banner = null
        walking = false
        battle.enemies.forEach { hp[it.uid] = it.hp; removed.remove(it.uid) }
        enemies = battle.enemies
        deal.snapTo(0f)
        deal.animateTo(1f, tw(700))
        tick++
    }
}

@Composable
fun BattleScreen(model: AppModel, fight: ActiveFight) {
    val ui = remember(fight) { BattleUi(fight.battle, Rules.goldMultiplier(model.state, fight.stage)) }
    val dungeon = Dungeons.all.first { it.id == fight.stage.dungeon }
    // changing `skipped` cancels the animated run and finishes the fight instantly
    LaunchedEffect(fight, ui.skipped) {
        ui.speed = model.state.battleSpeed.toFloat()
        val onEnd: (Battle) -> Outcome = { battle ->
            var rewards: FightRewards? = null
            model.update { s -> Rules.finishFight(s, fight.stage, battle).also { rewards = it.second }.first }
            rewards?.let { Outcome.Won(it) } ?: Outcome.Lost
        }
        if (ui.skipped) ui.skipToEnd(onEnd) else ui.run(onEnd)
    }
    @Suppress("UNUSED_VARIABLE") val t = ui.tick // re-read engine state (guard, cooldowns) every turn
    var confirmLeave by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(dungeon.bg), Palette.feltDark)))) {
        // ---- top bar
        Row(
            Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${dungeon.emoji} ${dungeon.name} ${fight.stage.key}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Palette.text)
            Spacer(Modifier.width(20.dp))
            Text("Wave ${ui.shownWave + 1}/${fight.battle.waveCount}   Round ${fight.battle.round}", color = Palette.textDim, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text("Skills:", color = Palette.text)
            Spacer(Modifier.width(6.dp))
            for ((m, label) in SKILL_MODES) {
                Spacer(Modifier.width(6.dp))
                FilterChip(ui.mode == m, { ui.switchMode(m); model.update { it.copy(skillMode = m) } }, { Text(label) })
            }
            Spacer(Modifier.width(20.dp))
            Text("Speed:", color = Palette.text)
            for (sp in listOf(1, 2, 4)) {
                Spacer(Modifier.width(6.dp))
                FilterChip(ui.speed == sp.toFloat(), { ui.speed = sp.toFloat(); model.update { it.copy(battleSpeed = sp) } }, { Text("${sp}×") })
            }
            if (model.devMode && ui.outcome == null && !ui.skipped) {
                Spacer(Modifier.width(12.dp))
                TextButton({ ui.skipped = true }) { Text("⏭️ Skip (dev)", color = Palette.textDim) }
            }
            Spacer(Modifier.width(20.dp))
            if (ui.outcome == null) TextButton({ confirmLeave = true }) { Text("🏳️ Run away", color = Palette.textDim) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 10.dp
            val midGap = 34.dp
            val cardH = ((maxHeight - gap * 2 - midGap - 24.dp) / 4).coerceAtMost(210.dp)
            val cardW = cardH / 1.4f
            val gridW = cardW * 3 + gap * 2
            val ox = (maxWidth - gridW) / 2
            val enemyY = 8.dp
            val playerY = enemyY + cardH * 2 + gap + midGap

            fun rectOf(f: Fighter): DpRect {
                val r = slotRect(f.slots, f.side == Side.ENEMY, cardW, cardH, gap)
                val oy = if (f.side == Side.ENEMY) enemyY else playerY
                return r.copy(x = r.x + ox, y = r.y + oy)
            }
            fun centerOf(uid: Int): Pair<Dp, Dp>? {
                val f = ui.fighter(uid) ?: return null
                val r = rectOf(f)
                return (r.x + r.w / 2) to (r.y + r.h / 2)
            }

            // ---- corridor / table
            // always shown: the scenery scrolls while walking and stays put while the party fights.
            // Distance = finished walks + the current one, so it continues seamlessly from wave to wave.
            Corridor(dungeon.scenery, dungeon.sceneryInner, ui.shownWave + ui.walk.value, ui.walking, ox, gridW, Modifier.fillMaxSize())
            // ---- loot tray (left side): dropped cards fly here
            val trayW = 150.dp
            val trayX = ((ox - trayW) / 2).coerceAtLeast(8.dp)
            val trayY = 16.dp
            LootTray(ui.loot, ui.gold, ui.goldMultiplier > 1, model.state.playerLevel, Modifier.offset(trayX, trayY).width(trayW))
            // empty spots
            for (side in listOf(true, false)) for (slot in 0..5) {
                val r = slotRect(listOf(slot), side, cardW, cardH, gap)
                Box(
                    Modifier.offset(r.x + ox, r.y + if (side) enemyY else playerY).size(r.w, r.h)
                        .alpha(if (side && ui.walking) 0f else 1f)
                        .border(2.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                )
            }
            // ---- cards
            val walkBob = if (ui.walking) sin(ui.walk.value * PI * 14).toFloat() else 0f
            val all = ui.battle.party + ui.enemies
            for ((i, f) in all.withIndex()) {
                val r = rectOf(f)
                var dx = 0.dp
                var dy = 0.dp
                val l = ui.lunge
                if (l != null && l.first == f.uid) {
                    val a = centerOf(l.first); val b = centerOf(l.second)
                    if (a != null && b != null) {
                        val k = sin(ui.lungeProgress.value * PI).toFloat() * 0.45f
                        dx = (b.first - a.first) * k; dy = (b.second - a.second) * k
                    }
                }
                if (f.side == Side.PLAYER) dy += (walkBob * 5).dp
                var fly = 0f
                if (ui.flying == f.uid) {
                    fly = ui.flyProgress.value
                    // the card's center flies to the top of the loot tray
                    dx += (trayX + trayW / 2 - (r.x + r.w / 2)) * fly
                    dy += (trayY + 70.dp - (r.y + r.h / 2)) * fly
                }
                var dealAlpha = 1f
                if (f.side == Side.ENEMY) {
                    val p = ((ui.deal.value * 1.6f) - (i - ui.battle.party.size) * 0.12f).coerceIn(0f, 1f)
                    dy -= ((1 - p) * 300).dp
                    dealAlpha = p
                }
                if (f !in ui.loot) BattleCard(ui, f, r, dx, dy, dealAlpha, fly)
            }
            // ---- projectiles
            for (p in ui.projectiles) {
                val a = centerOf(p.from); val b = centerOf(p.to)
                if (a != null && b != null) {
                    val k = ui.projectileProgress.value
                    Text(
                        p.emoji, fontSize = 34.sp,
                        modifier = Modifier.zIndex(5f).offset(a.first + (b.first - a.first) * k - 20.dp, a.second + (b.second - a.second) * k - 22.dp)
                            .scale(1f + sin(k * PI).toFloat() * 0.6f),
                    )
                }
            }
            // ---- skill call-outs
            ui.skillCall?.let { (uid, text) ->
                centerOf(uid)?.let { (x, y) ->
                    Text(
                        text, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF2A2000),
                        // drawn above all cards, just above the top edge of the acting card
                        modifier = Modifier.zIndex(10f).offset(x - 90.dp, (y - cardH / 2 - 40.dp).coerceAtLeast(0.dp)).width(180.dp)
                            .background(Palette.gold, RoundedCornerShape(12.dp)).padding(4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // ---- floating numbers
            for (fl in ui.floaters) {
                key(fl.id) {
                    centerOf(fl.uid)?.let { (x, y) -> Box(Modifier.zIndex(6f)) { FloatingText(fl, x, y, ui.speed) { ui.dropFloater(fl.id) } } }
                }
            }
            // ---- banner
            ui.banner?.let {
                Text(
                    it, fontSize = 40.sp, fontWeight = FontWeight.Black, color = Palette.gold, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).zIndex(11f).background(Color(0xBB000000), RoundedCornerShape(20.dp)).padding(horizontal = 36.dp, vertical = 14.dp),
                )
            }
            ui.decision?.let { DecisionPanel(ui, it, Modifier.zIndex(12f).align(Alignment.CenterEnd).padding(16.dp).width((ox - 32.dp).coerceIn(220.dp, 320.dp))) }
            ui.outcome?.let { Box(Modifier.matchParentSize().zIndex(20f)) { ResultOverlay(model, fight, it) } }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Run away?") },
            text = { Text("You will lose this fight and the entry fee.") },
            confirmButton = {
                Button({
                    confirmLeave = false
                    model.update { it.copy(losses = it.losses + 1) }
                    model.fight = null
                }) { Text("Run away") }
            },
            dismissButton = { TextButton({ confirmLeave = false }) { Text("Keep fighting!") } },
        )
    }
}

@Composable
private fun BattleCard(ui: BattleUi, f: Fighter, r: DpRect, dx: Dp, dy: Dp, dealAlpha: Float, fly: Float) {
    val gone = ui.removed[f.uid] == true
    val vanish by animateFloatAsState(if (gone) 0f else 1f, tween(450))
    val shakeCount = ui.shakes[f.uid] ?: 0
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeCount) {
        if (shakeCount > 0) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(260))
        }
    }
    val sx = (sin(shake.value * PI * 6) * (1 - shake.value) * 7).toFloat().dp
    val isDeciding = ui.decision?.uid == f.uid
    val pulse = rememberInfiniteTransition().animateFloat(1f, 1.06f, infiniteRepeatable(tween(500), RepeatMode.Reverse))
    if (vanish <= 0.01f) return
    Box(
        Modifier.offset(r.x + dx + sx, r.y + dy)
            .zIndex(if (ui.actor == f.uid || fly > 0f) 2f else 0f)
            .alpha(vanish * dealAlpha)
            .scale((0.6f + 0.4f * vanish) * (if (isDeciding) pulse.value else 1f) * (1f - 0.6f * fly))
            .rotate((1 - vanish) * (if (f.side == Side.PLAYER) -25f else 25f) + fly * 360f)
    ) {
        CardFace(
            CardLook(f.type, f.rarity, f.evo, f.level, f.maxHp, f.atk),
            width = r.w, height = r.h,
            currentHp = ui.hp[f.uid] ?: f.hp, maxHp = f.maxHp,
            selected = ui.actor == f.uid,
        ) {
            if (ui.battle.guardOf(f) > 0 && f.alive) {
                Text("🛡️", fontSize = 40.sp, modifier = Modifier.align(Alignment.Center).alpha(0.75f))
            }
            if (f.stunned && f.alive) StunnedOverlay()
            if (f.alive && (f.burning > 0 || f.poison > 0)) {
                Text(
                    (if (f.burning > 0) Affliction.BURNING.icon else "") + (if (f.poison > 0) Affliction.POISON.icon else ""),
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 18.dp)
                        .background(Color(0xAA000000), CircleShape).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (f.skill != null && f.side == Side.PLAYER && f.alive) {
                Text(
                    if (f.cooldown > 0) "⏳${f.cooldown}" else "✨",
                    fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 18.dp)
                        .background(Color(0xAA000000), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Stunned card: greyed out, with stars circling above its head. */
@Composable
internal fun BoxScope.StunnedOverlay() {
    val spin = rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
    Box(Modifier.matchParentSize().background(Color(0x664A4A6A)))
    BoxWithConstraints(Modifier.matchParentSize()) {
        val cx = maxWidth / 2
        val cy = maxHeight * 0.2f
        for (k in 0 until 3) {
            val a = (spin.value + k / 3f) * 2 * PI
            val x = cx + maxWidth * 0.3f * kotlin.math.cos(a).toFloat()
            val y = cy + maxHeight * 0.06f * sin(a).toFloat()
            val front = sin(a) > 0
            Text(
                if (k == 1) "💫" else "⭐", fontSize = (maxWidth.value * 0.16f).sp,
                modifier = Modifier.offset(x - maxWidth * 0.08f, y - maxWidth * 0.08f).scale(if (front) 1.15f else 0.8f).alpha(if (front) 1f else 0.7f),
            )
        }
    }
    Text(
        "STUNNED", color = Color(0xFFFFE066), fontSize = 11.sp, fontWeight = FontWeight.Black,
        // just above the stats, so the picture stays visible
        modifier = Modifier.align(Alignment.Center).offset(y = 26.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun FloatingText(fl: Floater, x: Dp, y: Dp, speed: Float, onDone: () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        a.animateTo(1f, tween((1000 / speed).toInt().coerceAtLeast(350)))
        onDone()
    }
    Text(
        fl.text, fontSize = 26.sp, fontWeight = FontWeight.Black, color = fl.color,
        modifier = Modifier.offset(x - 50.dp, y - 20.dp - (a.value * 60).dp).width(100.dp).alpha(1f - a.value * a.value),
        textAlign = TextAlign.Center,
    )
}

/**
 * Corridor around the battlefield: while the party walks up to the next wave the scenery scrolls down on both sides,
 * during a fight it stands still. [distance] counts walks (1 = one walk between waves).
 */
@Composable
private fun Corridor(outer: List<String>, inner: List<String>, distance: Float, walking: Boolean, ox: Dp, gridW: Dp, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(0.dp))) {
        val spacing = 120.dp
        val n = (maxHeight / spacing).toInt() + 3
        val travelled = distance * 9f
        val shift = spacing * (travelled % 1f)
        val step = travelled.toInt()
        // the path the party walks on
        Box(
            Modifier.offset(ox - 24.dp, 0.dp).width(gridW + 48.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0x00000000), Color(0x44000000), Color(0x44000000), Color(0x00000000))))
        )
        for (i in 0 until n) {
            val y = spacing * (i - 1) + shift
            Text(outer[(i - step).mod(outer.size)], fontSize = 54.sp, modifier = Modifier.offset(ox - 150.dp, y))
            Text(inner[(i - step).mod(inner.size)], fontSize = 30.sp, modifier = Modifier.offset(ox - 70.dp, y + spacing / 2))
            Text(outer[(i - step + 2).mod(outer.size)], fontSize = 54.sp, modifier = Modifier.offset(ox + gridW + 80.dp, y + spacing / 3))
            Text(inner[(i - step + 1).mod(inner.size)], fontSize = 30.sp, modifier = Modifier.offset(ox + gridW + 30.dp, y + spacing * 5 / 6))
        }
        if (walking) Text("👣 Exploring…",color = Palette.text.copy(alpha = 0.6f), fontSize = 22.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 140.dp))
    }
}

/** Gold and cards dropped during this fight (they become the player's when the fight is won). */
@Composable
private fun LootTray(loot: List<Fighter>, gold: Long, doubled: Boolean, playerLevel: Int, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x66000000))
            .border(2.dp, Palette.gold.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎁 Loot", color = Palette.gold, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text(if (loot.isEmpty() && gold == 0L) "nothing yet" else "win to keep it!", color = Palette.textDim, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text("🪙 ${fmt(gold)}", color = Palette.gold, fontWeight = FontWeight.Black, fontSize = 20.sp)
        if (doubled) Text("first win: double gold!", color = Palette.good, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        for (row in loot.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                for (f in row) {
                    key(f.uid) {
                        val a = remember { Animatable(0.5f) }
                        LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = 0.4f)) }
                        // dropped cards join at half the monster's level (bosses drop food cards)
                        CardFace(Rules.lootCard(f, playerLevel).look(), width = 62.dp, modifier = Modifier.scale(a.value))
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionPanel(ui: BattleUi, f: Fighter, modifier: Modifier) {
    val s = f.skill ?: return
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEE1E1E2C)).border(3.dp, Palette.gold, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardArt(f.type, 30.dp)
            Text(" ${f.name}'s turn!", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Palette.gold)
        }
        Spacer(Modifier.height(6.dp))
        Text("${s.icon} ${s.title} is ready:", color = Palette.text, fontWeight = FontWeight.Bold)
        Text(s.describe(f.skillPower, f.skillLevel), color = Palette.text, fontSize = 14.sp)
        Text(cooldownText(f.skillCooldown), color = Palette.textDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button({ ui.decisionAnswer?.complete(true) }, Modifier.fillMaxWidth().height(48.dp)) { Text("✨ Use ${s.title}!", fontSize = 16.sp) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton({ ui.decisionAnswer?.complete(false) }, Modifier.fillMaxWidth()) { Text("⚔️ Normal attack (save it)") }
    }
}

@Composable
private fun BoxScope.ResultOverlay(model: AppModel, fight: ActiveFight, outcome: Outcome) {
    val s = model.state
    Box(Modifier.matchParentSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(24.dp)).background(Palette.panel)
                .border(4.dp, if (outcome is Outcome.Won) Palette.gold else Palette.panelLight, RoundedCornerShape(24.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (outcome) {
                is Outcome.Won -> {
                    val r = outcome.rewards
                    Text("🏆 Victory!", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.gold)
                    if (r.firstClear) Text("First time beating ${fight.stage.key}! Double gold!", color = Palette.good, fontSize = 16.sp)
                    StarsLine(r.fightStars, 30)
                    Text(
                        listOfNotNull(
                            "won",
                            if (fight.battle.noCardLost) "no card lost" else null,
                            if (!fight.battle.playerUsedSkill) "no skills used" else null,
                        ).joinToString(" · ") + if (r.fightStars > r.starsBefore) "  – new best! 🎉" else "",
                        color = if (r.fightStars > r.starsBefore) Palette.gold else Palette.textDim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    for (m in r.milestones) Text(
                        "🏅 ${m.stars} stars in ${Dungeons.all.first { it.id == fight.stage.dungeon }.name}! Reward: ${m.label}",
                        color = Palette.good, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("🪙 +${fmt(r.gold)} gold     ⭐ +${fmt(r.playerXp)} player XP", fontSize = 22.sp, color = Palette.text, fontWeight = FontWeight.Bold)
                    if (r.playerLevelAfter > r.playerLevelBefore) Text("🎉 Player level up! You are now level ${r.playerLevelAfter}!", fontSize = 20.sp, color = Palette.good, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("Every card in the party got 📈 +${fmt(r.cardXp)} XP", color = Palette.text)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((id, lv) in r.cardLevels) {
                            val c = s.card(id) ?: continue
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CardFace(c.look(), width = 92.dp, badge = if (lv.second > lv.first) "LEVEL UP!" else null)
                                if (lv.second > lv.first) Text("Lv ${lv.first} ➜ ${lv.second}", color = Palette.good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val newCards = r.drops + r.milestoneCards
                    if (newCards.isEmpty()) Text("No new cards this time.", color = Palette.textDim)
                    else {
                        Text("🎁 New cards!", fontSize = 22.sp, color = Palette.gold, fontWeight = FontWeight.Black)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            newCards.forEachIndexed { i, c ->
                                val a = remember { Animatable(0f) }
                                LaunchedEffect(Unit) { delay(300L + i * 250L); a.animateTo(1f, tween(450)) }
                                CardFace(c.look(), width = 110.dp, badge = "NEW!", modifier = Modifier.scale(a.value).alpha(a.value))
                            }
                        }
                    }
                }
                Outcome.Lost -> {
                    Text("😢 Your party needs a rest!", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Palette.text)
                    Spacer(Modifier.height(10.dp))
                    Text("You keep all your cards. Only the entry fee (🪙 ${fight.stage.entryFee}) is gone.", color = Palette.text, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tips: win easier stages to level up, feed spare cards to your best card, put tough cards in the front row, or try ✋ I decide to use skills at the best moment.",
                        color = Palette.textDim, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SkillModePicker(model)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ model.fight = null; model.tab = Tab.CARDS }) { Text("🃏 My cards") }
                OutlinedButton({ model.fight = null; model.tab = Tab.PARTY }) { Text("👥 Party") }
                val again = fight.stage
                Button(
                    {
                        var next: ActiveFight? = null
                        if (model.update { gs -> Rules.startFight(gs, again).let { (ns, b) -> next = ActiveFight(again, b); ns } }) model.fight = next
                    },
                    enabled = s.gold >= again.entryFee,
                ) { Text("🔁 Fight again (🪙 ${again.entryFee})") }
                val nextStage = Dungeons.all.flatMap { it.stages }.firstOrNull { it.dungeon == again.dungeon && it.stage == again.stage + 1 }
                if (nextStage != null && s.isUnlocked(nextStage)) {
                    Button(
                        {
                            var next: ActiveFight? = null
                            if (model.update { gs -> Rules.startFight(gs, nextStage).let { (ns, b) -> next = ActiveFight(nextStage, b); ns } }) model.fight = next
                        },
                        enabled = s.gold >= nextStage.entryFee,
                    ) { Text("➡️ Next: ${nextStage.key} (🪙 ${nextStage.entryFee})") }
                }
            }
        }
    }
}
