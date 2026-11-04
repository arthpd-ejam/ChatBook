package com.chatbook.data.db

import com.chatbook.data.extract.BookFormat

enum class BookStatus { PENDING, INDEXING, READY, ERROR }

data class Book(
  val id: Long,
  val title: String,
  val fileName: String,
  val format: BookFormat,
  val status: BookStatus,
  /** Sections (pages/chapters) processed so far. */
  val progress: Int,
  /** Total sections, 0 while unknown. */
  val total: Int,
  val chunkCount: Int,
  val error: String?,
  val addedAt: Long,
) {
  val progressFraction: Float?
    get() = if (status == BookStatus.INDEXING && total > 0) (progress.toFloat() / total).coerceIn(0f, 1f) else null
}

/** One chunk returned by full-text search. */
data class SearchHit(
  val rowId: Long,
  val bookId: Long,
  val label: String,
  val content: String,
  val score: Double,
)
