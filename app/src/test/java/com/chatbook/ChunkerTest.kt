package com.chatbook

import com.chatbook.data.index.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
  private fun words(n: Int) = (1..n).joinToString(" ") { "w$it" }

  @Test
  fun shortSectionIsOneChunk() {
    assertEquals(1, Chunker.chunk(words(120)).size)
  }

  @Test
  fun tooShortSectionIsDropped() {
    assertTrue(Chunker.chunk("Chapter 1").isEmpty())
    assertTrue(Chunker.chunk("   \n ").isEmpty())
  }

  @Test
  fun longSectionOverlaps() {
    val chunks = Chunker.chunk(words(1000))
    // 0..300, 250..550, 500..800, 750..1000 (tail of 250 merged? 1000-750=250 <= 400 so last chunk ends at 1000)
    assertEquals(4, chunks.size)
    val first = chunks[0].split(" ")
    val second = chunks[1].split(" ")
    assertEquals(300, first.size)
    assertEquals("w251", second.first())
    assertEquals("w1000", chunks.last().split(" ").last())
    // every word appears in some chunk
    val all = chunks.flatMap { it.split(" ") }.toSet()
    assertEquals(1000, all.size)
  }

  @Test
  fun tinyTailIsMergedIntoPreviousChunk() {
    val chunks = Chunker.chunk(words(320))
    assertEquals(1, chunks.size)
    assertEquals(320, chunks[0].split(" ").size)
  }
}
