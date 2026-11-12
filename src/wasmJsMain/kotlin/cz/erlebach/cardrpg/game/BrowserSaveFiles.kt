package cz.erlebach.cardrpg.game

/** Starts a download of [text] as a file called [name]. */
private fun downloadText(name: String, text: String): Unit = js(
    """{
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
}"""
)

/** Opens the browser's file picker; [onText] gets the picked file's content (never called if cancelled). */
private fun pickTextFile(onText: (String) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json';
    input.onchange = () => { const f = input.files[0]; if (f) f.text().then(t => onText(t)); };
    input.click();
}"""
)

/** Export = a download (usually into Downloads), import = the browser's "open file" dialog. */
class BrowserSaveFiles : SaveFiles {
    override fun export(fileName: String, text: String) = downloadText(fileName, text)
    override fun pick(onText: (String) -> Unit) = pickTextFile(onText)
}
