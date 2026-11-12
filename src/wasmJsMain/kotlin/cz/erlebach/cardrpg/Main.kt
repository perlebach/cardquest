package cz.erlebach.cardrpg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import cz.erlebach.cardrpg.game.BrowserSaveStore
import cz.erlebach.cardrpg.resources.Res
import cz.erlebach.cardrpg.resources.emoji
import cz.erlebach.cardrpg.resources.math
import cz.erlebach.cardrpg.resources.symbols
import cz.erlebach.cardrpg.ui.App
import cz.erlebach.cardrpg.ui.AppModel
import cz.erlebach.cardrpg.ui.Palette
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/** The web version: the same game, saved in this browser. `?dev` in the address shows the developer cheat panel. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val dev = window.location.search.contains("dev")
    val model = AppModel(BrowserSaveStore(), devMode = dev)
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) {
        WithFonts { App(model) }
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
