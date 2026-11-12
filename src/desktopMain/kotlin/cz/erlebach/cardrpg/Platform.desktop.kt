package cz.erlebach.cardrpg

import java.time.LocalDate

actual fun localEpochDay(): Long = LocalDate.now().toEpochDay()
