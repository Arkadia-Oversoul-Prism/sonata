package com.arkadia.sonata.imports

import com.arkadia.sonata.MAX_INPUT_CHARS
import com.arkadia.sonata.imports.DocumentImport.ImportEncoding
import com.arkadia.sonata.imports.DocumentImport.ImportOutcome
import com.arkadia.sonata.imports.DocumentImport.Reason
import com.arkadia.sonata.imports.DocumentImport.StreamRead
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import decisions, exercised as plain values.
 *
 * Every case here is a file another app could hand over: a UTF-8 note, a
 * Windows-era Latin-1 text, a byte-order mark, a photograph mislabelled as text,
 * and a file larger than the ceiling. The refusal cases matter as much as the
 * accepted ones — an importer that quietly speaks the first two megabytes of a
 * binary is worse than one that says it cannot read the file.
 */
class DocumentImportTest {

    private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun accepted(outcome: ImportOutcome): ImportOutcome.Accepted {
        assertTrue("expected an acceptance, got $outcome", outcome is ImportOutcome.Accepted)
        return outcome as ImportOutcome.Accepted
    }

    // ── Accepted ─────────────────────────────────────────────────────────────

    @Test
    fun `a shared UTF-8 file becomes text with a title from its name`() {
        val result = accepted(DocumentImport.import("chapter-one.md", utf8("# Chapter One\n\nIt began.")))
        assertEquals("# Chapter One\n\nIt began.", result.text)
        assertEquals("chapter-one", result.title)
        assertEquals(ImportEncoding.UTF8, result.encoding)
        assertNull(result.truncatedFrom)
    }

    @Test
    fun `a byte order mark is stripped rather than spoken`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8("Hello")
        assertEquals("Hello", accepted(DocumentImport.import("note.txt", bytes)).text)
    }

    @Test
    fun `a name with no extension is kept whole`() {
        assertEquals("notes", accepted(DocumentImport.import("notes", utf8("text"))).title)
    }

    @Test
    fun `only the final extension is stripped`() {
        assertEquals("a.b", accepted(DocumentImport.import("a.b.md", utf8("text"))).title)
    }

    @Test
    fun `a path-qualified name is titled by its file name`() {
        assertEquals("prologue", accepted(DocumentImport.import("books/prologue.txt", utf8("x"))).title)
    }

    @Test
    fun `with no file name the title comes from the first line`() {
        val result = accepted(DocumentImport.import(null, utf8("\n\n  The Long Winter  \nrest")))
        assertEquals("The Long Winter", result.title)
    }

    @Test
    fun `a blank file name falls back to the first line instead of Untitled`() {
        assertEquals("Real title", accepted(DocumentImport.import("   ", utf8("Real title"))).title)
    }

    /**
     * Latin-1 is the fallback, not a substitution: the byte 0xE9 is `é` in
     * ISO-8859-1 and is *not* valid UTF-8, so this file exercises the fallback and
     * records that it did.
     */
    @Test
    fun `a Latin-1 file decodes through the fallback and says so`() {
        val bytes = "caf".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0xE9.toByte())
        val result = accepted(DocumentImport.import("cafe.txt", bytes))
        assertEquals("caf\u00e9", result.text)
        assertEquals(ImportEncoding.LATIN1, result.encoding)
    }

    @Test
    fun `an over-long document is accepted truncated and reports the original length`() {
        val long = "a".repeat(MAX_INPUT_CHARS + 500)
        val result = accepted(DocumentImport.import("long.txt", utf8(long)))
        assertEquals(MAX_INPUT_CHARS, result.text.length)
        assertEquals(MAX_INPUT_CHARS + 500, result.truncatedFrom)
    }

    // ── Refused ──────────────────────────────────────────────────────────────

    @Test
    fun `a NUL byte marks the file as binary`() {
        val bytes = utf8("plain text").plus(byteArrayOf(0x00))
        assertEquals(ImportOutcome.Rejected(Reason.BINARY), DocumentImport.import("image.png", bytes))
    }

    /**
     * The NUL is at the end on purpose. A check that scanned only a fixed prefix —
     * a common shortcut — would accept this file and speak binary.
     */
    @Test
    fun `a NUL byte late in the file is still binary`() {
        val bytes = utf8("x".repeat(50_000)).plus(byteArrayOf(0x00))
        assertEquals(ImportOutcome.Rejected(Reason.BINARY), DocumentImport.import("late.bin", bytes))
    }

    @Test
    fun `a file past the byte ceiling is refused`() {
        val bytes = ByteArray(DocumentImport.MAX_IMPORT_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(ImportOutcome.Rejected(Reason.TOO_LARGE), DocumentImport.import("huge.txt", bytes))
    }

    @Test
    fun `a file exactly at the byte ceiling is not refused for size`() {
        val bytes = ByteArray(DocumentImport.MAX_IMPORT_BYTES) { 'a'.code.toByte() }
        assertTrue(DocumentImport.import("edge.txt", bytes) is ImportOutcome.Accepted)
    }

    @Test
    fun `a file with nothing speakable in it is empty, not accepted`() {
        assertEquals(ImportOutcome.Empty, DocumentImport.import("blank.txt", utf8("   \n\t\n  ")))
    }

    @Test
    fun `an empty file is empty, not accepted`() {
        assertEquals(ImportOutcome.Empty, DocumentImport.import("zero.txt", ByteArray(0)))
    }

    // ── Bounded read ─────────────────────────────────────────────────────────

    private fun bytesOf(read: StreamRead): ByteArray {
        assertTrue("expected Ok, got $read", read is StreamRead.Ok)
        return (read as StreamRead.Ok).bytes
    }

    @Test
    fun `a stream shorter than the ceiling is read whole`() {
        val stream = ByteArrayInputStream(utf8("hello"))
        assertEquals("hello", String(bytesOf(DocumentImport.readBounded(stream, 10)), StandardCharsets.UTF_8))
    }

    @Test
    fun `a stream exactly at the ceiling is read whole`() {
        val stream = ByteArrayInputStream(ByteArray(10) { 0x41 })
        assertEquals(10, bytesOf(DocumentImport.readBounded(stream, 10)).size)
    }

    /**
     * The discriminating case: one byte past the ceiling must be refused, which is
     * why the reader takes a ceiling-plus-one buffer. Without the extra byte this
     * file would be indistinguishable from the one above.
     */
    @Test
    fun `a stream one byte past the ceiling is refused`() {
        val stream = ByteArrayInputStream(ByteArray(11) { 0x41 })
        assertTrue(DocumentImport.readBounded(stream, 10) is StreamRead.OverCeiling)
    }

    @Test
    fun `an empty stream reads as no bytes rather than as over the ceiling`() {
        assertEquals(0, bytesOf(DocumentImport.readBounded(ByteArrayInputStream(ByteArray(0)), 10)).size)
    }
}
