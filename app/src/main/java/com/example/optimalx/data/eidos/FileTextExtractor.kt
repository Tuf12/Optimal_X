package com.example.optimalx.data.eidos

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class FileTextExtractor(context: Context) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun extractText(file: File): String? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase(Locale.US)
        return when (ext) {
            "txt", "md", "json", "xml", "js", "py", "kt", "ts", "html", "css", "csv", "log" -> file.readText()
            "docx" -> extractDocxText(file)
            "xlsx" -> extractXlsxText(file)
            "ods", "odt" -> extractOdfText(file)
            "pdf" -> extractPdfText(file)
            else -> null
        }
    }

    private fun extractDocxText(file: File): String {
        val sb = StringBuilder()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().decodeToString()
                    val paragraphPattern = Regex("<w:p[ >][^/]*?</w:p>", RegexOption.DOT_MATCHES_ALL)
                    val runPattern = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    paragraphPattern.findAll(xml).forEach { para ->
                        val paraText = runPattern.findAll(para.value)
                            .joinToString("") { it.groupValues[1] }
                        if (paraText.isNotEmpty()) sb.appendLine(paraText) else sb.appendLine()
                    }
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return sb.toString().trim()
    }

    private fun extractXlsxText(file: File): String {
        val sharedStrings = mutableListOf<String>()
        val sheetXml = mutableListOf<String>()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        sharedStrings += Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(zip.readBytes().decodeToString())
                            .map { it.groupValues[1] }
                            .toList()
                    }
                    entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml") -> {
                        sheetXml += zip.readBytes().decodeToString()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val cellPattern = Regex("<c[^>]*>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val valuePattern = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val typePattern = Regex("t=\"(.*?)\"")
        val rows = mutableListOf<String>()
        sheetXml.forEach { xml ->
            cellPattern.findAll(xml).forEach { cell ->
                val cellXml = cell.value
                val v = valuePattern.find(cellXml)?.groupValues?.getOrNull(1).orEmpty()
                val type = typePattern.find(cellXml)?.groupValues?.getOrNull(1).orEmpty()
                val text = if (type == "s") {
                    sharedStrings.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                } else {
                    v
                }
                if (text.isNotBlank()) rows += text
            }
        }
        return rows.joinToString("\n").trim()
    }

    private fun extractOdfText(file: File): String {
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    val xml = zip.readBytes().decodeToString()
                    return Regex(">([^<>]+)<")
                        .findAll(xml)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun extractPdfText(file: File): String {
        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            return stripper.getText(document).trim()
        }
    }
}
