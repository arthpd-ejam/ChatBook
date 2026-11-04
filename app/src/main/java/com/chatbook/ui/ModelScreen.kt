package com.chatbook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.chatbook.llm.BackendPref
import com.chatbook.llm.EngineStatus
import com.chatbook.llm.ModelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: ModelViewModel, modifier: Modifier = Modifier) {
  val state by vm.state.collectAsState()
  val uriHandler = LocalUriHandler.current
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.importModel(uri)
  }
  val copying = state.copyProgress != null

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    TopAppBar(title = { Text("AI model") })
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Text("Model file", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(6.dp))
          if (state.modelFile == null) {
            Text(
              "No model yet. Download one of the models below on this phone, then tap \"Choose model file\" and pick the .litertlm file from your Downloads.",
              style = MaterialTheme.typography.bodyMedium,
            )
          } else {
            Text(state.modelName ?: "", style = MaterialTheme.typography.bodyMedium)
            Text(formatBytes(state.modelSizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (copying) {
            Spacer(Modifier.height(10.dp))
            Text("Copying into ChatBook… ${(state.copyProgress!! * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { state.copyProgress!! }, modifier = Modifier.fillMaxWidth())
          }
          state.copyError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
          Spacer(Modifier.height(12.dp))
          Row {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !copying) { Text("Choose model file") }
            if (state.modelFile != null) {
              Spacer(Modifier.width(8.dp))
              TextButton(onClick = { vm.deleteModel() }, enabled = !copying) { Text("Delete") }
            }
          }
        }
      }

      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Text("Runs on", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(6.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.backendPref == BackendPref.GPU, onClick = { vm.setBackend(BackendPref.GPU) }, label = { Text("GPU (faster)") })
            FilterChip(selected = state.backendPref == BackendPref.CPU, onClick = { vm.setBackend(BackendPref.CPU) }, label = { Text("CPU") })
          }
          Spacer(Modifier.height(6.dp))
          Text(
            "GPU is tried first and falls back to CPU automatically if it fails. Switch to CPU if answers come out garbled or the app crashes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(12.dp))
          val statusText = when (state.status) {
            EngineStatus.NO_MODEL -> "Status: no model"
            EngineStatus.NOT_LOADED -> "Status: not loaded (loads on your first question)"
            EngineStatus.LOADING -> "Status: loading… this can take 10-30 seconds"
            EngineStatus.READY -> "Status: ready on ${state.activeBackend ?: "?"}"
            EngineStatus.ERROR -> "Status: failed to load"
          }
          Text(statusText, style = MaterialTheme.typography.bodyMedium)
          if (state.status == EngineStatus.LOADING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
          state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
          Spacer(Modifier.height(12.dp))
          Row {
            when (state.status) {
              EngineStatus.NOT_LOADED, EngineStatus.ERROR -> OutlinedButton(onClick = { vm.load() }) { Text("Load now") }
              EngineStatus.READY -> OutlinedButton(onClick = { vm.unload() }) { Text("Unload (free memory)") }
              else -> Unit
            }
          }
        }
      }

      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
          Text("Get a model", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(6.dp))
          val ramGb = state.totalRamBytes / (1024.0 * 1024.0 * 1024.0)
          Text(
            "This phone has about ${"%.0f".format(ramGb)} GB of RAM. " +
              if (ramGb >= 7) "Gemma 4 E2B should work well." else if (ramGb >= 5) "Gemma 4 E2B may work; Gemma 3 1B is the safe choice." else "Use Gemma 3 1B or Qwen3 0.6B.",
            style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "Open a link, sign in to Hugging Face (Gemma asks you to accept its license once), open \"Files and versions\" and download the .litertlm file. Then come back and choose it above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(10.dp))
          ModelManager.SUGGESTED_MODELS.forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
              Text("${m.name} · ${m.size}", style = MaterialTheme.typography.bodyMedium)
              Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              TextButton(onClick = { uriHandler.openUri(m.url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Open download page")
              }
            }
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

private fun formatBytes(bytes: Long): String = when {
  bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
  bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
  else -> "$bytes bytes"
}
