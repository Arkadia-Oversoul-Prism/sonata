package com.arkadia.sonata.export

import com.arkadia.sonata.data.Document
import java.io.File

/**
 * Writes a document's text out as a `.txt` file.
 *
 * Both representations are exportable, and they are exported separately on
 * purpose. The original is what the user gave Sonata; the cleaned text is what the
 * narration engine actually spoke. A user who wants to keep their source and a
 * user who wants the narration-ready version are asking for different files, and
 * Phase 1 kept them in separate columns precisely so this choice is possible.
 */
interface TextExporter {
    suspend fun exportOriginal(document: Document): ExportResult
    suspend fun exportCleaned(document: Document): ExportResult
}

/** [TextExporter] that writes into a directory. */
class FileTextExporter(private val destinationDir: File) : TextExporter {

    override suspend fun exportOriginal(document: Document): ExportResult =
        write("${document.title} (original)", document.originalText)

    override suspend fun exportCleaned(document: Document): ExportResult =
        write("${document.title} (cleaned)", document.cleanedText ?: document.originalText)

    private fun write(title: String, text: String): ExportResult {
        if (!destinationDir.exists()) destinationDir.mkdirs()
        val taken = destinationDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val name = ExportNaming.uniqueName(title, TEXT_EXTENSION, taken)
        val out = File(destinationDir, name)
        out.writeText(text)
        return ExportResult.Success(out.absolutePath, out.length())
    }

    private companion object {
        const val TEXT_EXTENSION = "txt"
    }
}
