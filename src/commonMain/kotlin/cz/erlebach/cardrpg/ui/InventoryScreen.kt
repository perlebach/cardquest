package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Passive
import cz.erlebach.cardrpg.model.Rarity
import kotlin.math.roundToInt

private enum class Mode { VIEW, FEED, EVOLVE, SELL, BUY }

/** Rarest first, then highest evo stage, then highest level. */
private val byRarity = compareByDescending<OwnedCard> { it.rarity.ordinal }.thenByDescending { it.evo }.thenByDescending { it.level }

@Composable
fun InventoryScreen(model: AppModel) {
    val s = model.state
    var selectedId by remember { mutableStateOf(s.partyCards.firstOrNull()?.id) }
    var mode by remember { mutableStateOf(Mode.VIEW) }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var buyItem by remember { mutableStateOf(shopItems(s).first()) }
    val selected = s.card(selectedId)
    LaunchedEffect(selected == null) { if (selected == null && mode != Mode.SELL && mode != Mode.BUY) mode = Mode.VIEW }

    fun setMode(m: Mode) { mode = m; picked = emptySet() }
    // clicking the Cards tab again leaves feeding / evolving / selling / buying, like "Back"
    val reclicks = model.tabReclicks
    LaunchedEffect(reclicks) { if (mode != Mode.VIEW) setMode(Mode.VIEW) }

    val shown: List<OwnedCard> = when (mode) {
        // party cards first, then by rarity
        Mode.VIEW -> s.cards.sortedWith(compareByDescending<OwnedCard> { s.inParty(it.id) }.then(byRarity))
        // cards with the same skill first (they can raise its skill level), then by XP they give
        Mode.FEED -> if (selected == null) emptyList() else s.cards.filter { Rules.canBeFood(s, selected, it) && !it.type.hero }
            .sortedWith(compareByDescending<OwnedCard> { Rules.sharesSkill(selected, it) }.thenByDescending { Rules.feedXp(selected, it) }.then(byRarity))
        Mode.EVOLVE -> if (selected != null) Rules.evolveMaterialCandidates(s, selected).sortedWith(byRarity) else emptyList()
        Mode.SELL -> s.cards.filter { !it.type.hero && !it.type.food && !s.inParty(it.id) }.sortedWith(compareByDescending<OwnedCard> { it.sellPrice }.then(byRarity))
        Mode.BUY -> emptyList()
    }

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (mode) {
                        Mode.VIEW -> "Your cards (${s.cards.size} / ${s.inventorySize})"
                        Mode.FEED -> "Pick cards to feed to your ${selected?.type?.title}"
                        Mode.EVOLVE -> "Pick ${selected?.let { Balance.evolveMaterials(it.evo) }}× ${selected?.rarity?.label} ${selected?.type?.title}"
                        Mode.SELL -> "Pick cards to sell"
                        Mode.BUY -> "Pick a card to buy"
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text,
                )
                Spacer(Modifier.weight(1f))
                if (mode == Mode.VIEW) {
                    OutlinedButton({ setMode(Mode.SELL) }) { Text("🪙 Sell cards…") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton({ setMode(Mode.BUY) }) { Text("🛒 Buy cards…") }
                }
            }
            if (mode == Mode.VIEW || mode == Mode.BUY) InventoryBar(model)
            Spacer(Modifier.height(8.dp))
            if (mode == Mode.BUY) {
                LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shopItems(s), key = { "${it.type}-${it.rarity}" }) { item ->
                        Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                            val have = s.cards.count { it.type == item.type && it.rarity == item.rarity }
                            CardFace(
                                item.look(), width = 124.dp, selected = item == buyItem,
                                badge = if (item.type.food) "🪙 ${fmt(Balance.foodPrice(item.rarity))}"
                                    else if (item.rarity != Balance.HERO_BUY_RARITY) "🪙 ${fmt(s.heroPrice(item.rarity))}"
                                    else have.takeIf { it > 0 }?.let { "YOU HAVE $it" },
                                modifier = Modifier.clickable { buyItem = item },
                            )
                        }
                    }
                }
            } else if (shown.isEmpty()) {
                Text(
                    when (mode) {
                        Mode.FEED -> "No cards to feed. Win fights to collect more cards! (Cards in the party can't be eaten.)"
                        Mode.EVOLVE -> "You don't have any spare ${selected?.rarity?.label} ${selected?.type?.title} cards (outside the party)."
                        Mode.SELL -> "Nothing to sell. (Hero cards, food cards and party members can't be sold.)"
                        else -> ""
                    }, color = Palette.textDim, fontSize = 16.sp, modifier = Modifier.padding(24.dp)
                )
            }
            LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { c ->
                    val isPicked = c.id in picked
                    val skillFood = mode == Mode.FEED && selected != null && Rules.sharesSkill(selected, c)
                    Box(
                        Modifier
                            .then(if (skillFood) Modifier.background(Palette.good.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).border(3.dp, Palette.good, RoundedCornerShape(14.dp)) else Modifier)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CardFace(
                            c.look(), width = 124.dp,
                            selected = if (mode == Mode.VIEW) c.id == selectedId else isPicked,
                            badge = when {
                                mode == Mode.SELL -> (if (isPicked) "✔ " else "") + "🪙 ${fmt(c.sellPrice)}"
                                mode != Mode.VIEW && isPicked -> "✔ PICKED"
                                skillFood -> "${c.type.skill.icon} +${(Balance.SKILL_UP_CHANCE[c.rarity.ordinal] * 100).roundToInt()}% skill"
                                s.inParty(c.id) -> "IN PARTY"
                                else -> null
                            },
                            modifier = Modifier.clickable {
                                if (mode == Mode.VIEW) selectedId = c.id
                                else picked = if (isPicked) picked - c.id else picked + c.id
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        PanelBox(Modifier.width(370.dp).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (mode) {
                    Mode.SELL -> SellPanel(model, picked, shown, onPick = { picked = it }, onDone = { setMode(Mode.VIEW) })
                    Mode.BUY -> BuyPanel(model, buyItem, onBought = { selectedId = it }, onDone = { setMode(Mode.VIEW) })
                    else -> if (selected == null) Text("Click a card to see it.", color = Palette.textDim)
                    else when (mode) {
                        Mode.VIEW -> CardDetail(model, selected, onFeed = { setMode(Mode.FEED) }, onEvolve = { setMode(Mode.EVOLVE) })
                        Mode.FEED -> FeedPanel(model, selected, picked, shown, onPick = { picked = it }, onDone = { setMode(Mode.VIEW) })
                        Mode.EVOLVE -> EvolvePanel(model, selected, picked, onDone = { setMode(Mode.VIEW) })
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Used / total card slots and the button that buys more of them. */
@Composable
private fun InventoryBar(model: AppModel) {
    val s = model.state
    val price = s.inventoryUpgradeCost
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "🎒 ${s.cards.size} / ${s.inventorySize} slots used" +
                if (s.inventoryFull) " – full! You can't start fights or buy cards until you make room." else "",
            color = if (s.inventoryFull) Palette.bad else Palette.textDim, fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            { if (model.update { Rules.upgradeInventory(it) }) model.message = "🎒 Your inventory now holds ${model.state.inventorySize} cards!" },
            enabled = s.gold >= price,
        ) { Text("🎒 +${Balance.INVENTORY_STEP} slots for 🪙 ${fmt(price)}") }
    }
}

@Composable
private fun CardTitle(c: OwnedCard) {
    Text(c.fullName, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c.rarity.uiColor())
    Text(
        when {
            c.type.food -> "🍰 Food card – feed it to another card: ${Balance.FOOD_XP_FACTOR}× the XP of a max-level ${c.rarity.label}${"+".repeat(c.evo)} card!"
            c.type.hero -> "👤 Hero card – can grow to a higher rarity!"
            else -> "👾 Monster card – stays ${c.rarity.label}"
        },
        color = Palette.textDim, fontSize = 13.sp,
    )
}

@Composable
private fun CardDetail(model: AppModel, c: OwnedCard, onFeed: () -> Unit, onEvolve: () -> Unit) {
    val s = model.state
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CardFace(c.look(), width = 190.dp) }
    Spacer(Modifier.height(12.dp))
    CardTitle(c)
    Spacer(Modifier.height(10.dp))
    LevelInfo(s, c)
    Spacer(Modifier.height(10.dp))
    SkillInfo(c)
    PassiveInfo(model, c)
    Spacer(Modifier.height(16.dp))
    if (c.type.food) {
        Text("Worth 📈 ${fmt(c.sacrificeValue)} XP when fed to another card.", color = Palette.good, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Pick the card that should eat it, then 🍖 Feed.", color = Palette.textDim, fontSize = 12.sp)
    } else {
        if (s.inParty(c.id)) {
            OutlinedButton({ model.update { Rules.removeFromParty(it, c.id) } }, Modifier.fillMaxWidth(), enabled = s.partyCards.size > 1) { Text("Remove from party") }
        } else if (s.party.any { it == null } && s.party.count { it != null } < Balance.MAX_PARTY) {
            Button({ model.update { Rules.addToParty(it, c.id) } }, Modifier.fillMaxWidth()) { Text("➕ Add to party") }
        } else {
            Text("The party is full (max ${Balance.MAX_PARTY} cards) – remove a card in the Party tab to make room.", color = Palette.textDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        // a max-level card can still eat cards for a chance to raise its skill (the XP is lost)
        val canLevel = c.level < c.rarity.maxLevel
        val canSkillUp = c.hasSkill && c.skillLevel < Balance.MAX_SKILL_LEVEL
        Button(onFeed, Modifier.fillMaxWidth(), enabled = canLevel || canSkillUp) {
            Text(if (canLevel || !canSkillUp) "🍖 Feed cards to level up" else "🍖 Feed cards to raise the skill")
        }
        Spacer(Modifier.height(6.dp))
        val chk = Rules.evolveCheck(s, c)
        Button(onEvolve, Modifier.fillMaxWidth(), enabled = chk is Rules.EvolveCheck.Ok) {
            Text(if (!c.atMaxEvo || c.type.hero) "✨ Evolve" else "✨ Evolve (max reached)")
        }
        if (chk is Rules.EvolveCheck.No) Text(chk.reason, color = Palette.textDim, fontSize = 12.sp)
    }
    if (!c.type.hero && !c.type.food) {
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            { model.update { Rules.sell(it, listOf(c.id)) } }, Modifier.fillMaxWidth(), enabled = !s.inParty(c.id),
        ) { Text("Sell for 🪙 ${c.sellPrice}") }
    }
}

@Composable
private fun LevelInfo(s: GameState, c: OwnedCard) {
    val cap = Rules.levelCap(s, c)
    if (c.type.food) { Text("Level 1 – food cards don't grow.", color = Palette.text, fontSize = 14.sp); return }
    Text("Level ${c.level} / ${c.rarity.maxLevel}" + if (c.level > c.rarity.maxLevel) "  💪 above the limit" else "",
        color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    if (c.level >= c.rarity.maxLevel) {
        Text(if (c.atMaxEvo && !c.type.hero) "Maximum level reached." else "Max level – ready to evolve!", color = Palette.good, fontSize = 13.sp)
    } else {
        XpBar(c.xp, c.xpToNext, Modifier.fillMaxWidth().height(10.dp))
        Text("${fmt(c.xp)} / ${fmt(c.xpToNext)} XP to next level", color = Palette.textDim, fontSize = 12.sp)
        if (c.level >= cap) Text("⚠️ Cards can't be above your player level (${s.playerLevel}). Win fights to level up!", color = Color(0xFFFFC34D), fontSize = 12.sp)
    }
}

private fun Color(v: Long) = androidx.compose.ui.graphics.Color(v)

/** The hero's passive ability (bought with diamonds), or a hint where to get it once the contest is open. */
@Composable
private fun PassiveInfo(model: AppModel, c: OwnedCard) {
    val p = Passive.of(c.type) ?: return
    Spacer(Modifier.height(8.dp))
    if (c.passiveLevel > 0) {
        Text("${p.icon} ${p.title}  (passive level ${c.passiveLevel})", color = DIAMOND, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(p.describe(c.passiveLevel), color = Palette.text, fontSize = 14.sp)
        Text("Always on, in every fight – it doesn't count as using a skill.", color = Palette.textDim, fontSize = 12.sp)
    } else if (model.state.contestUnlocked) {
        Text("${p.icon} No passive yet – teach it ${p.title} with 💎 diamonds in the 🏟️ Contest tab.", color = Palette.textDim, fontSize = 12.sp)
    }
}

@Composable
private fun SkillInfo(c: OwnedCard) {
    val sk = c.type.skill
    if (c.type.food) {
        Text("No skill – food can't fight.", color = Palette.textDim, fontSize = 14.sp)
    } else if (c.hasSkill) {
        Text("${sk.icon} ${sk.title}  (skill level ${c.skillLevel}/${Balance.MAX_SKILL_LEVEL})", color = Palette.gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(sk.describe(c.skillPower, c.skillLevel), color = Palette.text, fontSize = 14.sp)
        if (c.skillLevel < Balance.MAX_SKILL_LEVEL) sk.maxBonus?.let {
            Text("⭐ At skill level ${Balance.MAX_SKILL_LEVEL}: $it", color = Palette.textDim, fontSize = 12.sp)
        }
        Text(cooldownText(c.skillCooldown), color = Palette.textDim, fontSize = 12.sp)
        if (c.cooldownCuts > 0) Text("${LOTUS.repeat(c.cooldownCuts)} Meditation shortened the cooldown ${c.cooldownCuts}×.", color = Palette.good, fontSize = 12.sp)
        if (c.skillLevel < Balance.MAX_SKILL_LEVEL) Text(
            "Each skill level adds ${(Balance.SKILL_LEVEL_BONUS * 100).toInt()}% power. Feed cards with ${sk.icon} ${sk.title} for a chance to level it up.",
            color = Palette.textDim, fontSize = 12.sp,
        )
    } else {
        Text("No skill – common cards don't have skills.", color = Palette.textDim, fontSize = 14.sp)
        Text("(Uncommon ${c.type.title}s know ${sk.icon} ${sk.title}.)", color = Palette.textDim, fontSize = 12.sp)
    }
}

@Composable
private fun FeedPanel(model: AppModel, target: OwnedCard, picked: Set<Long>, shown: List<OwnedCard>, onPick: (Set<Long>) -> Unit, onDone: () -> Unit) {
    val s = model.state
    val food = picked.mapNotNull { s.card(it) }
    val after = Rules.feedPreview(s, target, food)
    val totalXp = food.sumOf { Rules.feedXp(target, it) }
    Text("🍖 Feeding", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
    Text("Fed cards disappear. Your card gets their XP – the higher their level, rarity and evolution, the more (🍰 food cards: ${Balance.FOOD_XP_FACTOR}× a max-level card of their rarity!). Uncommon or better cards with the same skill (${target.type.skill.icon} ${target.type.skill.title}) give +${(Balance.SAME_SKILL_XP_BONUS * 100).roundToInt()}% XP and a chance to raise the skill level – feeding several at once adds the chances together!", color = Palette.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardFace(target.look(), width = 110.dp)
        Text("  ➜  ", fontSize = 26.sp, color = Palette.text)
        CardFace(after.look(), width = 110.dp, selected = after.level > target.level)
    }
    Spacer(Modifier.height(10.dp))
    Text("Picked: ${food.size} cards = ${fmt(totalXp)} XP", color = Palette.text, fontWeight = FontWeight.Bold)
    Text("Level ${target.level} ➜ ${after.level}", color = if (after.level > target.level) Palette.good else Palette.text, fontSize = 18.sp)
    val chance = Rules.skillUpChance(target, food)
    if (chance > 0) Text(
        "🎲 ${(chance * 100).roundToInt()}% chance: skill level ${target.skillLevel} ➜ ${target.skillLevel + 1}",
        color = Palette.good, fontSize = 16.sp,
    )
    val wasted = (target.totalXp + totalXp) - after.totalXp
    if (wasted > 0) Text("⚠️ ${fmt(wasted)} XP will be lost – the card hits its level limit.", color = Color(0xFFFFC34D), fontSize = 12.sp)
    val passiveFood = food.filter { it.passiveLevel > 0 }
    if (passiveFood.isNotEmpty()) Text(
        "⚠️ 💎 ${passiveFood.joinToString { "Lv ${it.level} ${it.fullName}" }} knows a passive ability – it will be lost!",
        color = Color(0xFFFFC34D), fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton({ onPick(shown.filter { it.rarity == Rarity.COMMON && !it.type.food }.map { it.id }.toSet()) }, Modifier.fillMaxWidth()) { Text("Pick all common cards") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton({ onPick(shown.filter { it.type.food }.map { it.id }.toSet()) }, Modifier.fillMaxWidth(), enabled = shown.any { it.type.food }) { Text("🍰 Pick all food cards") }
    Spacer(Modifier.height(6.dp))
    Button(
        {
            if (model.update { Rules.feed(it, target.id, picked.toList()) }) {
                val newLevel = model.state.card(target.id)?.skillLevel ?: target.skillLevel
                if (newLevel > target.skillLevel) model.message = "🎉 Lucky! ${target.type.skill.icon} ${target.type.skill.title} is now skill level $newLevel!"
                else if (chance > 0) model.message = "Yum! No skill level-up this time (it was a ${(chance * 100).roundToInt()}% chance)."
                onDone()
            }
        },
        Modifier.fillMaxWidth(), enabled = food.isNotEmpty(),
    ) { Text("🍖 Feed!") }
    TextButton(onDone, Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun EvolvePanel(model: AppModel, c: OwnedCard, picked: Set<Long>, onDone: () -> Unit) {
    val s = model.state
    val chk = Rules.evolveCheck(s, c)
    Text("✨ Evolution", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
    if (chk !is Rules.EvolveCheck.Ok) {
        Text((chk as Rules.EvolveCheck.No).reason, color = Palette.text)
        TextButton(onDone) { Text("Back") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardFace(c.look(), width = 110.dp)
        Text("  ➜  ", fontSize = 26.sp, color = Palette.text)
        CardFace(chk.result.look(), width = 110.dp, selected = true)
    }
    Spacer(Modifier.height(8.dp))
    Text("${c.fullName} ➜ ${chk.result.fullName}", color = chk.result.rarity.uiColor(), fontWeight = FontWeight.Bold)
    Text("The card goes back to level 1, but it will be much stronger when it grows again!", color = Palette.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    fun req(ok: Boolean, text: String) = (if (ok) "✅ " else "❌ ") + text
    Text(req(true, "Level ${c.rarity.maxLevel}"), color = Palette.text)
    Text(req(s.gold >= chk.gold, "🪙 ${chk.gold} gold"), color = Palette.text)
    Text(req(picked.size == chk.materials, "${picked.size} / ${chk.materials}× ${chk.materialRarity.label} ${c.type.title} picked"), color = Palette.text)
    val cutMats = picked.mapNotNull { s.card(it) }.filter { it.cooldownCuts > 0 }
    if (cutMats.isNotEmpty()) Text(
        "⚠️ ${LOTUS} ${cutMats.joinToString { "Lv ${it.level} ${it.fullName}" }} had a shorter cooldown from meditation – it will be lost!",
        color = Color(0xFFFFC34D), fontSize = 12.sp,
    )
    val passiveMats = picked.mapNotNull { s.card(it) }.filter { it.passiveLevel > 0 }
    if (passiveMats.isNotEmpty()) Text(
        "⚠️ 💎 ${passiveMats.joinToString { "Lv ${it.level} ${it.fullName}" }} knows a passive ability – it will be lost!",
        color = Color(0xFFFFC34D), fontSize = 12.sp,
    )
    if (picked.size > chk.materials) Text("Too many picked!", color = Palette.bad)
    Spacer(Modifier.height(10.dp))
    Button(
        { if (model.update { Rules.evolve(it, c.id, picked.toList()) }) onDone() },
        Modifier.fillMaxWidth(), enabled = s.gold >= chk.gold && picked.size == chk.materials,
    ) { Text("✨ Evolve!") }
    TextButton(onDone, Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun SellPanel(model: AppModel, picked: Set<Long>, shown: List<OwnedCard>, onPick: (Set<Long>) -> Unit, onDone: () -> Unit) {
    val s = model.state
    val cards = picked.mapNotNull { s.card(it) }
    Text("🪙 Selling", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
    Text("Sold cards are gone for good. Hero cards and cards in your party can't be sold.", color = Palette.textDim, fontSize = 13.sp)
    Spacer(Modifier.height(10.dp))
    Text("Picked: ${cards.size} cards", color = Palette.text)
    Text("You get: 🪙 ${cards.sumOf { it.sellPrice }}", color = Palette.gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    OutlinedButton({
        onPick(shown.filter { it.rarity == Rarity.COMMON && !it.type.food }.map { it.id }.toSet())
    }, Modifier.fillMaxWidth()) { Text("Pick all common cards") }
    Spacer(Modifier.height(6.dp))
    Button({ if (model.update { Rules.sell(it, picked.toList()) }) onDone() }, Modifier.fillMaxWidth(), enabled = cards.isNotEmpty()) { Text("🪙 Sell!") }
    TextButton(onDone, Modifier.fillMaxWidth()) { Text("Cancel") }
}

/**
 * What the shop sells: every hero (level 1, [Balance.HERO_BUY_RARITY], plus the higher rarities the player has evolved
 * that hero to, [GameState.heroShopRarities]) and food cards of every rarity.
 */
private fun shopItems(s: GameState): List<OwnedCard> =
    CardType.heroes.flatMap { t -> s.heroShopRarities(t).map { OwnedCard(0, t, it) } } + Rarity.entries.map { OwnedCard(0, CardType.FOOD, it) }

@Composable
private fun BuyPanel(model: AppModel, card: OwnedCard, onBought: (Long) -> Unit, onDone: () -> Unit) {
    val s = model.state
    val food = card.type.food
    val green = card.rarity == Balance.HERO_BUY_RARITY
    val price = if (food) Balance.foodPrice(card.rarity) else s.heroPrice(card.rarity)
    Text("🛒 Buying", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
    Text(
        if (food) "A level 1 food card. Feed it to any card: it gives ${Balance.FOOD_XP_FACTOR}× the XP of a max-level card of its rarity. " +
            "Rarer food costs more and gives more XP – the same XP per gold. Food cards can't be sold."
        else if (!green) "A level 1 ${card.rarity.label} ${card.type.title}. It's in the shop because you evolved a ${card.type.title} " +
            "to ${card.rarity.label}. Use it to evolve your ${card.type.title} further!"
        else "Buy any hero as a level 1 ${Balance.HERO_BUY_RARITY.label} card. Once you evolve a hero to a new rarity, " +
            "the shop sells that rarity too. " +
            (if (Balance.HERO_PRICE_STEP > 0) "Every card you buy makes the next one " +
                "${Balance.HERO_PRICE_STEP} gold more expensive (but never more than ${Balance.HERO_PRICE_MAX}). " else "") +
            "Tip: spare heroes of the same type are needed to evolve your hero!",
        color = Palette.textDim, fontSize = 13.sp,
    )
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CardFace(card.look(), width = 170.dp) }
    Spacer(Modifier.height(10.dp))
    CardTitle(card)
    Spacer(Modifier.height(8.dp))
    if (food) Text("Worth 📈 ${fmt(card.sacrificeValue)} XP when fed.", color = Palette.good, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    else SkillInfo(card)
    Spacer(Modifier.height(12.dp))
    Text("Price: 🪙 ${fmt(price)} each", color = Palette.gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val nextPrice = Balance.heroPrice(s.heroesBought + 1)
    if (!food && green && Balance.HERO_PRICE_STEP > 0)
        Text(if (nextPrice > price) "The next one will cost 🪙 $nextPrice." else "The price won't go up any more.", color = Palette.textDim, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    // how many to buy: the i-th one (0-based) costs priceOf(i) (green heroes may get pricier with every purchase)
    fun priceOf(i: Int): Long = if (food) price else if (green) Balance.heroPrice(s.heroesBought + i) else price
    fun totalOf(n: Int): Long = (0 until n).sumOf { priceOf(it) }
    val freeSlots = (s.inventorySize - s.cards.size).coerceAtLeast(0)
    val maxCount = generateSequence(0) { it + 1 }.takeWhile { it <= freeSlots && totalOf(it) <= s.gold }.last()
    var count by remember(card.type, card.rarity) { mutableStateOf(1) }
    val n = count.coerceIn(1, maxOf(1, maxCount))
    val total = totalOf(n)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        OutlinedButton({ count = n - 1 }, enabled = n > 1) { Text("−", fontSize = 18.sp) }
        Text("  $n×  ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        OutlinedButton({ count = n + 1 }, enabled = n < maxCount) { Text("+", fontSize = 18.sp) }
        Spacer(Modifier.width(8.dp))
        TextButton({ count = maxCount }, enabled = maxCount > 1 && n < maxCount) { Text("Max ($maxCount)") }
    }
    Spacer(Modifier.height(6.dp))
    Button(
        {
            val bought = model.update { st ->
                (0 until n).fold(st) { acc, _ -> if (food) Rules.buyFood(acc, card.rarity) else Rules.buyHero(acc, card.type, card.rarity) }
            }
            if (bought) {
                onBought(model.state.cards.last().id)
                model.message = if (n == 1) "🎉 A new ${card.fullName} joined your cards!" else "🎉 $n new ${card.fullName} cards joined your cards!"
                count = 1
            }
        },
        Modifier.fillMaxWidth(), enabled = maxCount >= 1,
    ) {
        Text(
            if (s.inventoryFull) "Inventory full (${s.cards.size} / ${s.inventorySize})"
            else if (maxCount >= 1) "🛒 Buy ${if (n > 1) "$n× " else ""}${card.type.title} for 🪙 ${fmt(total)}"
            else "Not enough gold (🪙 ${fmt(s.gold)})"
        )
    }
    TextButton(onDone, Modifier.fillMaxWidth()) { Text("Back") }
}
