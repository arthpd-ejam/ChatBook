package com.chatbook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chatbook.ui.ChatBookRoot
import com.chatbook.ui.theme.ChatBookTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val container = (application as ChatBookApp).container
    setContent {
      ChatBookTheme {
        ChatBookRoot(container)
      }
    }
  }
}
