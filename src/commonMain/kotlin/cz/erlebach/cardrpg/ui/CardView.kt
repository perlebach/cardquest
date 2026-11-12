package cz.erlebach.cardrpg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.erlebach.cardrpg.model.Balance
import cz.erlebach.cardrpg.model.CardType
import cz.erlebach.cardrpg.model.OwnedCard
import cz.erlebach.cardrpg.model.Rarity
import androidx.compose.foundation.Image
import cz.erlebach.cardrpg.resources.Res
import cz.erlebach.cardrpg.resources.allDrawableResources
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToLong

object Palette {
    val felt = Color(0xFF1F4D3A)
    val feltDark = Color(0xFF163A2B)
    val wood = Color(0xFF5A3A22)
    val woodLight = Color(0xFF7A5232)
    val panel = Color(0xFF2B2B3A)
    val panelLight = Color(0xFF3A3A50)
    val gold = Color(0xFFFFD34D)
    val text = Color(0xFFF4F1E8)
    val textDim = Color(0xFFB8B4A8)
    val good = Color(0xFF5BD46A)
    val bad = Color(0xFFFF6B6B)
    val accent = Color(0xFF6BB8FF)
}

fun Rarity.uiColor() = Color(color)

/**
 * true = the art background is a gradient in the card's rarity colour (so a blue Troll looks blue);
 * false = every card type has its own background colours ([CardType.bgTop] / [CardType.bgBottom]).
 */
const val RARITY_BACKGROUND = true

/** Light-to-dark gradient of the rarity colour, used behind the card art. */
fun Rarity.bgColors(): List<Color> = when (this) {
    Rarity.COMMON -> listOf(Color(0xFFF4F4F2), Color(0xFF8E8E96))
    Rarity.UNCOMMON -> listOf(Color(0xFFC8F2C8), Color(0xFF2E7A3E))
    Rarity.RARE -> listOf(Color(0xFFC4DCFF), Color(0xFF2A58A8))
    Rarity.EPIC -> listOf(Color(0xFFE6CCFF), Color(0xFF5A2A92))
    Rarity.LEGENDARY -> listOf(Color(0xFFFFE4B8), Color(0xFFB0601A))
}

/** Ornament colour for the evolution stage: silver, gold, diamond, ruby. */
fun evoOrnament(evo: Int): Color? = when (evo) {
    0 -> null
    1 -> Color(0xFFC9D1DB)
    2 -> Color(0xFFFFCC33)
    3 -> Color(0xFFA8F4FF)
    else -> Color(0xFFFF4F8B)
}

/** Picture of a card type: a hand-drawn SVG (composeResources/drawable) if it has one, otherwise its emoji. */
@Composable
fun CardArt(type: CardType, size: Dp, modifier: Modifier = Modifier) {
    val art = type.art?.let { Res.allDrawableResources[it] }
    if (art == null) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(type.emoji, fontSize = (size.value * 0.82f).sp, lineHeight = (size.value * 0.95f).sp)
        }
    } else {
        Image(painterResource(art), contentDescription = type.title, modifier = modifier.size(size))
    }
}

/** Data needed to draw a card face. */
data class CardLook(
    val type: CardType,
    val rarity: Rarity,
    val evo: Int,
    val level: Int,
    val hp: Int,
    val atk: Int,
    /** Meditation cooldown cuts, drawn as lotus flowers above the art. */
    val cooldownCuts: Int = 0,
)

fun OwnedCard.look() = CardLook(type, rarity, evo, level, hp, atk, cooldownCuts)

/** Symbol of one skill cooldown cut (meditation). */
const val LOTUS = "🪷"

/**
 * A playing card. Top part = art, bottom = stats (left) and level (right).
 * Border colour = rarity, outer ornament frame + corner gems = evolution stage.
 */
