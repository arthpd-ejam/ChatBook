package com.chatbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.chatbook.llm.EngineStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier) {
  val state by vm.state.collectAsState()
  val books by vm.books.collectAsState()
  val model by vm.modelState.collectAsState()
  var input by rememberSaveable { mutableStateOf("") }
  var bookMenuOpen by remember { mutableStateOf(false) }
  var showSource by remember { mutableStateOf<SourceRef?>(null) }
  val listState = rememberLazyListState()

  val lastText = state.messages.lastOrNull()?.text?.length ?: 0
  LaunchedEffect(state.messages.size, lastText) {
    if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
  }

  Column(modifier.imePadding()) {
    TopAppBar(
      title = { Text("Chat") },
      actions = {
        Box {
          TextButton(onClick = { bookMenuOpen = true }) {
            val label = books.firstOrNull { it.id == state.selectedBookId }?.title ?: "All books"
            Text(label.take(22) + if (label.length > 22) "…" else "")
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
          }
          DropdownMenu(expanded = bookMenuOpen, onDismissRequest = { bookMenuOpen = false }) {
            DropdownMenuItem(text = { Text("All books") }, onClick = { vm.selectBook(null); bookMenuOpen = false })
            books.forEach { book ->
              DropdownMenuItem(text = { Text(book.title) }, onClick = { vm.selectBook(book.id); bookMenuOpen = false })
            }
          }
        }
        if (state.messages.isNotEmpty()) {
          IconButton(onClick = { vm.clear() }) { Icon(Icons.Default.Delete, contentDescription = "Clear chat") }
        }
      },
    )

    val statusLine = when (model.status) {
      EngineStatus.NO_MODEL -> "No AI model yet. Open the Model tab to add one before asking questions."
      EngineStatus.NOT_LOADED -> "Model ready to load: ${model.modelName}. It loads on your first question."
      EngineStatus.LOADING -> "Loading the model…"
      EngineStatus.READY -> null
      EngineStatus.ERROR -> model.error ?: "The model failed to load."
    }
    if (statusLine != null) {
      Surface(
        color = if (model.status == EngineStatus.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
      ) {
        Text(statusLine, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
      }
    }

    if (state.messages.isEmpty()) {
      Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
          "Ask anything about the books in your library, for example \"What does chapter 3 say about habits?\" " +
            "Answers come only from your books and cite the book and page.",
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(state.messages, key = { it.id }) { message ->
          MessageBubble(message, onSourceClick = { showSource = it })
        }
      }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Ask about your books…") },
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
          if (!state.generating && input.isNotBlank()) { vm.send(input); input = "" }
        }),
      )
      Spacer(Modifier.width(8.dp))
      if (state.generating) {
        FilledIconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Close, contentDescription = "Stop") }
      } else {
        FilledIconButton(onClick = { if (input.isNotBlank()) { vm.send(input); input = "" } }, enabled = input.isNotBlank()) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
      }
    }
  }

  showSource?.let { source ->
    AlertDialog(
      onDismissRequest = { showSource = null },
      title = { Text("${source.bookTitle}, ${source.label}") },
      text = { Text(source.snippet + if (source.snippet.length >= 200) "…" else "") },
      confirmButton = { TextButton(onClick = { showSource = null }) { Text("Close") } },
    )
  }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSourceClick: (SourceRef) -> Unit) {
  val isUser = message.fromUser
  val color = when {
    message.isError -> MaterialTheme.colorScheme.errorContainer
    isUser -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
    Surface(color = color, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
      SelectionContainer {
        Text(
          message.text + if (message.streaming) " ▍" else "",
          Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    if (message.sources.isNotEmpty()) {
      Spacer(Modifier.height(4.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        message.sources.forEach { source ->
          SuggestionChip(
            onClick = { onSourceClick(source) },
            label = { Text("${source.bookTitle.take(24)}${if (source.bookTitle.length > 24) "…" else ""}, ${source.label}", style = MaterialTheme.typography.labelSmall) },
          )
        }
      }
    }
  }
}
