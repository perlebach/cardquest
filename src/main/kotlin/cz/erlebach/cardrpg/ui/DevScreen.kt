package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.content.Dungeons
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity

/** Cheats for testing (start with `gradlew run -Pdev`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevScreen(model: AppModel) {
    var rarity by remember { mutableStateOf(Rarity.UNCOMMON) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🛠️ Developer tools", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Palette.gold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ model.update { it.copy(gold = it.gold + 10_000) } }) { Text("+10k gold") }
            Button({ model.update { it.copy(playerLevel = it.playerLevel + 1) } }) { Text("+1 player level") }
            Button({ model.update { it.copy(playerLevel = it.playerLevel + 10) } }) { Text("+10 player levels") }
            Button({ model.update { s -> s.copy(cleared = s.cleared + Dungeons.all.flatMap { d -> d.stages.map { it.key } }) } }) { Text("Unlock all stages") }
            Button({
                model.update { s -> s.copy(cards = s.cards.map { if (s.inParty(it.id)) Rules.addCardXp(s, it, 1_000_000_000) else it }) }
            }) { Text("Max level party (up to player level)") }
            Button({ model.update { s -> s.copy(lastClueDay = s.lastClueDay?.minus(1)) } }) { Text("🪷 Skip a night (meditation)") }
            Button({ model.update { s -> s.copy(clues = CardType.heroes.associateWith { s.clues(it) + 100 }) } }) { Text("🪷 +100 clues each") }
        }
        Text("Give a level 1 card:", color = Palette.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in Rarity.entries) FilterChip(rarity == r, { rarity = r }, { Text(r.label, color = r.uiColor()) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in CardType.entries.filter { !it.boss }) {
                OutlinedButton({
                    model.update { s -> s.copy(cards = s.cards + OwnedCard(s.nextId, t, rarity), nextId = s.nextId + 1) }
                }) { CardArt(t, 22.dp); Text(" ${t.title}") }
            }
        }
    }
}
