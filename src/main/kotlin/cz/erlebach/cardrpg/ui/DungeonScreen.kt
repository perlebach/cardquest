package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.battle.SkillMode
import cz.erlebach.cardrpg.content.DungeonDef
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.content.MonsterSpec
import cz.erlebach.cardrpg.content.StageDef
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import kotlin.math.roundToInt

@Composable
fun DungeonScreen(model: AppModel) {
    val s = model.state
    val allStages = Dungeons.all.flatMap { it.stages }
    var selectedKey by remember {
        mutableStateOf(allStages.lastOrNull { s.isUnlocked(it) && it.key !in s.cleared }?.key ?: allStages.last { s.isUnlocked(it) }.key)
    }
    val selected = Dungeons.stage(selectedKey)!!
    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            // locked dungeons stay hidden until they open
            for (d in Dungeons.all.filter { s.isDungeonUnlocked(it.id) }) {
                // a dungeon with all its stars is folded up (it can still be opened)
                var expanded by remember(d.id, s.dungeonComplete(d)) { mutableStateOf(!s.dungeonComplete(d)) }
                DungeonPanel(model, d, selectedKey, expanded, { expanded = !expanded }) { selectedKey = it }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        PanelBox(Modifier.width(470.dp).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState())) { StageDetail(model, selected) }
        }
    }
}

