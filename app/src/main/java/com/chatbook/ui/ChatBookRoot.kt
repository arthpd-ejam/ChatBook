package com.chatbook.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatbook.AppContainer

private enum class Tab(val label: String, val icon: ImageVector) {
  LIBRARY("Library", Icons.AutoMirrored.Filled.List),
  CHAT("Chat", Icons.AutoMirrored.Filled.Send),
  MODEL("Model", Icons.Default.Settings),
}

@Composable
fun ChatBookRoot(container: AppContainer) {
  var tabIndex by rememberSaveable { mutableIntStateOf(0) }
  val libraryVm: LibraryViewModel = viewModel { LibraryViewModel(container) }
  val chatVm: ChatViewModel = viewModel { ChatViewModel(container) }
  val modelVm: ModelViewModel = viewModel { ModelViewModel(container) }

  Scaffold(
    // Screens draw their own top bars, which handle the status bar inset themselves.
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      NavigationBar {
        Tab.entries.forEachIndexed { index, tab ->
          NavigationBarItem(
            selected = tabIndex == index,
            onClick = { tabIndex = index },
            icon = { Icon(tab.icon, contentDescription = tab.label) },
            label = { Text(tab.label) },
          )
        }
      }
    },
  ) { padding ->
    // consumeWindowInsets lets imePadding() in the chat screen add only what the bottom bar does not cover.
    val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
    when (Tab.entries[tabIndex]) {
      Tab.LIBRARY -> LibraryScreen(libraryVm, modifier)
      Tab.CHAT -> ChatScreen(chatVm, modifier)
      Tab.MODEL -> ModelScreen(modelVm, modifier)
    }
  }
}
