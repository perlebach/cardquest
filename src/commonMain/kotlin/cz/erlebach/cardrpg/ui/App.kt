package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.game.GameState
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.game.SaveStore
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity

@Composable
fun App(model: AppModel) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.gold, onPrimary = Color(0xFF2A2000),
            secondary = Palette.accent, surface = Palette.panel, background = Palette.feltDark,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Palette.feltDark) {
            val fight = model.fight
            when {
                model.slot == null -> SavesScreen(model)
                !model.state.started -> StarterScreen(model)
                fight != null -> BattleScreen(model, fight)
                else -> MainScreen(model)
            }
            model.pendingImport?.let { ImportDialog(model, it) }
            model.message?.let { msg ->
                AlertDialog(
                    onDismissRequest = { model.message = null },
                    confirmButton = { Button({ model.message = null }) { Text("OK") } },
                    text = { Text(msg, fontSize = 18.sp) },
                )
            }
        }
    }
}

@Composable
private fun SavesScreen(model: AppModel) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    CenteredScreen {
        Text("🃏 Card Quest", fontSize = 52.sp, fontWeight = FontWeight.Black, color = Palette.gold)
        Spacer(Modifier.height(24.dp))
        if (model.slots.isNotEmpty()) {
            Text("Continue a game", fontSize = 26.sp, color = Palette.text)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.width(520.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (s in model.slots) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel)
                            .border(2.dp, Palette.panelLight, RoundedCornerShape(14.dp))
                            .clickable { model.open(s) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.weight(1f))
                        Text(if (s.state.started) "💪 ${fmt(s.state.partyStrength)}" else "no hero yet",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.gold)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if (!naming) {
            Button({ naming = true }, Modifier.height(56.dp)) { Text("✨ Start a new game", fontSize = 20.sp) }
            if (model.files != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(model::importGame) { Text("📂 Load a game from a file", fontSize = 16.sp) }
            }
        } else {
            Text("What's your name?", fontSize = 22.sp, color = Palette.text)
            Spacer(Modifier.height(10.dp))
            val focus = remember { FocusRequester() }
            val submit = { if (name.isNotBlank()) model.newGame(name) }
            OutlinedTextField(
                name, { name = it.take(SaveStore.MAX_NAME) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
                modifier = Modifier.width(360.dp).focusRequester(focus)
                    .onPreviewKeyEvent { e -> if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { submit(); true } else false },
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton({ naming = false; name = "" }) { Text("Back", fontSize = 18.sp) }
                Button({ submit() }, enabled = name.isNotBlank(), modifier = Modifier.height(52.dp)) { Text("Next: pick a hero ➜", fontSize = 18.sp) }
            }
        }
        model.syncStatus?.let {
            Spacer(Modifier.height(28.dp))
            Text(it, fontSize = 14.sp, color = Palette.textDim)
        }
    }
}

@Composable
private fun StarterScreen(model: AppModel) {
    var picked by remember { mutableStateOf<CardType?>(null) }
    CenteredScreen {
        Text("🃏 Card Quest", fontSize = 52.sp, fontWeight = FontWeight.Black, color = Palette.gold)
        Spacer(Modifier.height(8.dp))
        Text("${model.slot?.name}, pick your first hero!", fontSize = 26.sp, color = Palette.text)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            for (t in CardType.starters) {
                val card = OwnedCard(0, t, Rarity.UNCOMMON)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CardFace(card.look(), width = 190.dp, selected = picked == t,
                        modifier = Modifier.clickable { picked = t })
                    Spacer(Modifier.height(12.dp))
                    Text(starterBlurb(t), color = Palette.text, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(210.dp))
                    Text("${t.skill.icon} ${t.skill.title}: ${t.skill.describe(card.skillPower)}",
                        color = Palette.textDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(210.dp))
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = { picked?.let { p -> model.update { Rules.newGame(p) } } },
            enabled = picked != null,
            modifier = Modifier.height(56.dp),
        ) { Text(if (picked == null) "Choose a card" else "Start with the ${picked!!.title}!", fontSize = 20.sp) }
        Spacer(Modifier.height(12.dp))
        TextButton({ model.closeGame() }) { Text("⬅ Back to saved games", fontSize = 16.sp) }
    }
}

private fun starterBlurb(t: CardType) = when (t) {
    CardType.WARRIOR -> "Tough and brave. Lots of HP, small hits."
    CardType.MAGE -> "Hits hard and can burn every enemy at once. Fragile!"
    CardType.ROGUE -> "Quick and sneaky. Huge single hits. Fragile!"
    else -> ""
}

@Composable
private fun MainScreen(model: AppModel) {
    // clues come at midnight, also while the game is running
    LaunchedEffect(model) { while (true) { kotlinx.coroutines.delay(60_000); model.refreshMeditation() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (model.tab) {
                Tab.CARDS -> InventoryScreen(model)
                Tab.PARTY -> PartyScreen(model)
                Tab.DUNGEONS -> DungeonScreen(model)
                Tab.MEDITATION -> MeditationScreen(model)
                Tab.DEV -> DevScreen(model)
            }
        }
    }
}

@Composable
private fun TopBar(model: AppModel) {
    val s = model.state
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 1200.dp  // narrow window: just 🃏 instead of the title, so all tabs fit
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Palette.woodLight, Palette.wood))).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // title and tabs take the room that's left (and scroll sideways in a narrow window); level, gold and ⚙️ always show
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(if (wide) "🃏 Card Quest" else "🃏", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Palette.gold)
                Spacer(Modifier.width(12.dp))
                Text(model.slot?.name ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Spacer(Modifier.width(24.dp))
                for (t in Tab.entries) {
                    if (t == Tab.DEV && !model.devMode) continue
                    if (t == Tab.MEDITATION && !s.meditationUnlocked) continue
                    val sel = model.tab == t
                    Box(
                        Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Palette.feltDark else Color(0x33000000))
                            .border(2.dp, if (sel) Palette.gold else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { model.tab = t }.padding(horizontal = 18.dp, vertical = 8.dp)
                    ) { Text("${t.icon} ${t.title}", fontSize = 18.sp, color = if (sel) Palette.gold else Palette.text, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("⭐ Player level ${s.playerLevel}", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                XpBar(s.playerXp, s.playerXpToNext, Modifier.width(160.dp).height(8.dp), Palette.gold)
                Text("${fmt(s.playerXp)} / ${fmt(s.playerXpToNext)} XP", color = Palette.textDim, fontSize = 11.sp)
            }
            Spacer(Modifier.width(20.dp))
            Text("🪙 ${fmt(s.gold)}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
            Spacer(Modifier.width(12.dp))
            Box {
                TextButton({ menu = true }) { Text("⚙️", fontSize = 20.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Wins: ${s.wins}   Losses: ${s.losses}") }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("Saved in: ${model.savePath}", fontSize = 11.sp) }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("📂 Switch game…") }, onClick = { menu = false; model.closeGame() })
                    if (model.files != null) {
                        DropdownMenuItem(text = { Text("⬇️ Save this game to a file") }, onClick = { menu = false; model.exportGame() })
                    }
                    DropdownMenuItem(text = { Text("🗑️ Start this game over…") }, onClick = { menu = false; confirmReset = true })
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Start over?") },
            text = { Text("All cards, gold and progress of \"${model.slot?.name}\" will be lost!") },
            confirmButton = { Button({ confirmReset = false; model.reset() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.bad)) { Text("Yes, start over") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("No") } },
        )
    }
}

/** A loaded game has the name of an existing one: replace that one, or keep both? */
@Composable
private fun ImportDialog(model: AppModel, p: PendingImport) {
    fun strength(g: GameState) = if (g.started) "💪 ${fmt(g.partyStrength)}" else "no hero yet"
    AlertDialog(
        onDismissRequest = { model.resolveImport(null) },
        title = { Text("There already is a game called \"${p.existing.name}\"") },
        text = {
            Text(
                "Here: ${strength(p.existing.state)}\nIn the file: ${strength(p.file.game)}\n\n" +
                    "Replace the game here with the one from the file (the one here will be lost), or keep both?",
                fontSize = 16.sp,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.resolveImport(false) }) { Text("Keep both") }
                Button({ model.resolveImport(true) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.bad)) { Text("Replace it") }
            }
        },
        dismissButton = { TextButton({ model.resolveImport(null) }) { Text("Cancel") } },
    )
}

/** A full-screen column, centred – and scrollable when the window is too low for it. */
@Composable
private fun CenteredScreen(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.felt, Palette.feltDark))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** A titled panel used on several screens. */
@Composable
fun PanelBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Palette.panel).border(2.dp, Palette.panelLight, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content,
    )
}
