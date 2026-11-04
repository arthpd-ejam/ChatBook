package com.chatbook.data.retrieval

import android.database.sqlite.SQLiteException
import com.chatbook.data.db.BookDatabase
import com.chatbook.data.db.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Question → keywords → FTS4 MATCH → BM25-ranked chunks. */
class Retriever(private val db: BookDatabase) {

  data class Result(val hits: List<SearchHit>, val keywords: List<String>)

  suspend fun search(question: String, bookIds: Collection<Long>?, limit: Int = DEFAULT_LIMIT): Result =
    withContext(Dispatchers.IO) {
      val query = QueryBuilder.ftsQuery(question) ?: return@withContext Result(emptyList(), emptyList())
      val hits = try {
        db.search(query, bookIds, limit)
      } catch (e: SQLiteException) {
        emptyList()
      }
      Result(hits, QueryBuilder.keywords(question))
    }

  companion object {
    const val DEFAULT_LIMIT = 6
  }
}
