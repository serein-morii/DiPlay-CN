package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TraditionalChineseTranslationsTest {
    // Do not treat prose such as "2 % steps" as a Formatter argument.
    private val argument = Regex("%(?:%|n|(?:\\d+\\$)?[-#+0,(<]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaA]))")

    @Test fun everyUserFacingStringHasATraditionalChineseTranslationWithMatchingArguments() {
        for (module in listOf(File("."), File("../shared"), File("../samples/home"), File("../samples/maphost"))) {
            val english = load(File(module, "src/main/res/values"))
            val traditional = load(File(module, "src/main/res/values-zh-rTW"))
            assertEquals("$module: Traditional Chinese resource keys", english.keys, traditional.keys)
            for ((name, source) in english) {
                assertEquals("$module/$name", arguments(source), arguments(traditional.getValue(name)))
            }
        }
    }

    private fun arguments(value: String) = argument.findAll(value).map { it.value }
        .filter { it != "%%" && it != "%n" }.toList().sorted()

    private fun load(folder: File): Map<String, String> {
        val files = folder.listFiles { file -> file.extension == "xml" }?.sortedBy { it.name }
            ?: error("Missing locale resources: $folder")
        val result = mutableMapOf<String, String>()
        for (file in files) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                val name = node.attributes.getNamedItem("name").nodeValue
                check(result.put(name, node.textContent) == null) { "Duplicate translation: $folder/$name" }
            }
        }
        return result
    }
}
