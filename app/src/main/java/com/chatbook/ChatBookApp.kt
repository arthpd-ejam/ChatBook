package com.chatbook

import android.app.Application
import android.content.Context
import com.chatbook.data.BookRepository
import com.chatbook.data.db.BookDatabase
import com.chatbook.data.index.IndexWorker
import com.chatbook.data.retrieval.Retriever
import com.chatbook.llm.ModelManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
  val db = BookDatabase(context)
  val books = BookRepository(context, db)
  val retriever = Retriever(db)
  val models = ModelManager(context)
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class ChatBookApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    PDFBoxResourceLoader.init(applicationContext)
    container = AppContainer(this)
    IndexWorker.ensureChannel(this)
    container.scope.launch { runCatching { container.books.resumePending() } }
  }

  companion object {
    const val CHANNEL_INDEXING = "indexing"
  }
}
