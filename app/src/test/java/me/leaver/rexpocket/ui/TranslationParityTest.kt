package me.leaver.rexpocket.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translatable English string has its own entry in each Chinese locale, so nothing falls back:
 * a string missing from values-zh-rTW would show Simplified Chinese in Taiwan, and one missing from
 * values-zh would show English. The resource XML is read directly, across all of a locale's files.
 */
class TranslationParityTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** A resource's name and its text, for `string`, `plurals` and `string-array` entries. */
    private fun entries(dir: String, translatableOnly: Boolean = false): Map<String, String> {
        val files = File(res, dir).listFiles { f -> f.extension == "xml" }.orEmpty()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.sortedBy { it.name }.flatMap { file ->
            val root = builder.parse(file).documentElement
            (0 until root.childNodes.length).map { root.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName in setOf("string", "plurals", "string-array") }
                .filter { !translatableOnly || it.getAttribute("translatable") != "false" }
                .map { it.getAttribute("name") to it.textContent }
        }.toMap()
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    private fun checkLocale(dir: String) {
        val english = entries("values", translatableOnly = true)
        val local = entries(dir)
        val missing = english.keys - local.keys
        assertTrue("$dir has no entry for: ${missing.sorted()}", missing.isEmpty())
        val stale = local.keys - entries("values").keys
        assertTrue("$dir has entries English no longer has: ${stale.sorted()}", stale.isEmpty())
        val wrong = english.filter { (name, text) -> placeholders(text) != placeholders(local.getValue(name)) }.keys
        assertTrue("$dir changes the placeholders of: ${wrong.sorted()}", wrong.isEmpty())
    }

    @Test
    fun simplifiedChineseHasEveryString() = checkLocale("values-zh")

    @Test
    fun traditionalChineseTaiwanHasEveryString() = checkLocale("values-zh-rTW")
}
