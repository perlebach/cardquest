package cz.erlebach.cardrpg

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cz.erlebach.cardrpg.game.DesktopSaveFiles
import cz.erlebach.cardrpg.game.SaveManager
import cz.erlebach.cardrpg.game.ServerSaves
import cz.erlebach.cardrpg.ui.App
import cz.erlebach.cardrpg.ui.AppModel
import cz.erlebach.gameserver.GameLauncher
import cz.erlebach.gameserver.SaveSync
import cz.erlebach.gameserver.SyncConfig
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JOptionPane

/** Name of the game on the home server (project ../gameServer): ~/.gameServer/games/cardQuest. */
private const val GAME_ID = "cardQuest"

/**
 * The exe (or `-Dcardquest.network=true`) looks for the home server in the LAN: it starts a newer build of the game
 * if the server has one, and syncs the saved games. `gradlew run` (and the dev mode) stays offline,
 * so testing never touches the real saves on the server.
 */
fun main(args: Array<String>) {
    val dev = System.getProperty("cardrpg.dev") == "true"
    val network = !dev && (System.getProperty("jpackage.app-path") != null || System.getProperty("cardquest.network") == "true")
    val clientDir = Path.of(System.getProperty("user.home"), ".cardquest")
    val config = SyncConfig(clientDir.resolve("sync.properties"))
    if (network && GameLauncher.launchLatestVersion(GAME_ID, "cz.erlebach.cardrpg.MainKt", clientDir, args, config)) return

    val saves = SaveManager(clientDir.toFile())
    val sync = (if (network) GameLauncher.server(GAME_ID) else null)?.let { ServerSaves(it, saves, config, ::askConflict) }
    val status = when {
        sync != null -> sync.syncAll()
        network -> "⚠️ No game server found – playing on this computer only."
        else -> null
    }
    sync?.let { Runtime.getRuntime().addShutdownHook(Thread { it.flush() }) }

    application {
        val model = remember { AppModel(saves, sync, status, devMode = dev, files = DesktopSaveFiles()) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Card Quest",
            state = rememberWindowState(size = DpSize(1280.dp, 860.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(1000, 700)
            App(model)
        }
    }
}

/** The game changed both on the server and on this computer: which one to keep? true = the server copy. */
private fun askConflict(game: String, conflict: SaveSync.Conflict): Boolean {
    val format = SimpleDateFormat("d.M.yyyy HH:mm")
    val serverTime = conflict.serverModifiedMillis?.let { format.format(Date(it)) } ?: "?"
    val options = arrayOf("From the server (saved $serverTime)", "From this computer (saved ${format.format(Date(conflict.localModifiedMillis))})")
    val choice = JOptionPane.showOptionDialog(null,
        "The game \"$game\" is different on the server and on this computer.\nWhich one do you want to play? The other one is backed up.",
        "Card Quest – which saved game?", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0])
    return choice != 1
}
