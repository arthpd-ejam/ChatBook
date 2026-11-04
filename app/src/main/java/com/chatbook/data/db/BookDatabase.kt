package com.chatbook.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.chatbook.data.extract.BookFormat
import com.chatbook.data.retrieval.Bm25
import kotlin.math.abs

/**
 * Plain SQLite storage. `books` holds library metadata; `chunks` is an FTS4 full-text table with
 * the Porter stemmer so "whales" matches "whale". `book_id` and `label` are stored but not indexed.
 */
class BookDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE books (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT NOT NULL,
        file_name TEXT NOT NULL,
        format TEXT NOT NULL,
        status TEXT NOT NULL,
        progress INTEGER NOT NULL DEFAULT 0,
        total INTEGER NOT NULL DEFAULT 0,
        chunk_count INTEGER NOT NULL DEFAULT 0,
        error TEXT,
        added_at INTEGER NOT NULL
      )
      """.trimIndent()
    )
    db.execSQL(
      "CREATE VIRTUAL TABLE chunks USING fts4(content, book_id, label, tokenize=porter, notindexed=book_id, notindexed=label)"
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // First schema version; nothing to migrate yet.
  }

  // ---- books -------------------------------------------------------------------------------

  fun listBooks(): List<Book> =
    readableDatabase.rawQuery("SELECT * FROM books ORDER BY added_at DESC, id DESC", null).use { c ->
      buildList { while (c.moveToNext()) add(c.toBook()) }
    }

  fun getBook(id: Long): Book? =
    readableDatabase.rawQuery("SELECT * FROM books WHERE id = ?", arrayOf(id.toString())).use { c ->
      if (c.moveToFirst()) c.toBook() else null
    }

  fun insertBook(title: String, fileName: String, format: BookFormat): Long {
    val values = ContentValues().apply {
      put("title", title)
      put("file_name", fileName)
      put("format", format.name)
      put("status", BookStatus.PENDING.name)
      put("added_at", System.currentTimeMillis())
    }
    return writableDatabase.insertOrThrow("books", null, values)
  }

  fun updateFileName(id: Long, fileName: String) =
    update(id, ContentValues().apply { put("file_name", fileName) })

  fun updateTitle(id: Long, title: String) = update(id, ContentValues().apply { put("title", title) })

  fun setStatus(id: Long, status: BookStatus, error: String? = null) =
    update(id, ContentValues().apply {
      put("status", status.name)
      if (error == null) putNull("error") else put("error", error)
      if (status == BookStatus.INDEXING || status == BookStatus.PENDING) {
        put("progress", 0)
        put("chunk_count", 0)
      }
    })

  fun setProgress(id: Long, progress: Int, total: Int, chunkCount: Int) =
    update(id, ContentValues().apply {
      put("progress", progress)
      put("total", total)
      put("chunk_count", chunkCount)
    })

  fun setReady(id: Long, total: Int, chunkCount: Int) =
    update(id, ContentValues().apply {
      put("status", BookStatus.READY.name)
      put("progress", total)
      put("total", total)
      put("chunk_count", chunkCount)
      putNull("error")
    })

  private fun update(id: Long, values: ContentValues) {
    writableDatabase.update("books", values, "id = ?", arrayOf(id.toString()))
  }

  fun deleteBook(id: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete("chunks", "book_id = ?", arrayOf(id.toString()))
      db.delete("books", "id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  // ---- chunks ------------------------------------------------------------------------------

  fun deleteChunks(bookId: Long) {
    writableDatabase.delete("chunks", "book_id = ?", arrayOf(bookId.toString()))
  }

  /** Inserts (label, text) pairs for one book inside a single transaction. */
  fun insertChunks(bookId: Long, chunks: List<Pair<String, String>>) {
    if (chunks.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val stmt = db.compileStatement("INSERT INTO chunks(content, book_id, label) VALUES (?, ?, ?)")
      for ((label, text) in chunks) {
        stmt.clearBindings()
        stmt.bindString(1, text)
        stmt.bindString(2, bookId.toString())
        stmt.bindString(3, label)
        stmt.executeInsert()
      }
      stmt.close()
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Full-text search ranked with BM25.
   *
   * @param ftsQuery an FTS4 MATCH expression (see QueryBuilder)
   * @param bookIds restrict to these books; null means every book, empty means nothing
   */
  fun search(ftsQuery: String, bookIds: Collection<Long>?, limit: Int): List<SearchHit> {
    if (bookIds != null && bookIds.isEmpty()) return emptyList()
    val db = readableDatabase
    val sql = StringBuilder("SELECT rowid, matchinfo(chunks, 'pcnalx') FROM chunks WHERE chunks MATCH ?")
    val args = arrayListOf(ftsQuery)
    if (bookIds != null) {
      sql.append(" AND book_id IN (").append(bookIds.joinToString(",") { "?" }).append(")")
      bookIds.forEach { args.add(it.toString()) }
    }
    val scored = ArrayList<Pair<Long, Double>>()
    db.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
      while (c.moveToNext()) {
        val blob = c.getBlob(1) ?: continue
        scored.add(c.getLong(0) to Bm25.score(blob))
      }
    }
    if (scored.isEmpty()) return emptyList()
    scored.sortByDescending { it.second }
    val candidates = scored.take(limit * 3)

    val byRowId = HashMap<Long, SearchHit>()
    val ids = candidates.joinToString(",") { it.first.toString() }
    db.rawQuery("SELECT rowid, book_id, label, content FROM chunks WHERE rowid IN ($ids)", null).use { c ->
      while (c.moveToNext()) {
        val rowId = c.getLong(0)
        byRowId[rowId] = SearchHit(rowId, c.getString(1).toLongOrNull() ?: -1L, c.getString(2) ?: "", c.getString(3) ?: "", 0.0)
      }
    }
    val result = ArrayList<SearchHit>(limit)
    for ((rowId, score) in candidates) {
      val hit = byRowId[rowId] ?: continue
      // Neighbouring chunks of the same page/chapter overlap by 50 words; keep only the best one.
      if (result.any { it.bookId == hit.bookId && it.label == hit.label && abs(it.rowId - rowId) <= 1 }) continue
      result.add(hit.copy(score = score))
      if (result.size >= limit) break
    }
    return result
  }

  private fun android.database.Cursor.toBook(): Book = Book(
    id = getLong(getColumnIndexOrThrow("id")),
    title = getString(getColumnIndexOrThrow("title")),
    fileName = getString(getColumnIndexOrThrow("file_name")),
    format = BookFormat.fromName(getString(getColumnIndexOrThrow("format"))) ?: BookFormat.PDF,
    status = runCatching { BookStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }.getOrDefault(BookStatus.ERROR),
    progress = getInt(getColumnIndexOrThrow("progress")),
    total = getInt(getColumnIndexOrThrow("total")),
    chunkCount = getInt(getColumnIndexOrThrow("chunk_count")),
    error = getColumnIndexOrThrow("error").let { if (isNull(it)) null else getString(it) },
    addedAt = getLong(getColumnIndexOrThrow("added_at")),
  )

  companion object {
    const val DB_NAME = "chatbook.db"
    const val DB_VERSION = 1
  }
}
