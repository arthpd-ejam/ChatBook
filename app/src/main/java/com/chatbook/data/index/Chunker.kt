package com.chatbook.data.index

/**
 * Splits section text into overlapping word windows.
 *
 * ~300-word chunks with a 50-word overlap keep each chunk small enough that six of them fit in a
 * 4k-token context while the overlap stops an answer from being cut in half at a boundary.
 */
object Chunker {
  const val CHUNK_WORDS = 300
  const val OVERLAP_WORDS = 50
  /** Sections with fewer words than this (blank pages, lone headings) are not indexed. */
  const val MIN_WORDS = 8
  /** If the remainder after a chunk is this short it is merged into the chunk instead of becoming a tiny tail. */
  private const val TAIL_MERGE_WORDS = 100

  fun chunk(text: String): List<String> {
    val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.size < MIN_WORDS) return emptyList()
    val out = ArrayList<String>()
    var start = 0
    while (start < words.size) {
      val remaining = words.size - start
      val end = if (remaining <= CHUNK_WORDS + TAIL_MERGE_WORDS) words.size else start + CHUNK_WORDS
      out.add(words.subList(start, end).joinToString(" "))
      if (end >= words.size) break
      start += CHUNK_WORDS - OVERLAP_WORDS
    }
    return out
  }

  private val WHITESPACE = Regex("\\s+")
}
