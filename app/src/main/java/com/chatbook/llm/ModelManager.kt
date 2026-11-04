package com.chatbook.llm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BackendPref { GPU, CPU }

enum class EngineStatus { NO_MODEL, NOT_LOADED, LOADING, READY, ERROR }

data class ModelState(
  val modelFile: File? = null,
  val modelSizeBytes: Long = 0L,
  val backendPref: BackendPref = BackendPref.GPU,
  val status: EngineStatus = EngineStatus.NO_MODEL,
  /** Backend actually in use once loaded ("GPU" or "CPU"). */
  val activeBackend: String? = null,
  val error: String? = null,
  /** 0..1 while a model file is being copied into app storage, null otherwise. */
  val copyProgress: Float? = null,
  val copyError: String? = null,
  val totalRamBytes: Long = 0L,
) {
  val modelName: String? get() = modelFile?.name
}

/**
 * Owns the model file (copied once from the user's pick into app storage because LiteRT-LM needs a
 * real path), the GPU/CPU preference and the loaded [LlmEngine].
 */
class ModelManager(private val context: Context) {

  private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
  private val mutex = Mutex()
  private var engine: LlmEngine? = null

  private val _state = MutableStateFlow(initialState())
  val state: StateFlow<ModelState> = _state.asStateFlow()

  val modelsDir: File
    get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").also { it.mkdirs() }

  private fun initialState(): ModelState {
    val file = prefs.getString(KEY_MODEL_PATH, null)?.let(::File)?.takeIf { it.isFile }
    val pref = runCatching { BackendPref.valueOf(prefs.getString(KEY_BACKEND, BackendPref.GPU.name)!!) }
      .getOrDefault(BackendPref.GPU)
    val ram = runCatching {
      val info = ActivityManager.MemoryInfo()
      context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
      info.totalMem
    }.getOrDefault(0L)
    return ModelState(
      modelFile = file,
      modelSizeBytes = file?.length() ?: 0L,
      backendPref = pref,
      status = if (file == null) EngineStatus.NO_MODEL else EngineStatus.NOT_LOADED,
      totalRamBytes = ram,
    )
  }

  val isLoaded: Boolean get() = engine != null

