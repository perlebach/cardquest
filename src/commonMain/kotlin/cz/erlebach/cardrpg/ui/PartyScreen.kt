package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.battle.Grid
import cz.erlebach.cardrpg.game.Rules
import cz.erlebach.cardrpg.model.Balance
import kotlin.math.roundToInt

/** A card being dragged: its id and the pointer position (root coordinates). */
private data class Drag(val cardId: Long, val pointer: Offset)

/** Where a card can be dropped. */
private sealed interface DropTarget {
    data class Slot(val slot: Int) : DropTarget
    data object Bench : DropTarget
}

@Composable
fun PartyScreen(model: AppModel) {
    val s = model.state
    /** Card currently "in hand" (picked by clicking, to be placed somewhere). */
    var held by remember { mutableStateOf<Long?>(null) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var hover by remember { mutableStateOf<DropTarget?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val targets = remember { mutableMapOf<DropTarget, Rect>() }
    val cardPositions = remember { mutableMapOf<Long, Offset>() }
    val bench = s.cards.filter { !s.inParty(it.id) && !it.type.food }.sortedByDescending { it.power }

    fun targetAt(p: Offset): DropTarget? =
        targets.entries.firstOrNull { it.key is DropTarget.Slot && it.value.contains(p) }?.key
            ?: targets[DropTarget.Bench]?.takeIf { it.contains(p) }?.let { DropTarget.Bench }

    // called from gesture handlers that outlive recompositions, so it reads the current state
    fun drop(cardId: Long, target: DropTarget?) {
        val s = model.state
        when (target) {
            is DropTarget.Slot -> if (s.party[target.slot] != cardId) model.update { Rules.placeInParty(it, cardId, target.slot) }
            DropTarget.Bench -> if (s.inParty(cardId)) {
                if (s.partyCards.size > 1) model.update { Rules.removeFromParty(it, cardId) }
                else model.message = "Your party needs at least one card."
            }
            null -> {}
        }
        held = null
    }

    /** Lets a card be dragged with the mouse (clicking still works too). */
    fun Modifier.draggableCard(cardId: Long): Modifier {
        return this
            .onGloballyPositioned { cardPositions[cardId] = it.positionInRoot() }
            .pointerInput(cardId) {
                detectDragGestures(
                    onDragStart = { held = null; drag = Drag(cardId, (cardPositions[cardId] ?: Offset.Zero) + it) },
                    onDrag = { change, delta ->
                        change.consume()
                        drag = drag?.let { d -> d.copy(pointer = d.pointer + delta) }
                        hover = drag?.let { targetAt(it.pointer) }
                    },
                    onDragEnd = { drag?.let { drop(it.cardId, targetAt(it.pointer)) }; drag = null; hover = null },
                    onDragCancel = { drag = null; hover = null },
                )
            }
    }

    fun Modifier.dropTarget(t: DropTarget) = onGloballyPositioned { targets[t] = it.boundsInRoot() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot() }) {
        Row(Modifier.fillMaxSize().padding(12.dp)) {
            PanelBox(Modifier.width(520.dp).fillMaxHeight()) {
                Text("Your party (${s.partyCards.size}/${Balance.MAX_PARTY})", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Palette.gold)
                Text(
                    "Drag a card to a spot (or click a card, then click a spot). Cards in the front row get hit first – put tough cards in front!",
                    color = Palette.textDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("⬆ enemies are this way ⬆", color = Palette.textDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                for (row in 0..1) {
                    Text(if (row == 0) "Front row" else "Back row", color = Palette.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (col in 0..2) {
                            val slot = row * 3 + col
                            val card = s.card(s.party[slot])
                            val isHeld = card != null && card.id == held
                            val hovered = hover == DropTarget.Slot(slot)
                            val highlight = held != null || drag != null
                            Box(
                                Modifier.size(146.dp, 200.dp)
                                    .dropTarget(DropTarget.Slot(slot))
                                    .border(
                                        if (hovered) 4.dp else 2.dp,
                                        when {
                                            hovered -> Palette.gold
                                            highlight -> Palette.gold.copy(alpha = 0.6f)
                                            else -> Color(0x44FFFFFF)
                                        },
                                        RoundedCornerShape(12.dp),
                                    )
                                    .background(if (hovered) Palette.gold.copy(alpha = 0.15f) else Color(0x22000000), RoundedCornerShape(12.dp))
                                    .clickable {
                                        val h = held
                                        if (h != null && h != card?.id) {
                                            if (model.update { Rules.placeInParty(it, h, slot) }) held = null
                                        } else held = if (isHeld) null else card?.id
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (card != null) {
                                    CardFace(
                                        card.look(), width = 136.dp, selected = isHeld, dimmed = drag?.cardId == card.id,
                                        modifier = Modifier.draggableCard(card.id),
                                    )
                                } else Text(Grid.names[slot], color = Color(0x66FFFFFF), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val heldCard = s.card(held)
                if (heldCard != null && s.inParty(heldCard.id)) {
                    OutlinedButton(
                        { if (model.update { Rules.removeFromParty(it, heldCard.id) }) held = null },
                        enabled = s.partyCards.size > 1,
                    ) { Text("Remove ${heldCard.type.title} from the party") }
                }
                val partyPower = s.partyCards.sumOf { it.hp } to s.partyCards.sumOf { it.atk }
                Text("Party total: ❤️ ${fmt(partyPower.first)}   ⚔️ ${fmt(partyPower.second)}", color = Palette.text, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.width(12.dp))
            val benchHovered = hover == DropTarget.Bench
            Column(
                Modifier.weight(1f).fillMaxHeight().dropTarget(DropTarget.Bench)
                    .border(3.dp, if (benchHovered) Palette.gold else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                Text("Other cards", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text("Drag one into the party, or drag a party card here to take it out.", color = Palette.textDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(GridCells.Adaptive(126.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bench, key = { it.id }) { c ->
                        CardFace(
                            c.look(), width = 118.dp, selected = held == c.id, dimmed = drag?.cardId == c.id,
                            modifier = Modifier.padding(4.dp).draggableCard(c.id).clickable { held = if (held == c.id) null else c.id },
                        )
                    }
                }
            }
        }

        // the card under the mouse while dragging
        drag?.let { d ->
            val c = s.card(d.cardId) ?: return@let
            val density = LocalDensity.current
            val half = with(density) { Offset(59.dp.toPx(), 82.dp.toPx()) }
            val p = d.pointer - screenOrigin - half
            CardFace(
                c.look(), width = 118.dp,
                modifier = Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }.rotate(-4f).alpha(0.9f),
            )
        }
    }
}
