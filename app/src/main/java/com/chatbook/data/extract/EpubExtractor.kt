package com.chatbook.data.extract

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * EPUB reader. An EPUB is a zip: META-INF/container.xml points to the OPF package file, whose
 * manifest lists every item and whose spine gives the reading order. Each spine item is XHTML that
 * is turned into plain text with jsoup.
 */
class EpubExtractor : Extractor {
  override fun extract(file: File, listener: ExtractListener) {
    ZipFile(file).use { zip ->
      val entries = zip.entries().asSequence().associateBy { it.name }

      fun read(name: String): String? {
        val entry = entries[name] ?: entries.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
          ?: return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
      }

      val container = read("META-INF/container.xml")
        ?: throw UnsupportedBookException("Not a valid EPUB (missing META-INF/container.xml)")
      val containerDoc = Jsoup.parse(container, "", Parser.xmlParser())
      val opfPath = containerDoc.select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
        ?: throw UnsupportedBookException("Not a valid EPUB (no rootfile in container.xml)")
      val opfXml = read(opfPath) ?: throw UnsupportedBookException("Not a valid EPUB (package file $opfPath missing)")
      val opf = Jsoup.parse(opfXml, "", Parser.xmlParser())
      val opfDir = opfPath.substringBeforeLast('/', "")

      val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim()?.ifEmpty { null }
      val manifest = opf.select("manifest > item").associate { item ->
        item.attr("id") to Pair(item.attr("href"), item.attr("media-type"))
      }
      val spine = opf.select("spine > itemref").map { it.attr("idref") }
      val chapters = spine.mapNotNull { idref ->
        val (href, mediaType) = manifest[idref] ?: return@mapNotNull null
        if (mediaType.isNotBlank() && !mediaType.contains("html", ignoreCase = true)) return@mapNotNull null
        resolve(opfDir, href)
      }
      if (chapters.isEmpty()) throw UnsupportedBookException("This EPUB has no readable text chapters")

      listener.onStart(title, chapters.size)
      chapters.forEachIndexed { index, path ->
        val html = read(path) ?: return@forEachIndexed
        val doc = Jsoup.parse(html)
        val text = doc.body().text()
        if (text.isBlank()) return@forEachIndexed
        listener.onSection(index, Section(chapterLabel(doc, "Section ${index + 1}"), text))
      }
    }
  }

  /** Resolves an href from the OPF against the OPF's directory, dropping fragments and URL escapes. */
  internal fun resolve(baseDir: String, href: String): String {
    val clean = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }.getOrDefault(href)
    val parts = ArrayDeque<String>()
    if (baseDir.isNotEmpty() && !clean.startsWith("/")) baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
    for (seg in clean.split('/')) {
      when (seg) {
        "", "." -> Unit
        ".." -> parts.removeLastOrNull()
        else -> parts.addLast(seg)
      }
    }
    return parts.joinToString("/")
  }
}
