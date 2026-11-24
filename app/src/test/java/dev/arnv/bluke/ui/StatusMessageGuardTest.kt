package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** UI decisions come from [connectionUi], never from the manager's English status text. */
class StatusMessageGuardTest {
    @Test
    fun noUiFileMatchesOnStatusText() {
        val ui = listOf("src/main/java/dev/arnv/bluke/ui", "app/src/main/java/dev/arnv/bluke/ui")
            .map(::File).first { it.isDirectory }
        val pattern = Regex("""(statusMessage|statusLine)\s*(\.value)?\s*\.\s*(contains|startsWith|endsWith|equals)\b|(statusMessage|statusLine)\s*(==|!=)""")
        val files = ui.walk().filter { it.extension == "kt" }.toList()
        assertTrue("found no UI sources under $ui", files.size > 10)
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null }
        }
        assertEquals("UI reads status text instead of connectionUi:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }
}
