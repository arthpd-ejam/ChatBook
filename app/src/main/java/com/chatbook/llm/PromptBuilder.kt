package com.chatbook.llm

/** One retrieved passage, ready to be shown to the model and cited back to the user. */
data class Excerpt(val bookTitle: String, val label: String, val text: String)

/** Builds the system instruction and per-question prompt for the on-device model. */
object PromptBuilder {
  /** Roughly four characters per token for English prose. */
  private const val CHARS_PER_TOKEN = 4

  /** Characters of excerpt text allowed per question (about 2,200 tokens of a 4,096-token window). */
  const val DEFAULT_EXCERPT_CHAR_BUDGET = 9_000

  val SYSTEM_INSTRUCTION = """
    You are ChatBook, an assistant that answers questions about the user's own books.
    You will be given numbered EXCERPTS from those books and then a QUESTION.
    Rules:
    1. Answer using ONLY the excerpts. Do not use outside knowledge.
    2. If the excerpts do not contain the answer, say plainly: "I couldn't find that in your books." You may add what the excerpts do say if it is related.
    3. After each fact, cite its source in square brackets exactly as written in the excerpt header, for example [Moby-Dick, p. 42] or [Dune, Chapter 3].
    4. Be concise: a few sentences, or a short list when listing items.
    5. Write in the same language as the question.
  """.trimIndent()

  fun build(
    question: String,
    excerpts: List<Excerpt>,
    previousTurn: Pair<String, String>? = null,
    excerptCharBudget: Int = DEFAULT_EXCERPT_CHAR_BUDGET,
  ): String {
    val sb = StringBuilder()
    if (previousTurn != null) {
      sb.append("Previous exchange, for context only:\n")
      sb.append("User asked: ").append(previousTurn.first.take(400)).append('\n')
      sb.append("You answered: ").append(previousTurn.second.take(600)).append("\n\n")
    }
    sb.append("EXCERPTS:\n")
    if (excerpts.isEmpty()) {
      sb.append("(no matching passages were found in the library)\n")
    } else {
      val perExcerpt = excerptCharBudget / excerpts.size
      excerpts.forEachIndexed { i, ex ->
        val body = if (ex.text.length > perExcerpt) ex.text.take(perExcerpt).substringBeforeLast(' ') + " ..." else ex.text
        sb.append('[').append(i + 1).append("] ").append(ex.bookTitle).append(", ").append(ex.label).append('\n')
        sb.append(body.trim()).append("\n\n")
      }
    }
    sb.append("QUESTION: ").append(question.trim()).append('\n')
    sb.append("Answer from the excerpts above and cite each source as [Book title, location].")
    return sb.toString()
  }

  fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN + 1
}
