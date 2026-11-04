package com.chatbook.data.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * BM25 ranking computed from SQLite FTS4 `matchinfo(table, 'pcnalx')` blobs.
 *
 * Blob layout (32-bit native-endian ints):
 *  p  number of phrases in the query
 *  c  number of columns
 *  n  number of rows in the table
 *  a  c values: average tokens per column
 *  l  c values: tokens in this row per column
 *  x  3 values per (phrase, column): hits in this row, hits in all rows, rows with a hit
 */
object Bm25 {
  private const val K1 = 1.2
  private const val B = 0.75

  fun score(matchinfo: ByteArray, column: Int = 0): Double {
    if (matchinfo.size < 12) return 0.0
    val ints = IntArray(matchinfo.size / 4)
    ByteBuffer.wrap(matchinfo).order(ByteOrder.nativeOrder()).asIntBuffer().get(ints)
    val p = ints[0]
    val c = ints[1]
    val n = ints[2].toDouble()
    if (p <= 0 || c <= 0 || column >= c) return 0.0
    val avgLen = max(ints[3 + column].toDouble(), 1.0)
    val docLen = ints[3 + c + column].toDouble()
    val xBase = 3 + 2 * c
    var score = 0.0
    for (phrase in 0 until p) {
      val idx = xBase + 3 * (phrase * c + column)
      if (idx + 2 >= ints.size) break
      val tf = ints[idx].toDouble()
      val df = ints[idx + 2].toDouble()
      if (tf <= 0.0 || df <= 0.0) continue
      val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0)
      val denom = tf + K1 * (1.0 - B + B * docLen / avgLen)
      score += idf * (tf * (K1 + 1.0)) / denom
    }
    return score
  }
}
