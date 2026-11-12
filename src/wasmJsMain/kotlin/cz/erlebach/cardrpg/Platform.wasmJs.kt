package cz.erlebach.cardrpg

/** Days since 1.1.1970 of the browser's local date. */
private fun localEpochDayJs(): Double = js("((d) => Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000)(new Date())")

actual fun localEpochDay(): Long = localEpochDayJs().toLong()
