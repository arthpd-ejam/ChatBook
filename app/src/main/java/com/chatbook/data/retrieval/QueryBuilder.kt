package com.chatbook.data.retrieval

/** Turns a natural-language question into an FTS4 `MATCH` expression of OR-ed keywords. */
object QueryBuilder {
  private const val MAX_TERMS = 12

  fun keywords(question: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (raw in question.lowercase().split(SPLIT)) {
      val term = raw.trim('\'', '’', '-', '_')
      if (term.length < 2) continue
      if (term in STOPWORDS) continue
      seen.add(term)
    }
    // Prefer longer, more specific words when there are too many.
    return seen.sortedByDescending { it.length }.take(MAX_TERMS)
  }

  /** Returns an FTS MATCH string such as `"whale" OR "ahab" OR "harpoon"`, or null if nothing is searchable. */
  fun ftsQuery(question: String): String? {
    val terms = keywords(question)
    if (terms.isEmpty()) return null
    return terms.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }
  }

  private val SPLIT = Regex("[^\\p{L}\\p{N}'’\\-_]+")

  private val STOPWORDS = setOf(
    "a", "an", "the", "and", "or", "but", "if", "then", "than", "so", "as", "of", "at", "by", "for",
    "from", "in", "into", "on", "onto", "to", "with", "without", "about", "over", "under", "between",
    "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "doing", "have", "has",
    "had", "having", "will", "would", "shall", "should", "can", "could", "may", "might", "must",
    "i", "me", "my", "mine", "we", "us", "our", "you", "your", "he", "him", "his", "she", "her", "it",
    "its", "they", "them", "their", "this", "that", "these", "those", "there", "here", "who", "whom",
    "whose", "what", "which", "when", "where", "why", "how", "not", "no", "nor", "any", "all", "some",
    "each", "every", "both", "few", "more", "most", "other", "such", "only", "own", "same", "very",
    "just", "also", "too", "again", "once", "up", "down", "out", "off", "again", "further", "because",
    "while", "during", "before", "after", "above", "below", "through", "against", "please", "tell",
    "explain", "describe", "say", "says", "said", "know", "think", "mean", "means", "give", "find",
    "book", "books", "chapter", "page", "text", "author", "according", "mention", "mentioned",
    "something", "anything", "thing", "things", "way", "like", "get", "got", "much", "many", "lot",
    "does", "doesn't", "don't", "isn't", "aren't", "wasn't", "can't", "cannot", "won't", "did",
    "really", "exactly", "actually", "around", "one", "two", "first", "last", "s", "t",
  )
}
