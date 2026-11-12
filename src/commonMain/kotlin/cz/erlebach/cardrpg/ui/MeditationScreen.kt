package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard

/** "Wait N rounds after using it." for a skill cooldown (0 = every turn). */
fun cooldownText(cooldown: Int) = when (cooldown) {
    0 -> "No waiting – it can be used every turn!"
    1 -> "Wait 1 round after using it."
    else -> "Wait $cooldown rounds after using it."
}

/**
 * Meditation: the best rare-or-better card of every hero sits on its mat and finds clues of its class every night.
 * Clues (some of every class) shorten the skill cooldown of a rare-or-better hero card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeditationScreen(model: AppModel) {
    val s = model.state
    val meditators = s.meditators
    var confirm by remember { mutableStateOf<OwnedCard?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- mats
        PanelBox(Modifier.fillMaxWidth()) {
            Text("$LOTUS Meditation", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Palette.gold)
            Text(
                "The best ${Balance.MEDITATION_RARITY.label} or better card of every hero meditates here (it can still fight). " +
                    "Every night at midnight it finds clues of its class. The rarer the card, the more clues!",
                color = Palette.textDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (t in CardType.heroes) Mat(t, meditators[t])
            }
        }
        // ---- clue inventory
        PanelBox(Modifier.fillMaxWidth()) {
            Text("🔎 Your clues", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (t in CardType.heroes) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ClueCoin(t, 52.dp)
                    Text(fmt(s.clues(t)), color = Palette.gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("${t.title} clues", color = Palette.textDim, fontSize = 11.sp)
                }
            }
        }
        // ---- spending
        PanelBox(Modifier.fillMaxWidth()) {
            Text("⏳ Shorten a skill cooldown", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Text(
                "Every step costs clues of ALL ${CardType.heroes.size} classes and makes the skill of one ${Balance.MEDITATION_RARITY.label} or " +
                    "better hero card ready one round sooner. Each $LOTUS on a card is one step.",
                color = Palette.textDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (cuts in 0 until Balance.MAX_COOLDOWN_CUTS) {
                    val cost = Balance.cooldownCutCost(cuts)
                    val enough = CardType.heroes.all { s.clues(it) >= cost }
                    val from = Balance.SKILL_COOLDOWN - cuts
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(if (enough) Palette.good.copy(alpha = 0.18f) else Color(0x33000000))
                            .border(2.dp, if (enough) Palette.good else Palette.panelLight, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(LOTUS.repeat(cuts + 1), fontSize = 14.sp)
                        Text("⏳ $from ➜ ${from - 1}", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${fmt(cost)} of each clue", color = if (enough) Palette.good else Palette.textDim, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val eligible = s.cards.filter { Rules.canMeditate(it) && Rules.cooldownCutProblem(s, it) == null }
                .sortedWith(compareBy<OwnedCard> { it.type.ordinal }.thenByDescending { it.rank })
            if (eligible.isEmpty()) {
                Text("Not enough clues yet – no hero card can be upgraded. Let your heroes meditate!", color = Palette.textDim, fontSize = 15.sp)
            } else {
                Text("These cards can be upgraded now:", color = Palette.good, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in eligible) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(124.dp)) {
                        CardFace(c.look(), width = 110.dp, badge = if (s.inParty(c.id)) "IN PARTY" else null)
                        Text("${c.type.skill.icon} ⏳ ${c.skillCooldown} ➜ ${c.skillCooldown - 1}", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Button({ confirm = c }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("$LOTUS ${fmt(Balance.cooldownCutCost(c.cooldownCuts))} each", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { c -> ConfirmCut(model, c) { confirm = null } }
}

@Composable
private fun ConfirmCut(model: AppModel, c: OwnedCard, close: () -> Unit) {
    val s = model.state
    val cost = Balance.cooldownCutCost(c.cooldownCuts)
    val better = Rules.strongerTwin(s, c)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("$LOTUS Shorten the cooldown?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardFace(c.look(), width = 100.dp)
                    Text("  ➜  ", fontSize = 24.sp, color = Palette.text)
                    CardFace(c.copy(cooldownCuts = c.cooldownCuts + 1).look(), width = 100.dp, selected = true)
                }
                Text("Lv ${c.level} ${c.fullName}: ${c.type.skill.icon} ${c.type.skill.title}", fontWeight = FontWeight.Bold, color = c.rarity.uiColor())
                Text("Cooldown ${c.skillCooldown} ➜ ${c.skillCooldown - 1} rounds. ${cooldownText(c.skillCooldown - 1)}")
                Text("Costs ${fmt(cost)} clues of every class (${fmt(cost * CardType.heroes.size)} in total).", color = Palette.textDim)
                if (better != null) Text(
                    "⚠️ You have a stronger ${c.type.title} (Lv ${better.level} ${better.fullName}). Upgrade that one instead?",
                    color = Color(0xFFFFC34D),
                )
            }
        },
        confirmButton = {
            Button({
                if (model.update { Rules.cutCooldown(it, c.id) })
                    model.message = "$LOTUS ${c.type.skill.title} of your ${c.fullName} now has cooldown ${c.skillCooldown - 1}!"
                close()
            }) { Text("Yes, upgrade!") }
        },
        dismissButton = { TextButton(close) { Text("No") } },
    )
}

/** One meditation mat: the meditating card (or a faded picture of the hero) above a round cushion. */
@Composable
private fun Mat(type: CardType, card: OwnedCard?) {
    Column(Modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(140.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            if (card != null) CardFace(card.look(), width = 96.dp)
            else CardArt(type, 84.dp, Modifier.alpha(0.18f).padding(bottom = 10.dp))
        }
        Cushion(type, Modifier.offset(y = (-10).dp))
        Text(type.title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.offset(y = (-6).dp))
        if (card != null) Text(
            Balance.cluesPerDay(card.rarity, card.evo).let { "+$it ${if (it == 1L) "clue" else "clues"} / night" }, color = Palette.good, fontSize = 12.sp,
            modifier = Modifier.offset(y = (-6).dp),
        )
        else Text(
            "Needs a ${Balance.MEDITATION_RARITY.label} ${type.title}", color = Palette.textDim, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.offset(y = (-6).dp),
        )
    }
}