@Composable
fun CardFace(
    look: CardLook,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = width * 1.4f,
    currentHp: Int? = null,
    maxHp: Int? = null,
    selected: Boolean = false,
    dimmed: Boolean = false,
    badge: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val s = width.value / 120f
    val rarityColor = look.rarity.uiColor()
    val orn = evoOrnament(look.evo)
    val shape = RoundedCornerShape((10 * s).dp)
    Box(
        modifier
            .size(width, height)
            .alpha(if (dimmed) 0.45f else 1f)
            .shadow(if (selected) (14 * s).dp else (5 * s).dp, shape, ambientColor = if (selected) Palette.gold else Color.Black, spotColor = if (selected) Palette.gold else Color.Black)
            .clip(shape)
            .background(if (orn != null) orn else rarityColor)
            .then(if (selected) Modifier.border((3 * s).dp, Palette.gold, shape) else Modifier)
            .padding(if (orn != null) (4 * s).dp else 0.dp)
    ) {
        Column(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape((8 * s).dp))
                .border((4 * s).dp, rarityColor, RoundedCornerShape((8 * s).dp))
                .padding((4 * s).dp)
                .clip(RoundedCornerShape((5 * s).dp))
        ) {
            // ---- art
            Box(
                Modifier.fillMaxWidth().weight(0.72f)
                    .background(Brush.verticalGradient(
                        if (RARITY_BACKGROUND) look.rarity.bgColors() else listOf(Color(look.type.bgTop), Color(look.type.bgBottom))
                    ))
            ) {
                CardArt(
                    look.type, (56 * s * (height.value / width.value / 1.4f).coerceAtMost(1.6f)).dp,
                    Modifier.align(Alignment.Center).padding(top = (8 * s).dp),
                )
                val compact = width < 80.dp
                if (!compact) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x66000000)).padding(vertical = (1 * s).dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            look.type.title, color = Color.White, fontSize = (11 * s).sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (orn != null) Text(" " + "✚".repeat(look.evo), color = orn, fontSize = (10 * s).sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }
                if (look.cooldownCuts > 0) {
                    Text(
                        LOTUS.repeat(look.cooldownCuts),
                        Modifier.align(Alignment.TopCenter).padding(top = ((if (compact) 1 else 24) * s).dp),
                        fontSize = (11 * s).sp, lineHeight = (13 * s).sp, maxLines = 1,
                    )
                }
                if (look.rarity != Rarity.COMMON && !look.type.food) {
                    Text(
                        look.type.skill.icon,
                        Modifier.align(Alignment.TopEnd).padding(top = (if (compact) 2 else 17).let { it * s }.dp, end = (2 * s).dp)
                            .background(Color(0xAA000000), CircleShape).padding((2 * s).dp),
                        fontSize = (10 * s).sp,
                    )
                }
                if (badge != null) {
                    Text(
                        badge,
                        Modifier.align(if (compact) Alignment.TopStart else Alignment.BottomCenter).padding(bottom = (3 * s).dp)
                            .background(Color(0xCC1A1A2A), RoundedCornerShape((4 * s).dp)).padding(horizontal = (5 * s).dp),
                        color = Palette.gold, fontSize = (9 * s).sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (currentHp != null && maxHp != null) {
                    HpBar(currentHp, maxHp, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding((3 * s).dp).height((7 * s).dp))
                }
            }
            // ---- stats
            Row(
                Modifier.fillMaxWidth().weight(0.28f).background(Color(0xFF26222E)).padding(horizontal = (5 * s).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (look.type.food) Column(Modifier.weight(1f)) {
                    Text("🍖 FOOD", color = Color(0xFFFFB0C8), fontSize = (11 * s).sp, lineHeight = (13 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${Balance.FOOD_XP_FACTOR.toInt()}× XP", color = Color(0xFFFFE08A), fontSize = (11 * s).sp, lineHeight = (13 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                } else Column(Modifier.weight(1f)) {
                    Text("❤️ ${fmt(currentHp ?: look.hp)}", color = Color(0xFFFF9C9C), fontSize = (11 * s).sp, lineHeight = (13 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("⚔️ ${fmt(look.atk)}", color = Color(0xFFFFE08A), fontSize = (11 * s).sp, lineHeight = (13 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LV", color = Palette.textDim, fontSize = (8 * s).sp, lineHeight = (8 * s).sp)
                    Text("${look.level}", color = Color.White, fontSize = (20 * s).sp, fontWeight = FontWeight.Black, lineHeight = (20 * s).sp)
                }
            }
        }
        if (orn != null) EvoGems(look.evo, orn, s)
        overlay()
    }
}

/** Corner gems + "+" pips that mark the evolution stage. */
@Composable
private fun BoxScope.EvoGems(@Suppress("UNUSED_PARAMETER") evo: Int, color: Color, s: Float) {
    Box(Modifier.matchParentSize().drawWithContent {
        drawContent()
        val g = 7 * s * density
        val corners = listOf(Offset(g * 0.2f, g * 0.2f), Offset(size.width - g * 1.2f, g * 0.2f),
            Offset(g * 0.2f, size.height - g * 1.2f), Offset(size.width - g * 1.2f, size.height - g * 1.2f))
        for (c in corners) {
            rotate(45f, pivot = c + Offset(g / 2, g / 2)) {
                drawRect(Color(0xFF222233), c - Offset(1.5f, 1.5f), Size(g + 3f, g + 3f))
                drawRect(color, c, Size(g, g))
                drawRect(Color.White.copy(alpha = 0.6f), c + Offset(g * 0.2f, g * 0.2f), Size(g * 0.3f, g * 0.3f))
            }
        }
    })
}

@Composable
fun HpBar(hp: Int, max: Int, modifier: Modifier) {
    val f = (hp.toFloat() / max).coerceIn(0f, 1f)
    val c = when {
        f > 0.6f -> Palette.good
        f > 0.3f -> Color(0xFFFFC34D)
        else -> Palette.bad
    }
    Box(modifier.clip(RoundedCornerShape(50)).background(Color(0xAA000000))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(c))
    }
}

@Composable
fun XpBar(value: Long, max: Long, modifier: Modifier, color: Color = Palette.accent) {
    val f = if (max <= 0) 1f else (value.toFloat() / max).coerceIn(0f, 1f)
    Box(modifier.clip(RoundedCornerShape(50)).background(Color(0x55000000))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Compact number formatting: 12 345 -> 12.3k */
fun fmt(n: Number): String {
    val v = n.toLong()
    return when {
        v >= 10_000_000 -> "${v / 1_000_000}M"
        v >= 1_000_000 -> (v / 100_000.0).roundToLong().let { "${it / 10}.${it % 10}M" }
        v >= 10_000 -> "${v / 1000}k"
        else -> v.toString()
    }
}