@Composable
private fun DungeonPanel(model: AppModel, d: DungeonDef, selectedKey: String, expanded: Boolean, onToggle: () -> Unit, onSelect: (String) -> Unit) {
    val s = model.state
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(d.bg), Color(d.bg).copy(alpha = 0.6f))))
            .border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        val stars = s.dungeonStars(d)
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▼ " else "▶ ", fontSize = 18.sp, color = Palette.textDim)
            Text("${d.emoji} ${d.id}. ${d.name}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Palette.text)
            if (s.dungeonComplete(d)) Text("  🏆 Complete!", fontSize = 16.sp, color = Palette.good, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("⭐ $stars / ${d.maxStars}", color = Palette.gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(6.dp))
        // star rewards
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in d.milestones) {
                val got = Rules.milestoneKey(d, m) in s.claimedMilestones
                Text(
                    (if (got) "✅ " else "") + "${m.stars}⭐: ${m.label}",
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (got) Color(0x552E7D4A) else Color(0x44000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (got) Palette.good else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        for (row in d.stages.chunked(5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                for (st in row) StageTile(st, s.isUnlocked(st), s.stars(st.key), st.key == selectedKey) { onSelect(st.key) }
            }
        }
    }
}

@Composable
private fun StageTile(st: StageDef, unlocked: Boolean, stars: Int, selected: Boolean, onClick: () -> Unit) {
    val cleared = stars > 0
    val bg = when {
        !unlocked -> Color(0x88000000)
        cleared -> Color(0xFF2E7D4A)
        else -> Color(0xFFB8860B)
    }
    Box(
        Modifier.size(96.dp, 78.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .border(if (selected) 4.dp else 2.dp, if (selected) Palette.gold else Color(0x66FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.key, fontSize = 22.sp, fontWeight = FontWeight.Black, color = if (unlocked) Color.White else Color(0x88FFFFFF))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !unlocked -> Text("🔒", fontSize = 14.sp)
                    cleared -> StarsLine(stars, 16)
                    else -> Text("NEW!", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (st.hasBoss) Text(" 👑", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun StageDetail(model: AppModel, st: StageDef) {
    val s = model.state
    val unlocked = s.isUnlocked(st)
    val firstClear = st.key !in s.cleared
    Text("Stage ${st.key}" + if (st.hasBoss) "  👑 Boss!" else "", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Palette.gold)
    Spacer(Modifier.height(6.dp))
    Text("🎟️ Entry fee: 🪙 ${st.entryFee}", color = Palette.text, fontSize = 16.sp)
    Text("🏆 Win: up to 🪙 ${fmt(st.maxGold * (if (firstClear) 2 else 1))}" + (if (firstClear) "  (double for the first win!)" else ""), color = Palette.text, fontSize = 16.sp)
    Text("Every beaten monster gives gold – except the ones that join you as a card.", color = Palette.textDim, fontSize = 12.sp)
    Text("⭐ ${fmt(st.playerXp)} player XP,  📈 ${fmt(st.cardXp)} XP for every card in the party", color = Palette.text, fontSize = 14.sp)
    if (st.hasBoss) Text("👑 The boss may drop a 🍰 food card (${(Balance.FOOD_DROP_CHANCE * 100).roundToInt()}%).", color = Palette.textDim, fontSize = 12.sp)
    st.bossTrait?.let { tr ->
        Spacer(Modifier.height(6.dp))
        Text("${tr.icon} ${tr.title}: ${tr.description}", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Best answer: ${tr.counter.icon} ${tr.counter.title} – the ${tr.counterHero.title} or a monster with that skill.", color = Palette.gold, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Your best: ", color = Palette.text, fontSize = 14.sp)
        StarsLine(s.stars(st.key), 20)
    }
    Text("★ win  ·  +★ no card knocked out  ·  +★ no skill used – all in the same fight", color = Palette.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    for ((i, w) in st.waves.withIndex()) {
        Text("Wave ${i + 1}" + if (w.any { it.type.boss }) " – the boss!" else "", color = Palette.textDim, fontWeight = FontWeight.Bold)
        EnemyPreview(w, 66.dp)
        Spacer(Modifier.height(6.dp))
    }
    Text("Monsters you beat may join you (the % is the chance). They join at ${(Balance.LOOT_LEVEL_SHARE * 100).roundToInt()} % of the monster's level.", color = Palette.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Party: ", color = Palette.text)
        for (c in s.partyCards) CardArt(c.type, 28.dp)
        Spacer(Modifier.weight(1f))
        TextButton({ model.tab = Tab.PARTY }) { Text("Change party") }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            var started: ActiveFight? = null
            val ok = model.update { gs ->
                val (ns, battle) = Rules.startFight(gs, st)
                started = ActiveFight(st, battle)
                ns
            }
            if (ok) model.fight = started
        },
        enabled = unlocked && s.gold >= st.entryFee,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(
            when {
                !unlocked -> "🔒 Locked"
                s.gold < st.entryFee -> "Not enough gold – sell some cards!"
                else -> "⚔️ Fight!  (🪙 ${st.entryFee})"
            }, fontSize = 20.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    SkillModePicker(model)
}

/** The 3 stars of a stage: gold ★ = earned, dim ☆ = not yet. */
@Composable
fun StarsLine(stars: Int, size: Int) {
    Row {
        for (f in (1..3).map { it <= stars }) Text(if (f) "★" else "☆", fontSize = size.sp, lineHeight = size.sp, color = if (f) Palette.gold else Color(0x99FFFFFF), fontWeight = FontWeight.Black)
    }
}

val SKILL_MODES = listOf(SkillMode.AUTO to "🤖 Auto", SkillMode.ASK to "✋ I decide", SkillMode.NEVER to "🚫 Never")

/** Choose how party cards use skills before the fight starts (it can still be changed during the fight). */
@Composable
fun SkillModePicker(model: AppModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Skills:", color = Palette.text)
        for ((m, label) in SKILL_MODES) {
            Spacer(Modifier.width(6.dp))
            FilterChip(model.state.skillMode == m, { model.update { it.copy(skillMode = m) } }, { Text(label) })
        }
    }
}

/** Small 2x3 grid preview of a wave (back row on top, like in battle). */
@Composable
fun EnemyPreview(wave: List<MonsterSpec>, cardWidth: Dp) {
    val h = cardWidth * 1.4f
    val gap = 6.dp
    Box(Modifier.size(cardWidth * 3 + gap * 2, h * 2 + gap)) {
        for (slot in 0..5) {
            val r = slotRect(listOf(slot), enemy = true, cardWidth, h, gap)
            Box(Modifier.offset(r.x, r.y).size(r.w, r.h).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)))
        }
        for (m in wave) {
            val r = slotRect(m.slots, enemy = true, cardWidth, h, gap)
            val look = CardLook(m.type, m.rarity, m.evo, m.level,
                Balance.hp(m.type, m.level, m.rarity, m.evo, m.slots.size), Balance.atk(m.type, m.level, m.rarity, m.evo))
            Box(Modifier.offset(r.x, r.y)) {
                CardFace(look, width = r.w, height = r.h,
                    badge = if (m.dropChance > 0) "${(m.dropChance * 100).roundToInt()}%" else null)
            }
        }
    }
}

data class DpRect(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

/**
 * Position of a card covering [slots] on a 2x3 grid. Enemy grids show the back row on top,
 * player grids show the front row on top (front rows face each other).
 */
fun slotRect(slots: List<Int>, enemy: Boolean, w: Dp, h: Dp, gap: Dp): DpRect {
    val cols = slots.map { it % 3 }
    val rows = slots.map { r -> if (enemy) 1 - r / 3 else r / 3 }
    val c0 = cols.min(); val c1 = cols.max(); val r0 = rows.min(); val r1 = rows.max()
    return DpRect(
        (w + gap) * c0, (h + gap) * r0,
        w * (c1 - c0 + 1) + gap * (c1 - c0), h * (r1 - r0 + 1) + gap * (r1 - r0),
    )
}
