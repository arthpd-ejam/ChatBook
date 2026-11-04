package com.chatbook.data.extract

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import org.jsoup.Jsoup

/**
 * Minimal MOBI / PRC / AZW reader.
 *
 * Reads the PalmDB container, the PalmDOC + MOBI headers in record 0, strips the trailing
 * entries from each text record, decompresses (none or PalmDOC) and returns the book's HTML.
 * HUFF/CDIC-compressed and DRM-protected (Kindle store) files are rejected with a clear message.
 */
object MobiParser {
  data class MobiBook(val title: String?, val html: String)

  private const val COMPRESSION_NONE = 1
  private const val COMPRESSION_PALMDOC = 2
  private const val COMPRESSION_HUFFCDIC = 17480

  fun parse(file: File): MobiBook = RandomAccessFile(file, "r").use { raf -> parse(raf) }

  private fun parse(raf: RandomAccessFile): MobiBook {
    val fileLength = raf.length()
    if (fileLength < 78 + 8) throw UnsupportedBookException("Not a MOBI file (too small)")
    val pdbHeader = ByteArray(78)
    raf.seek(0)
    raf.readFully(pdbHeader)
    val typeCreator = String(pdbHeader, 60, 8, Charsets.ISO_8859_1)
    val isMobi = typeCreator == "BOOKMOBI"
    if (!isMobi && typeCreator != "TEXtREAd") {
      throw UnsupportedBookException("Not a MOBI file (unexpected type \"$typeCreator\")")
    }
    val numRecords = u16(pdbHeader, 76)
    if (numRecords < 2) throw UnsupportedBookException("MOBI file has no text records")

    val recordList = ByteArray(numRecords * 8)
    raf.readFully(recordList)
    val offsets = LongArray(numRecords + 1)
    for (r in 0 until numRecords) offsets[r] = u32(recordList, r * 8)
    offsets[numRecords] = fileLength
    for (r in 0 until numRecords) {
      if (offsets[r] > offsets[r + 1] || offsets[r] >= fileLength) {
        throw UnsupportedBookException("Corrupt MOBI record table")
      }
    }

    fun record(index: Int): ByteArray {
      val len = (offsets[index + 1] - offsets[index]).toInt()
      val buf = ByteArray(len)
      raf.seek(offsets[index])
      raf.readFully(buf)
      return buf
    }

    val record0 = record(0)
    if (record0.size < 16) throw UnsupportedBookException("Corrupt MOBI header")
    val compression = u16(record0, 0)
    val textRecordCount = u16(record0, 8)
    val encryption = u16(record0, 12)

    var charset: Charset = Charsets.ISO_8859_1
    var title: String? = null
    var extraFlags = 0
    if (isMobi && record0.size >= 24 && String(record0, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
      val headerLength = u32(record0, 20).toInt()
      val encoding = if (record0.size >= 32) u32(record0, 28).toInt() else 65001
      charset = when (encoding) {
        65001 -> Charsets.UTF_8
        1252 -> runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        else -> Charsets.ISO_8859_1
      }
      val version = if (record0.size >= 40) u32(record0, 36).toInt() else 0
      if (record0.size >= 0x5C) {
        val nameOffset = u32(record0, 0x54).toInt()
        val nameLength = u32(record0, 0x58).toInt()
        if (nameLength in 1..1024 && nameOffset >= 0 && nameOffset + nameLength <= record0.size) {
          title = String(record0, nameOffset, nameLength, charset).trim().ifEmpty { null }
        }
      }
      if (headerLength >= 0xE4 && version >= 5 && record0.size >= 0xF4) {
        extraFlags = u16(record0, 0xF2)
      }
    }
    if (title == null) {
      val end = pdbHeader.indexOfFirst { it == 0.toByte() }.let { if (it < 0 || it > 32) 32 else it }
      title = String(pdbHeader, 0, end, Charsets.ISO_8859_1).trim().ifEmpty { null }
    }

    if (encryption != 0) {
      throw UnsupportedBookException(
        "This book is DRM-protected (Kindle store purchase). ChatBook can only read DRM-free MOBI files."
      )
    }
    when (compression) {
      COMPRESSION_NONE, COMPRESSION_PALMDOC -> Unit
      COMPRESSION_HUFFCDIC -> throw UnsupportedBookException(
        "This MOBI uses HUFF/CDIC compression, which ChatBook does not support yet. Convert it to EPUB with Calibre."
      )
      else -> throw UnsupportedBookException("Unknown MOBI compression type $compression")
    }

    val lastText = minOf(textRecordCount, numRecords - 1)
    val out = java.io.ByteArrayOutputStream()
    for (r in 1..lastText) {
      val raw = record(r)
      val trimmed = trimTrailingEntries(raw, extraFlags)
      val data = if (compression == COMPRESSION_PALMDOC) PalmDoc.decompress(trimmed) else trimmed
      out.write(data)
    }
    val html = String(out.toByteArray(), charset)
    return MobiBook(title, html)
  }

  /** Removes the trailing multibyte/index entries that MOBI 5+ appends to each text record. */
  internal fun trimTrailingEntries(record: ByteArray, extraFlags: Int): ByteArray {
    var size = record.size
    var flags = extraFlags ushr 1
    while (flags != 0) {
      if (flags and 1 != 0) size -= trailingEntrySize(record, size)
      flags = flags ushr 1
    }
    if (extraFlags and 1 != 0 && size > 0) {
      size -= (record[size - 1].toInt() and 0x03) + 1
    }
    if (size < 0) size = 0
    return if (size == record.size) record else record.copyOf(size)
  }

  /** Reads the backwards variable-length size of a trailing entry (the size includes itself). */
  private fun trailingEntrySize(data: ByteArray, size: Int): Int {
    var bitPos = 0
    var result = 0
    var i = size - 1
    while (i >= 0) {
      val v = data[i].toInt() and 0xFF
      result = result or ((v and 0x7F) shl bitPos)
      bitPos += 7
      if (v and 0x80 != 0 || bitPos >= 28) break
      i--
    }
    return result
  }

  private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

  private fun u32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
      ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}

/** Splits MOBI HTML on page breaks and emits one [Section] per part. */
class MobiExtractor : Extractor {
  override fun extract(file: File, listener: ExtractListener) {
    val book = MobiParser.parse(file)
    val parts = book.html.split(PAGE_BREAK).filter { it.isNotBlank() }
    val total = if (parts.isEmpty()) 0 else parts.size
    listener.onStart(book.title, total)
    parts.forEachIndexed { index, html ->
      val doc = Jsoup.parse(html)
      val text = doc.body().text()
      if (text.isBlank()) return@forEachIndexed
      val fallback = if (parts.size == 1) "Full text" else "Section ${index + 1}"
      listener.onSection(index, Section(chapterLabel(doc, fallback), text))
    }
  }

  private companion object {
    val PAGE_BREAK = Regex("<mbp:pagebreak\\s*/?>|<mbp:pagebreak>\\s*</mbp:pagebreak>", RegexOption.IGNORE_CASE)
  }
}
