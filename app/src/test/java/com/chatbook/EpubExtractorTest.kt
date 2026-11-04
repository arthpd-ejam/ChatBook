package com.chatbook

import com.chatbook.data.extract.EpubExtractor
import com.chatbook.data.extract.ExtractListener
import com.chatbook.data.extract.Section
import com.chatbook.data.extract.UnsupportedBookException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpubExtractorTest {
  private var seenTitle: String? = null

  @Test
  fun readsSpineInOrderWithLabelsAndTitle() {
    val epub = buildEpub(
      "OEBPS/content.opf" to """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
          <metadata><dc:title>Dune</dc:title><dc:creator>Frank Herbert</dc:creator></metadata>
          <manifest>
            <item id="css" href="style.css" media-type="text/css"/>
            <item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="cover.jpg" media-type="image/jpeg"/>
          </manifest>
          <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="cover"/></spine>
        </package>""",
      "OEBPS/text/ch1.xhtml" to "<html><head><title>Ignored</title></head><body><h1>Book One: Dune</h1><p>A beginning is the time for taking the most delicate care that the balances are correct.</p></body></html>",
      "OEBPS/text/ch 2.xhtml" to "<html><head><title>Muad'Dib</title></head><body><p>Arrakis teaches the attitude of the knife.</p></body></html>",
      "OEBPS/style.css" to "p{}",
    )
    val sections = ArrayList<Section>()
    var total = 0
    EpubExtractor().extract(epub, object : ExtractListener {
      override fun onStart(title: String?, totalSections: Int) { seenTitle = title; total = totalSections }
      override fun onSection(index: Int, section: Section) { sections.add(section) }
    })
    assertEquals("Dune", seenTitle)
    assertEquals(2, total)
    assertEquals(listOf("Book One: Dune", "Muad'Dib"), sections.map { it.label })
    assertTrue(sections[0].text.contains("delicate care"))
    assertTrue(sections[1].text.contains("attitude of the knife"))
  }

  @Test
  fun resolvesRelativePaths() {
    val e = EpubExtractor()
    assertEquals("OEBPS/text/ch1.xhtml", e.resolve("OEBPS", "text/ch1.xhtml#frag"))
    assertEquals("text/ch 2.xhtml", e.resolve("", "text/ch%202.xhtml"))
    assertEquals("images/a.png", e.resolve("OEBPS/text", "../../images/a.png"))
    assertEquals("OEBPS/a.xhtml", e.resolve("OEBPS", "./a.xhtml"))
  }

  @Test
  fun rejectsZipWithoutContainer() {
    val zip = buildEpub("mimetype" to "application/epub+zip")
    try { EpubExtractor().extract(zip, object : ExtractListener {
      override fun onStart(title: String?, totalSections: Int) {}
      override fun onSection(index: Int, section: Section) {}
    }); fail() } catch (e: UnsupportedBookException) { assertTrue(e.message!!.contains("container.xml")) }
  }

  private fun buildEpub(vararg entries: Pair<String, String>): File {
    val f = File.createTempFile("test", ".epub")
    f.deleteOnExit()
    ZipOutputStream(FileOutputStream(f)).use { zos ->
      val hasOpf = entries.any { it.first.endsWith(".opf") }
      if (hasOpf) {
        zos.putNextEntry(ZipEntry("META-INF/container.xml"))
        zos.write(("""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""").toByteArray())
        zos.closeEntry()
      }
      for ((name, content) in entries) {
        zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray()); zos.closeEntry()
      }
    }
    return f
  }
}
