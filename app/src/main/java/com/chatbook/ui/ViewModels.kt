package com.chatbook.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbook.AppContainer
import com.chatbook.data.db.Book
import com.chatbook.data.db.BookStatus
import com.chatbook.llm.BackendPref
import com.chatbook.llm.Excerpt
import com.chatbook.llm.ModelState
import com.chatbook.llm.PromptBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
  val books: StateFlow<List<Book>> =
    container.books.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _importing = MutableStateFlow(false)
  val importing: StateFlow<Boolean> = _importing.asStateFlow()

  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  fun import(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      _importing.value = true
      val result = container.books.import(uris)
      _importing.value = false
      val parts = ArrayList<String>()
      if (result.imported > 0) parts.add(if (result.imported == 1) "Added 1 book, indexing…" else "Added ${result.imported} books, indexing…")
      parts.addAll(result.errors)
      _message.value = parts.joinToString("\n").ifEmpty { null }
    }
  }

  fun delete(id: Long) {
    viewModelScope.launch { container.books.delete(id) }
  }

  fun reindex(id: Long) = container.books.reindex(id)

  fun clearMessage() {
    _message.value = null
  }
}

// ---------------------------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------------------------

data class SourceRef(val bookTitle: String, val label: String, val snippet: String)

data class ChatMessage(
  val id: Long,
  val fromUser: Boolean,
  val text: String,
  val sources: List<SourceRef> = emptyList(),
  val streaming: Boolean = false,
  val isError: Boolean = false,
)

data class ChatUiState(
  val messages: List<ChatMessage> = emptyList(),
  val generating: Boolean = false,
  /** null = search every book */
  val selectedBookId: Long? = null,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(ChatUiState())
  val state: StateFlow<ChatUiState> = _state.asStateFlow()

  val books: StateFlow<List<Book>> =
    container.books.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val modelState: StateFlow<ModelState> = container.models.state

  private var nextId = 1L
  private var previousTurn: Pair<String, String>? = null
  private var stopRequested = false
  private var job: Job? = null

  fun selectBook(id: Long?) = _state.update { it.copy(selectedBookId = id) }

  fun clear() {
    stop()
    previousTurn = null
    _state.update { it.copy(messages = emptyList()) }
  }

  fun stop() {
    if (!_state.value.generating) return
    stopRequested = true
    container.models.stop()
  }

  fun send(question: String) {
    val q = question.trim()
    if (q.isEmpty() || _state.value.generating) return
    job = viewModelScope.launch {
      append(ChatMessage(nextId++, fromUser = true, text = q))
      val answerId = nextId++
      append(ChatMessage(answerId, fromUser = false, text = "Searching your books…", streaming = true))
      _state.update { it.copy(generating = true) }
      stopRequested = false
      try {
        val library = container.books.books.first()
        val selected = _state.value.selectedBookId
        val searchable = library
          .filter { selected == null || it.id == selected }
          .filter { it.status == BookStatus.READY || it.status == BookStatus.INDEXING }
        if (searchable.isEmpty()) {
          finish(answerId, if (library.isEmpty()) "Your library is empty. Add a book in the Library tab first." else "That book is not indexed yet.", isError = true)
          return@launch
        }
        val result = container.retriever.search(q, searchable.map { it.id })
        if (result.hits.isEmpty()) {
          finish(answerId, "I couldn't find anything about that in your books. Try asking with different words.")
          return@launch
        }
        val titles = library.associate { it.id to it.title }
        val excerpts = result.hits.map { Excerpt(titles[it.bookId] ?: "Unknown book", it.label, it.content) }
        val sources = result.hits.map { SourceRef(titles[it.bookId] ?: "Unknown book", it.label, it.content.take(200)) }

        if (!container.models.isLoaded) update(answerId) { it.copy(text = "Loading the model (this can take a while the first time)…") }
        val engine = container.models.ensureLoaded()
        if (engine == null) {
          finish(answerId, container.models.state.value.error ?: "The model could not be loaded. Check the Model tab.", isError = true)
          return@launch
        }
        update(answerId) { it.copy(text = "Thinking…") }

        val prompt = PromptBuilder.build(q, excerpts, previousTurn)
        val answer = StringBuilder()
        var failure: Throwable? = null
        engine.generate(PromptBuilder.SYSTEM_INSTRUCTION, prompt)
          .catch { e -> if (!stopRequested) failure = e }
          .collect { chunk ->
            answer.append(chunk)
            update(answerId) { it.copy(text = answer.toString()) }
          }
        val text = when {
          answer.isNotBlank() -> answer.toString().trim() + (if (stopRequested) "\n\n(stopped)" else "")
          failure != null -> "The model failed: ${failure?.message ?: "unknown error"}"
          stopRequested -> "(stopped)"
          else -> "The model returned no answer."
        }
        finish(answerId, text, sources, isError = failure != null && answer.isBlank())
        if (failure == null && answer.isNotBlank()) previousTurn = q to answer.toString()
      } catch (e: Exception) {
        finish(answerId, "Something went wrong: ${e.message ?: e.javaClass.simpleName}", isError = true)
      } finally {
        _state.update { it.copy(generating = false) }
        stopRequested = false
      }
    }
  }

  private fun append(message: ChatMessage) = _state.update { it.copy(messages = it.messages + message) }

  private fun update(id: Long, transform: (ChatMessage) -> ChatMessage) =
    _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it }) }

  private fun finish(id: Long, text: String, sources: List<SourceRef> = emptyList(), isError: Boolean = false) =
    update(id) { it.copy(text = text, sources = sources, streaming = false, isError = isError) }
}

// ---------------------------------------------------------------------------------------------
// Model setup
// ---------------------------------------------------------------------------------------------

class ModelViewModel(private val container: AppContainer) : ViewModel() {
  val state: StateFlow<ModelState> = container.models.state

  fun importModel(uri: Uri) {
    viewModelScope.launch { container.models.importModel(uri) }
  }

  fun setBackend(pref: BackendPref) = container.models.setBackend(pref)

  fun load() {
    viewModelScope.launch { container.models.ensureLoaded() }
  }

  fun unload() = container.models.unload()

  fun deleteModel() {
    viewModelScope.launch { container.models.deleteModel() }
  }
}
