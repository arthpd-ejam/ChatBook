package com.chatbook

import com.chatbook.data.extract.ExtractListener
import com.chatbook.data.extract.FormatDetector
import com.chatbook.data.extract.BookFormat
import com.chatbook.data.extract.MobiExtractor
import com.chatbook.data.extract.MobiParser
import com.chatbook.data.extract.Section
import com.chatbook.data.extract.UnsupportedBookException
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MobiParserTest {
  private val html = "<html><body><h1>Chapter One</h1><p>Call me Ishmael. Some years ago, never mind how long precisely, " +
    "having little or no money in my purse.</p><mbp:pagebreak/><h2>Chapter Two</h2><p>The Carpet-Bag. I stuffed a shirt " +
    "or two into my old carpet-bag, tucked it under my arm, and started for Cape Horn and the Pacific.</p></body></html>"

  @Test
  fun readsUncompressedMobi() {
    val file = writeMobi(buildMobi(html.toByteArray(), compression = 1, title = "Moby-Dick"))
    val book = MobiParser.parse(file)
    assertEquals("Moby-Dick", book.title)
    assertTrue(book.html.contains("Call me Ishmael"))
  }

  @Test
  fun readsPalmDocCompressedMobiWithTrailingEntries() {
    // extraFlags bit 1 = one trailing size entry, bit 0 = multibyte overlap entry.
    val file = writeMobi(buildMobi(html.toByteArray(), compression = 2, title = "Moby-Dick", extraFlags = 0b11, recordSize = 64))
    val book = MobiParser.parse(file)
    assertTrue(book.html, book.html.contains("Cape Horn and the Pacific"))
    assertTrue(book.html.endsWith("</html>"))
  }

  @Test
  fun splitsSectionsOnPageBreaks() {
    val file = writeMobi(buildMobi(html.toByteArray(), compression = 2, title = "Moby-Dick"))
    val sections = ArrayList<Section>()
    var total = -1
    MobiExtractor().extract(file, object : ExtractListener {
      override fun onStart(title: String?, totalSections: Int) { total = totalSections }
      override fun onSection(index: Int, section: Section) { sections.add(section) }
    })
    assertEquals(2, total)
    assertEquals(listOf("Chapter One", "Chapter Two"), sections.map { it.label })
    assertTrue(sections[1].text.startsWith("Chapter Two The Carpet-Bag."))
  }

  @Test
  fun rejectsDrm() {
    val file = writeMobi(buildMobi(html.toByteArray(), compression = 2, title = "Locked", encryption = 2))
    try { MobiParser.parse(file); fail("expected DRM rejection") } catch (e: UnsupportedBookException) {
      assertTrue(e.message!!.contains("DRM"))
    }
  }

  @Test
  fun rejectsHuffCdic() {
    val file = writeMobi(buildMobi(html.toByteArray(), compression = 17480, title = "Huff"))
    try { MobiParser.parse(file); fail("expected HUFF rejection") } catch (e: UnsupportedBookException) {
      assertTrue(e.message!!.contains("HUFF"))
    }
  }

  @Test
  fun detectsFormatFromMagicBytes() {
    val mobi = buildMobi(html.toByteArray(), compression = 1, title = "x")
    assertEquals(BookFormat.MOBI, FormatDetector.detect("whatever.bin", mobi.copyOf(68)))
    assertEquals(BookFormat.PDF, FormatDetector.detect("a.bin", "%PDF-1.7".toByteArray()))
    assertEquals(BookFormat.EPUB, FormatDetector.detect("a.bin", byteArrayOf(0x50, 0x4B, 3, 4, 0, 0)))
    assertEquals(BookFormat.MOBI, FormatDetector.detect("book.azw3", ByteArray(0)))
    assertEquals(null, FormatDetector.detect("book.txt", ByteArray(0)))
  }

  private fun writeMobi(bytes: ByteArray): File {
    val f = File.createTempFile("test", ".mobi")
    f.deleteOnExit()
    f.writeBytes(bytes)
    return f
  }

  /** Builds a minimal but structurally faithful MOBI file (PalmDB + record 0 + text records). */
  private fun buildMobi(
    text: ByteArray,
    compression: Int,
    title: String,
    encryption: Int = 0,
    extraFlags: Int = 0,
    recordSize: Int = 4096,
  ): ByteArray {
    val textRecords = ArrayList<ByteArray>()
    var pos = 0
    while (pos < text.size) {
      val end = minOf(pos + recordSize, text.size)
      val chunk = text.copyOfRange(pos, end)
      val body = if (compression == 2) TestPalmDocCompressor.compress(chunk) else chunk
      val rec = ByteArrayOutputStream()
      rec.write(body)
      if (extraFlags and 1 != 0) rec.write(byteArrayOf(0xAA.toByte(), 0x01)) // 2-byte multibyte entry
      if (extraFlags and 2 != 0) rec.write(byteArrayOf(0x00, 0x00, 0x83.toByte())) // 3-byte size entry
      textRecords.add(rec.toByteArray())
      pos = end
    }

    val titleBytes = title.toByteArray(Charsets.UTF_8)
    val headerLen = 232
    val record0 = ByteArray(16 + headerLen + titleBytes.size)
    putU16(record0, 0, compression)
    putU32(record0, 4, text.size.toLong())
    putU16(record0, 8, textRecords.size)
    putU16(record0, 10, recordSize)
    putU16(record0, 12, encryption)
    "MOBI".toByteArray().copyInto(record0, 16)
    putU32(record0, 20, headerLen.toLong())
    putU32(record0, 24, 2)
    putU32(record0, 28, 65001)
    putU32(record0, 32, 12345)
    putU32(record0, 36, 6)
    putU32(record0, 0x54, (16 + headerLen).toLong())
    putU32(record0, 0x58, titleBytes.size.toLong())
    putU32(record0, 0x80, 0)
    putU16(record0, 0xF2, extraFlags)
    titleBytes.copyInto(record0, 16 + headerLen)

    val records = listOf(record0) + textRecords
    val pdb = ByteArray(78)
    "test".toByteArray().copyInto(pdb, 0)
    "BOOKMOBI".toByteArray().copyInto(pdb, 60)
    putU16(pdb, 76, records.size)
    val out = ByteArrayOutputStream()
    out.write(pdb)
    var offset = 78 + records.size * 8 + 2 // 2-byte gap after the record list like real files
    for ((i, r) in records.withIndex()) {
      val entry = ByteArray(8)
      putU32(entry, 0, offset.toLong())
      entry[5] = ((2 * i) ushr 8).toByte(); entry[6] = 0; entry[7] = (2 * i).toByte()
      out.write(entry)
      offset += r.size
    }
    out.write(byteArrayOf(0, 0))
    records.forEach { out.write(it) }
    return out.toByteArray()
  }

  private fun putU16(b: ByteArray, off: Int, v: Int) { b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte() }
  private fun putU32(b: ByteArray, off: Int, v: Long) {
    b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
  }
}
