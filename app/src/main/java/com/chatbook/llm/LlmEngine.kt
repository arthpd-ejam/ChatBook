package com.chatbook.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around a LiteRT-LM [Engine].
 *
 * One fresh [Conversation] is created per question so the KV cache only ever holds the system
 * instruction, the retrieved excerpts and the question.
 */
@OptIn(ExperimentalApi::class)
class LlmEngine private constructor(private val engine: Engine, val backendName: String) {

  private val active = AtomicReference<Conversation?>(null)

  /** Streams the model's answer as text chunks. Collect on any dispatcher; work happens on IO. */
  fun generate(systemInstruction: String, prompt: String): Flow<String> = flow {
    val conversation = engine.createConversation(
      ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
      )
    )
    active.set(conversation)
    try {
      emitAll(conversation.sendMessageAsync(Contents.of(prompt)).map { it.toString() })
    } finally {
      active.compareAndSet(conversation, null)
      runCatching { conversation.close() }
    }
  }.flowOn(Dispatchers.IO)

  /** Asks the native engine to stop generating; the active flow then completes. */
  fun stop() {
    runCatching { active.get()?.cancelProcess() }
  }

  fun close() {
    stop()
    runCatching { engine.close() }
  }

  companion object {
    const val DEFAULT_MAX_TOKENS = 4096

    /**
     * Loads the model, trying the GPU first when [preferGpu] is set and falling back to the CPU.
     * Initialisation can take ten seconds or more for a 2-3 GB model.
     */
    suspend fun create(modelPath: String, preferGpu: Boolean, cacheDir: String): LlmEngine =
      withContext(Dispatchers.IO) {
        val attempts: List<Pair<String, () -> Backend>> =
          if (preferGpu) listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
          else listOf("CPU" to { Backend.CPU() })
        var lastError: Throwable? = null
        for ((name, backend) in attempts) {
          var engine: Engine? = null
          try {
            engine = Engine(
              EngineConfig(
                modelPath = modelPath,
                backend = backend(),
                maxNumTokens = DEFAULT_MAX_TOKENS,
                cacheDir = cacheDir,
              )
            )
            engine.initialize()
            return@withContext LlmEngine(engine, name)
          } catch (t: Throwable) {
            lastError = t
            runCatching { engine?.close() }
          }
        }
        throw lastError ?: IllegalStateException("No backend could load the model")
      }
  }
}
