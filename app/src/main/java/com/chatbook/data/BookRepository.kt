package com.chatbook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.chatbook.data.db.Book
import com.chatbook.data.db.BookDatabase
import com.chatbook.data.db.BookStatus
import com.chatbook.data.extract.FormatDetector
import com.chatbook.data.index.IndexWorker
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Imports, lists and removes books. Files live in filesDir/books as "<id>.<ext>". */
class BookRepository(private val context: Context, val db: BookDatabase) {

  private val changes = MutableStateFlow(0L)

  /** Re-emits the library whenever something changes (import, progress, delete). */
  val books: Flow<List<Book>> = changes.map { withContext(Dispatchers.IO) { db.listBooks() } }

  fun notifyChanged() {
    changes.update { it + 1 }
  }

  val booksDir: File
    get() = File(context.filesDir, "books").also { it.mkdirs() }

  fun fileFor(book: Book): File = File(booksDir, book.fileName)

  data class ImportResult(val imported: Int, val errors: List<String>)

  suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
    var imported = 0
    val errors = ArrayList<String>()
    for (uri in uris) {
      val name = displayName(uri) ?: "book"
      val tmp = File(booksDir, "import-${System.nanoTime()}.tmp")
      try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("could not open the file")
        input.use { source -> tmp.outputStream().use { source.copyTo(it, 1 shl 16) } }
        val header = ByteArray(68)
        val read = tmp.inputStream().use { it.read(header) }
        val format = FormatDetector.detect(name, if (read > 0) header.copyOf(read) else ByteArray(0))
        if (format == null) {
          tmp.delete()
          errors.add("$name: not a PDF, EPUB or MOBI file")
          continue
        }
        val title = name.substringBeforeLast('.').trim().ifEmpty { name }
        val id = db.insertBook(title, tmp.name, format)
        val finalName = "$id.${format.extension}"
        val dest = File(booksDir, finalName)
        dest.delete()
        if (!tmp.renameTo(dest)) {
          tmp.copyTo(dest, overwrite = true)
          tmp.delete()
        }
        db.updateFileName(id, finalName)
        IndexWorker.enqueue(context, id, ExistingWorkPolicy.REPLACE)
        imported++
      } catch (e: Exception) {
        tmp.delete()
        errors.add("$name: ${e.message ?: "could not import"}")
      }
    }
    notifyChanged()
    ImportResult(imported, errors)
  }

  suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
    WorkManager.getInstance(context).cancelUniqueWork(IndexWorker.uniqueName(id))
    db.getBook(id)?.let { fileFor(it).delete() }
    db.deleteBook(id)
    notifyChanged()
  }

  fun reindex(id: Long) {
    IndexWorker.enqueue(context, id, ExistingWorkPolicy.REPLACE)
  }

  /** Re-queues books that were still being indexed when the app was last killed. */
  suspend fun resumePending() = withContext(Dispatchers.IO) {
    db.listBooks()
      .filter { it.status == BookStatus.PENDING || it.status == BookStatus.INDEXING }
      .forEach { IndexWorker.enqueue(context, it.id, ExistingWorkPolicy.KEEP) }
  }

  private fun displayName(uri: Uri): String? {
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return c.getString(idx)
        }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
  }
}
