package com.chatbook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chatbook.data.db.Book
import com.chatbook.data.db.BookStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, modifier: Modifier = Modifier) {
  val books by vm.books.collectAsState()
  val importing by vm.importing.collectAsState()
  val message by vm.message.collectAsState()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    vm.import(uris)
  }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  LaunchedEffect(message) {
    val m = message ?: return@LaunchedEffect
    snackbar.showSnackbar(m)
    vm.clearMessage()
  }

  var pendingDelete by remember { mutableStateOf<Book?>(null) }

  Scaffold(
    modifier = modifier,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { TopAppBar(title = { Text("Library") }) },
    snackbarHost = { SnackbarHost(snackbar) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { if (!importing) picker.launch(arrayOf("*/*")) },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(if (importing) "Copying…" else "Add books") },
      )
    },
  ) { padding ->
    if (books.isEmpty()) {
      EmptyLibrary(Modifier.fillMaxSize().padding(padding))
    } else {
      LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(books, key = { it.id }) { book ->
          BookRow(book, onDelete = { pendingDelete = book }, onRetry = { vm.reindex(book.id) })
        }
      }
    }
  }

  pendingDelete?.let { book ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Remove this book?") },
      text = { Text("\"${book.title}\" and its index will be deleted from ChatBook. The original file on your phone is not touched.") },
      confirmButton = { TextButton(onClick = { vm.delete(book.id); pendingDelete = null }) { Text("Remove") } },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
  Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text("No books yet", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
      "Tap \"Add books\" and pick PDF, EPUB or MOBI files from your phone. ChatBook reads them once, builds a search index, and then you can ask questions in the Chat tab.",
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      "Kindle-store books (DRM) and scanned PDFs without a text layer cannot be read.",
      style = MaterialTheme.typography.bodySmall,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun BookRow(book: Book, onDelete: () -> Unit, onRetry: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        val status = when (book.status) {
          BookStatus.PENDING -> "Waiting to be indexed…"
          BookStatus.INDEXING -> if (book.total > 0) "Indexing ${book.progress} of ${book.total} ${unitFor(book)}…" else "Reading file…"
          BookStatus.READY -> "${book.format.name} · ${book.total} ${unitFor(book)} · ${book.chunkCount} passages"
          BookStatus.ERROR -> book.error ?: "Could not read this book"
        }
        Text(
          status,
          style = MaterialTheme.typography.bodySmall,
          color = if (book.status == BookStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        book.progressFraction?.let { fraction ->
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (book.status == BookStatus.INDEXING && book.total == 0) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
      }
      if (book.status == BookStatus.ERROR) {
        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "Try again") }
      }
      IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
    }
  }
}

private fun unitFor(book: Book): String = when (book.format) {
  com.chatbook.data.extract.BookFormat.PDF -> "pages"
  else -> "chapters"
}