  /** Copies the picked file into app storage, replacing any previous model. */
  suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
    unload()
    val name = displayName(uri) ?: "model.litertlm"
    if (!name.endsWith(".litertlm", ignoreCase = true)) {
      _state.update { it.copy(copyError = "\"$name\" is not a .litertlm file. Download a LiteRT-LM model (see below).") }
      return@withContext
    }
    val size = fileSize(uri)
    val target = File(modelsDir, name)
    val tmp = File(modelsDir, "$name.part")
    _state.update { it.copy(copyProgress = 0f, copyError = null) }
    try {
      val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
      var copied = 0L
      var lastUpdate = 0L
      input.use { source ->
        tmp.outputStream().use { out ->
          val buffer = ByteArray(1 shl 20)
          while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            copied += n
            val now = SystemClock.elapsedRealtime()
            if (size > 0 && now - lastUpdate > 200) {
              lastUpdate = now
              _state.update { it.copy(copyProgress = (copied.toFloat() / size).coerceIn(0f, 1f)) }
            }
          }
        }
      }
      // Only one model is kept to save space.
      modelsDir.listFiles()?.filter { it != tmp }?.forEach { it.delete() }
      if (!tmp.renameTo(target)) throw IOException("Could not move the model into place")
      prefs.edit().putString(KEY_MODEL_PATH, target.absolutePath).apply()
      _state.update {
        it.copy(
          modelFile = target,
          modelSizeBytes = target.length(),
          status = EngineStatus.NOT_LOADED,
          activeBackend = null,
          error = null,
          copyProgress = null,
          copyError = null,
        )
      }
    } catch (e: Exception) {
      tmp.delete()
      _state.update { it.copy(copyProgress = null, copyError = "Copy failed: ${e.message ?: e.javaClass.simpleName}") }
    }
  }

  fun setBackend(pref: BackendPref) {
    if (pref == _state.value.backendPref) return
    prefs.edit().putString(KEY_BACKEND, pref.name).apply()
    unload()
    _state.update { it.copy(backendPref = pref) }
  }

  /** Loads the engine if needed. Returns it, or null (with [ModelState.error] set) on failure. */
  suspend fun ensureLoaded(): LlmEngine? = mutex.withLock {
    engine?.let { return@withLock it }
    val file = _state.value.modelFile
    if (file == null || !file.isFile) {
      _state.update { it.copy(status = EngineStatus.NO_MODEL, error = "No model file yet. Open the Model tab to add one.") }
      return@withLock null
    }
    _state.update { it.copy(status = EngineStatus.LOADING, error = null) }
    try {
      val loaded = LlmEngine.create(
        modelPath = file.absolutePath,
        preferGpu = _state.value.backendPref == BackendPref.GPU,
        cacheDir = context.cacheDir.absolutePath,
      )
      engine = loaded
      _state.update { it.copy(status = EngineStatus.READY, activeBackend = loaded.backendName, error = null) }
      loaded
    } catch (t: Throwable) {
      _state.update { it.copy(status = EngineStatus.ERROR, activeBackend = null, error = friendlyError(t)) }
      null
    }
  }

  fun unload() {
    engine?.close()
    engine = null
    _state.update {
      it.copy(
        status = if (it.modelFile == null) EngineStatus.NO_MODEL else EngineStatus.NOT_LOADED,
        activeBackend = null,
      )
    }
  }

  suspend fun deleteModel() = withContext(Dispatchers.IO) {
    unload()
    _state.value.modelFile?.delete()
    modelsDir.listFiles()?.forEach { it.delete() }
    prefs.edit().remove(KEY_MODEL_PATH).apply()
    _state.update { it.copy(modelFile = null, modelSizeBytes = 0L, status = EngineStatus.NO_MODEL, activeBackend = null, error = null) }
  }

  fun generate(systemInstruction: String, prompt: String): Flow<String> =
    engine?.generate(systemInstruction, prompt) ?: flow { throw IllegalStateException("The model is not loaded") }

  fun stop() {
    engine?.stop()
  }

  private fun friendlyError(t: Throwable): String {
    val msg = t.message ?: t.javaClass.simpleName
    return when {
      t is OutOfMemoryError || msg.contains("memory", ignoreCase = true) ->
        "Not enough memory to load this model. Try a smaller model such as Gemma 3 1B, or switch to CPU."
      msg.contains("UnsatisfiedLink", ignoreCase = true) || t is UnsatisfiedLinkError ->
        "The LiteRT-LM native library failed to load on this phone ($msg)."
      else -> "Could not load the model: $msg"
    }
  }

  private fun displayName(uri: Uri): String? {
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return c.getString(idx)
        }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
  }

  private fun fileSize(uri: Uri): Long {
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.SIZE)
          if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
        }
      }
    }
    return -1L
  }

  companion object {
    private const val KEY_MODEL_PATH = "model_path"
    private const val KEY_BACKEND = "backend"

    data class ModelLink(val name: String, val size: String, val note: String, val url: String)

    val SUGGESTED_MODELS = listOf(
      ModelLink(
        "Gemma 4 E2B", "about 2.6 GB", "Best answers. Needs a phone with 6-8 GB RAM or more.",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
      ),
      ModelLink(
        "Gemma 3 1B", "about 0.5 GB", "Runs on most phones; shorter, simpler answers.",
        "https://huggingface.co/litert-community/Gemma3-1B-IT",
      ),
      ModelLink(
        "Qwen3 0.6B", "about 0.5 GB", "Alternative small model, no license click-through.",
        "https://huggingface.co/litert-community/Qwen3-0.6B",
      ),
    )
  }
}