/** A round sitting cushion in the hero's colours, with its clue coin stitched on the front. */
@Composable
private fun Cushion(type: CardType, modifier: Modifier = Modifier) {
    Box(modifier.size(120.dp, 40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val top = Color(type.bgTop)
            val bottom = Color(type.bgBottom)
            // shadow, side, top
            drawOval(Color(0x55000000), Offset(4f, size.height * 0.35f), Size(size.width - 8f, size.height * 0.65f))
            drawOval(bottom, Offset(0f, size.height * 0.18f), Size(size.width, size.height * 0.72f))
            drawOval(Brush.verticalGradient(listOf(top, bottom)), Offset(0f, 0f), Size(size.width, size.height * 0.68f))
            drawOval(Color(0x66FFFFFF), Offset(6f, 3f), Size(size.width - 12f, size.height * 0.62f), style = Stroke(width = 2f))
        }
        ClueCoin(type, 26.dp, Modifier.offset(y = 6.dp))
    }
}

/** A clue: a gold coin with the hero's picture inside. */
@Composable
fun ClueCoin(type: CardType, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size)
            .background(Brush.radialGradient(listOf(Color(0xFFFFF1A8), Color(0xFFE0A21E), Color(0xFF9A6A10))), CircleShape)
            .border((size.value / 18).dp, Color(0xFF6A4A08), CircleShape)
            .padding((size.value / 9).dp)
            .background(Brush.verticalGradient(listOf(Color(type.bgTop), Color(type.bgBottom))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { CardArt(type, size * 0.62f) }
}
