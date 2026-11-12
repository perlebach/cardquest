package cz.erlebach.cardrpg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import cz.erlebach.cardrpg.game.BrowserSaveFiles
import cz.erlebach.cardrpg.game.BrowserSaveStore
import cz.erlebach.cardrpg.resources.Res
import cz.erlebach.cardrpg.resources.emoji
import cz.erlebach.cardrpg.resources.math
import cz.erlebach.cardrpg.resources.symbols
import cz.erlebach.cardrpg.ui.App
import cz.erlebach.cardrpg.ui.AppModel
import cz.erlebach.cardrpg.ui.Palette
import kotlinx.browser.document
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/** The web version: the same game, saved in this browser. The Dev tab (cheats) exists only in the desktop version. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val model = AppModel(BrowserSaveStore(), devMode = false, files = BrowserSaveFiles())
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) {
        FitToWindow { WithFonts { App(model) } }
    }
}

/**
 * The screens are made for at least 1000 × 700 (the smallest desktop window). In a smaller browser window
 * everything is drawn smaller, so it still fits; bigger windows stay at the normal size.
 */
@Composable
private fun FitToWindow(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(1f, maxWidth / 1000.dp, maxHeight / 700.dp)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            content()
        }
    }
}

/**
 * A browser canvas has no system fonts, so the emoji and symbols the game uses (cards, icons, ★, →) come with it:
 * composeResources/font, made by tools/web-fonts.py. They are registered as fallback fonts before the game shows.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun WithFonts(content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    val emoji by preloadFont(Res.font.emoji)
    val symbols by preloadFont(Res.font.symbols)
    val math by preloadFont(Res.font.math)
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(emoji, symbols, math) {
        val fonts = listOfNotNull(emoji, symbols, math)
        if (fonts.size == 3) {
            fonts.forEach { resolver.preload(FontFamily(it)) }
            ready = true
        }
    }
    if (ready) content()
    else Box(Modifier.fillMaxSize().background(Palette.felt), contentAlignment = Alignment.Center) {
        Text("Loading…", color = Palette.text, fontSize = 24.sp)
    }
}
