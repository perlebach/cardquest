package cz.erlebach.cardrpg.game

import kotlinx.browser.localStorage

private fun nowMillis(): Double = js("Date.now()")

/**
 * Saved games in the browser's localStorage (this browser on this device only; about 5 MB, a game takes ~10 kB).
 * Each game is the same JSON as a desktop save file, under "cardquest.save.<key>";
 * "cardquest.saved.<key>" holds when it was last saved, for the order of the list.
 */
class BrowserSaveStore : SaveStore({ it.isNotEmpty() }) {
    override fun keys(): List<String> =
        (0 until localStorage.length).mapNotNull { localStorage.key(it) }
            .filter { it.startsWith(SAVE) }
            .map { it.removePrefix(SAVE) }
            .sortedByDescending { localStorage.getItem(SAVED + it)?.toDoubleOrNull() ?: 0.0 }

    override fun readText(key: String): String? = localStorage.getItem(SAVE + key)

    override fun writeText(key: String, text: String) {
        localStorage.setItem(SAVE + key, text)  // a single setItem: replaced in one step
        localStorage.setItem(SAVED + key, nowMillis().toString())
    }

    override fun location(slot: SaveSlot?): String = "this browser (localStorage)"

    private companion object {
        const val SAVE = "cardquest.save."
        const val SAVED = "cardquest.saved."
    }
}
