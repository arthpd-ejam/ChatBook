package com.chatbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material 3 with the phone's own wallpaper colours (dynamic colour exists on every minSdk 31 device). */
@Composable
fun ChatBookTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  MaterialTheme(colorScheme = colorScheme, content = content)
}
