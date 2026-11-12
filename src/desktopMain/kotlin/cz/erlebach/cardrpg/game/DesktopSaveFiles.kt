package cz.erlebach.cardrpg.game

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Export / import through the Windows file dialogs; starts in Downloads, then remembers the last folder. */
class DesktopSaveFiles : SaveFiles {
    private var folder: String = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }?.path
        ?: System.getProperty("user.home")

    override fun export(fileName: String, text: String) {
        val file = choose("Save the game to a file", FileDialog.SAVE, fileName) ?: return
        file.writeText(text)
    }

    override fun pick(onText: (String) -> Unit) {
        val file = choose("Load a game from a file", FileDialog.LOAD, "*.json") ?: return
        onText(file.readText())
    }

    /** Shows the dialog (blocks until closed); null = cancelled. */
    private fun choose(title: String, mode: Int, file: String): File? {
        val dialog = FileDialog(null as Frame?, title, mode)
        dialog.directory = folder
        dialog.file = file
        dialog.isVisible = true
        val name = dialog.file ?: return null
        folder = dialog.directory
        return File(dialog.directory, name)
    }
}
