package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.content.Contest
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity

/**
 * The Contest tab: enter the endless fight with a ticket, buy passive abilities for hero cards with the diamonds won there,
 * or (as a fallback) gold and legendary cakes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContestScreen(model: AppModel) {
    val s = model.state
    var confirm by remember { mutableStateOf<OwnedCard?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- the arena
        PanelBox(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏟️ Contest", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Palette.gold, modifier = Modifier.weight(1f))
                Text("🎟️ ${s.contestTickets}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.text)
                Spacer(Modifier.width(18.dp))
                Text("💎 ${fmt(s.diamonds)}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = DIAMOND)
            }
            Text(
                "Wave after wave of monsters, each one stronger than the one before. Sooner or later your party falls, " +
                    "but every beaten monster gives 💎 diamonds (the stronger, the more) and you keep them all. No gold, no cards here.",
                color = Palette.textDim, fontSize = 14.sp,
            )
            Text("🎟️ One entry ticket a day. Your record: ${s.contestBestWaves} ${if (s.contestBestWaves == 1) "wave" else "waves"}.", color = Palette.text, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Party: ", color = Palette.text)
                for (c in s.partyCards) CardArt(c.type, 28.dp)
                Spacer(Modifier.width(8.dp))
                TextButton({ model.tab = Tab.PARTY }) { Text("Change party") }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    var started: ActiveFight? = null
                    if (model.update { gs -> Rules.startContest(gs).let { (ns, b) -> started = ActiveFight(null, b); ns } }) model.fight = started
                },
                enabled = s.contestTickets > 0 && s.partyCards.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (s.contestTickets > 0) "⚔️ Enter the contest  (🎟️ 1 of ${s.contestTickets})" else "No ticket left – come back tomorrow!", fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            SkillModePicker(model)
        }

        // ---- passive abilities
        PanelBox(Modifier.fillMaxWidth()) {
            Text("💎 Passive abilities", fontSize = 22.sp, fontWeight = FontWeight.Black, color = DIAMOND)
            Text(
                "Diamonds teach a hero card a passive ability of its class. It is always on, in every fight, and it doesn't count as " +
                    "using a skill (the ★ for no skills is still yours). Every level costs ten times more than the one before.",
                color = Palette.textDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            for (p in Passive.entries) {
                val heroes = s.cards.filter { it.type == p.hero }.sortedByDescending { it.rank }
                PassiveRow(p, heroes, s.diamonds, s::inParty) { confirm = it }
                Spacer(Modifier.height(10.dp))
            }
        }

        // ---- shop
        PanelBox(Modifier.fillMaxWidth()) {
            Text("🛒 Diamond shop", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Text("Nothing left to teach? Diamonds buy gold and cake, too.", color = Palette.textDim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShopOffer("🪙 ${fmt(Contest.GOLD_PACK_GOLD)} gold", Contest.GOLD_PACK_PRICE, s.diamonds) {
                    model.update(Rules::buyGoldWithDiamonds)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CardFace(OwnedCard(0, CardType.FOOD, Rarity.LEGENDARY).look(), width = 90.dp)
                    Spacer(Modifier.height(4.dp))
                    ShopOffer("Legendary cake", Contest.LEGENDARY_CAKE_PRICE, s.diamonds) { model.update(Rules::buyCakeWithDiamonds) }
                }
            }
            Text(
                "(A legendary cake costs 🪙 ${fmt(Balance.foodPrice(Rarity.LEGENDARY))} in the Cards shop.)",
                color = Palette.textDim, fontSize = 12.sp,
            )
        }
    }
    confirm?.let { c -> ConfirmPassive(model, c) { confirm = null } }
}

/** One class: what its passive does, then every card of that hero with its level and the upgrade button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassiveRow(p: Passive, heroes: List<OwnedCard>, diamonds: Long, inParty: (Long) -> Boolean, onPick: (OwnedCard) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22000000))
            .border(1.dp, Palette.panelLight, RoundedCornerShape(12.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardArt(p.hero, 30.dp)
            Text(" ${p.hero.title}: ${p.icon} ${p.title}", color = Palette.gold, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Text("Level 1: ${p.describe(1)}", color = Palette.text, fontSize = 13.sp)
        Text("Level 2: ${p.describe(2)}", color = Palette.textDim, fontSize = 12.sp)
        if (heroes.isEmpty()) {
            Text("You have no ${p.hero.title} card yet.", color = Palette.textDim, fontSize = 12.sp)
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in heroes) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
                CardFace(c.look(), width = 96.dp, badge = if (inParty(c.id)) "IN PARTY" else null)
                if (c.passiveLevel >= Passive.MAX_LEVEL) Text("Max level!", color = DIAMOND, fontSize = 13.sp)
                else {
                    val cost = Passive.cost(c.passiveLevel)
                    Button(
                        { onPick(c) }, enabled = diamonds >= cost, contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DIAMOND, contentColor = Color(0xFF0A2236)),
                    ) { Text("Lv ${c.passiveLevel + 1}: 💎 ${fmt(cost)}", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ShopOffer(label: String, price: Long, diamonds: Long, buy: () -> Unit) {
    Button(
        buy, enabled = diamonds >= price,
        colors = ButtonDefaults.buttonColors(containerColor = DIAMOND, contentColor = Color(0xFF0A2236)),
    ) { Text("$label  –  💎 ${fmt(price)}") }
}

@Composable
private fun ConfirmPassive(model: AppModel, c: OwnedCard, close: () -> Unit) {
    val p = Passive.of(c.type) ?: return close()
    val s = model.state
    val cost = Passive.cost(c.passiveLevel)
    val next = c.copy(passiveLevel = c.passiveLevel + 1)
    val better = Rules.strongerTwin(s, c)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("💎 Teach ${p.icon} ${p.title}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardFace(c.look(), width = 100.dp)
                    Text("  ➜  ", fontSize = 24.sp, color = Palette.text)
                    CardFace(next.look(), width = 100.dp, selected = true)
                }
                Text("Lv ${c.level} ${c.fullName}", fontWeight = FontWeight.Bold, color = c.rarity.uiColor())
                if (c.passiveLevel > 0) Text("Now: ${p.describe(c.passiveLevel)}", color = Palette.textDim)
                Text("Level ${next.passiveLevel}: ${p.describe(next.passiveLevel)}", color = Palette.text)
                Text("Costs 💎 ${fmt(cost)} diamonds (you have ${fmt(s.diamonds)}).", color = Palette.textDim)
                if (better != null) Text(
                    "⚠️ You have a stronger ${c.type.title} (Lv ${better.level} ${better.fullName}). Teach that one instead?",
                    color = Color(0xFFFFC34D),
                )
            }
        },
        confirmButton = {
            Button({
                if (model.update { Rules.upgradePassive(it, c.id) })
                    model.message = "💎 Your ${c.fullName} learned ${p.icon} ${p.title} level ${next.passiveLevel}!"
                close()
            }) { Text("Yes, teach it!") }
        },
        dismissButton = { TextButton(close) { Text("No") } },
    )
}
