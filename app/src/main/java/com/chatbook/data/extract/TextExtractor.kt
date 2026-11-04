package com.chatbook.data.extract

import java.io.File

/** Book file formats ChatBook can read. */
enum class BookFormat(val extension: String) {
  PDF("pdf"),
  EPUB("epub"),
  MOBI("mobi");

  companion object {
    fun fromName(name: String): BookFormat? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
  }
}

/**
 * One citable unit of a book: a PDF page or an EPUB/MOBI chapter.
 *
 * @param label human readable location, e.g. "p. 12" or "Chapter 3: The Hunt"
 * @param text plain text of that unit (whitespace already normalised or not; the chunker re-splits it)
 */
data class Section(val label: String, val text: String)

/** Thrown when a file is recognised but cannot be read (DRM, unsupported compression, ...). */
class UnsupportedBookException(message: String) : Exception(message)

/** Receives sections as they are extracted so a whole book never has to sit in memory. */
interface ExtractListener {
  /** Called once before any section. [title] is the title found in the file's metadata, if any. */
  fun onStart(title: String?, totalSections: Int)

  fun onSection(index: Int, section: Section)
}

interface Extractor {
  fun extract(file: File, listener: ExtractListener)
}

/** Detects the format from the first bytes of the file, falling back to the file extension. */
object FormatDetector {
  fun detect(fileName: String?, header: ByteArray): BookFormat? {
    if (header.size >= 4 && header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() &&
      header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
    ) return BookFormat.PDF
    if (header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
      header[2] == 3.toByte() && header[3] == 4.toByte()
    ) return BookFormat.EPUB
    if (header.size >= 68) {
      val magic = String(header, 60, 8, Charsets.ISO_8859_1)
      if (magic == "BOOKMOBI" || magic == "TEXtREAd") return BookFormat.MOBI
    }
    val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when (ext) {
      "pdf" -> BookFormat.PDF
      "epub" -> BookFormat.EPUB
      "mobi", "prc", "azw", "azw3", "kf8" -> BookFormat.MOBI
      else -> null
    }
  }

  fun detect(file: File): BookFormat? {
    val header = ByteArray(68)
    val read = file.inputStream().use { it.read(header) }
    return detect(file.name, if (read > 0) header.copyOf(read) else ByteArray(0))
  }
}

/** Picks a readable chapter label from an HTML document: first heading, then <title>, then a fallback. */
internal fun chapterLabel(doc: org.jsoup.nodes.Document, fallback: String): String {
  val heading = doc.select("h1, h2, h3").firstOrNull { it.text().isNotBlank() }?.text()
  val title = heading ?: doc.title().takeIf { it.isNotBlank() } ?: fallback
  val clean = title.replace(Regex("\\s+"), " ").trim()
  return if (clean.length > 70) clean.take(67) + "..." else clean
}
