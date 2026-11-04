package com.chatbook.data.extract

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** PDF text extraction with PdfBox-Android, one section per page so answers can cite page numbers. */
class PdfExtractor : Extractor {
  override fun extract(file: File, listener: ExtractListener) {
    val document = try {
      // Temp-file backed parsing keeps very large PDFs from exhausting the heap.
      PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    } catch (e: InvalidPasswordException) {
      throw UnsupportedBookException("This PDF is password-protected and cannot be read.")
    }
    document.use { doc ->
      val pages = doc.numberOfPages
      val title = doc.documentInformation?.title?.trim()?.ifEmpty { null }
      listener.onStart(title, pages)
      val stripper = PDFTextStripper()
      var emptyPages = 0
      for (page in 1..pages) {
        stripper.startPage = page
        stripper.endPage = page
        val text = try {
          stripper.getText(doc)
        } catch (e: Exception) {
          ""
        }
        if (text.isBlank()) emptyPages++
        listener.onSection(page - 1, Section("p. $page", text))
      }
      if (pages > 0 && emptyPages == pages) {
        throw UnsupportedBookException(
          "This PDF has no text layer (it is probably scanned images). OCR is not supported yet."
        )
      }
    }
  }
}

object Extractors {
  fun forFormat(format: BookFormat): Extractor = when (format) {
    BookFormat.PDF -> PdfExtractor()
    BookFormat.EPUB -> EpubExtractor()
    BookFormat.MOBI -> MobiExtractor()
  }
}
