package com.chatbook

import com.chatbook.data.retrieval.Bm25
import com.chatbook.data.retrieval.QueryBuilder
import com.chatbook.llm.Excerpt
import com.chatbook.llm.PromptBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {
  private fun blob(vararg ints: Int): ByteArray {
    val buf = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.nativeOrder())
    ints.forEach { buf.putInt(it) }
    return buf.array()
  }

  @Test
  fun bm25PrefersRareTermsAndShorterDocs() {
    // p=2 phrases, c=3 columns (content, book_id, label), n=1000 rows, avg len col0 = 300.
    val common = blob(2, 3, 1000, 300, 0, 0, /*l*/ 300, 0, 0,
      /*phrase0 col0*/ 2, 5000, 900, 0, 0, 0, 0, 0, 0,
      /*phrase1 col0*/ 0, 50, 20, 0, 0, 0, 0, 0, 0)
    val rare = blob(2, 3, 1000, 300, 0, 0, /*l*/ 300, 0, 0,
      2, 5000, 900, 0, 0, 0, 0, 0, 0,
      1, 50, 20, 0, 0, 0, 0, 0, 0)
    val rareShort = blob(2, 3, 1000, 300, 0, 0, /*l*/ 150, 0, 0,
      2, 5000, 900, 0, 0, 0, 0, 0, 0,
      1, 50, 20, 0, 0, 0, 0, 0, 0)
    val sCommon = Bm25.score(common)
    val sRare = Bm25.score(rare)
    val sRareShort = Bm25.score(rareShort)
    assertTrue(sCommon > 0)
    assertTrue("rare term should add score", sRare > sCommon)
    assertTrue("shorter doc should score higher", sRareShort > sRare)
    assertEquals(0.0, Bm25.score(ByteArray(0)), 0.0)
  }

  @Test
  fun queryBuilderDropsStopwordsAndQuotesTerms() {
    val q = QueryBuilder.ftsQuery("What does the author say about Captain Ahab's whale?")
    assertEquals("\"captain\" OR \"ahab's\" OR \"whale\"", q)
    assertNull(QueryBuilder.ftsQuery("what is it?"))
    assertEquals(listOf("über", "café", "1984"), QueryBuilder.keywords("Über the café in 1984"))
  }

  @Test
  fun promptStaysWithinBudgetAndCitesLabels() {
    val longText = "word ".repeat(5000)
    val prompt = PromptBuilder.build(
      question = "Who is Ahab?",
      excerpts = listOf(Excerpt("Moby-Dick", "p. 42", longText), Excerpt("Moby-Dick", "p. 43", longText)),
      previousTurn = "Hi" to "Hello",
      excerptCharBudget = 2000,
    )
    assertTrue(prompt.contains("[1] Moby-Dick, p. 42"))
    assertTrue(prompt.contains("[2] Moby-Dick, p. 43"))
    assertTrue(prompt.contains("QUESTION: Who is Ahab?"))
    assertTrue("prompt length ${prompt.length}", prompt.length < 2000 + 600)
    assertTrue(prompt.startsWith("Previous exchange"))
  }
}
