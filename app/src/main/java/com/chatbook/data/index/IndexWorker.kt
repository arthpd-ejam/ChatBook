package com.chatbook.data.index

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chatbook.ChatBookApp
import com.chatbook.R
import com.chatbook.data.BookRepository
import com.chatbook.data.db.Book
import com.chatbook.data.db.BookDatabase
import com.chatbook.data.db.BookStatus
import com.chatbook.data.extract.ExtractListener
import com.chatbook.data.extract.Extractors
import com.chatbook.data.extract.Section
import com.chatbook.data.extract.UnsupportedBookException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Extracts, chunks and indexes one book. Runs as a foreground worker so a 1,000-page PDF keeps
 * going when the app is in the background; if the process dies WorkManager restarts it from scratch.
 */
class IndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
    if (bookId < 0) return Result.failure()
    val container = (applicationContext as ChatBookApp).container
    val db = container.db
    val repo = container.books
    val book = db.getBook(bookId) ?: return Result.failure()

    // May be refused when started from the background; indexing then continues without a notification.
    runCatching { setForeground(foregroundInfo(book.title, 0, 0)) }
    db.setStatus(bookId, BookStatus.INDEXING)
    repo.notifyChanged()

    return try {
      withContext(Dispatchers.IO) { index(book, db, repo) }
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: UnsupportedBookException) {
      fail(db, repo, bookId, e.message ?: "Unsupported file")
    } catch (e: OutOfMemoryError) {
      fail(db, repo, bookId, "Ran out of memory while reading this book. Try a smaller file or restart the app.")
    } catch (e: Throwable) {
      fail(db, repo, bookId, "Could not read this file (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})")
    }
  }

  private fun fail(db: BookDatabase, repo: BookRepository, bookId: Long, message: String): Result {
    db.setStatus(bookId, BookStatus.ERROR, message)
    repo.notifyChanged()
    return Result.failure()
  }

  private suspend fun index(book: Book, db: BookDatabase, repo: BookRepository) {
    val file = repo.fileFor(book)
    if (!file.exists()) throw UnsupportedBookException("The book file is missing. Remove it and import it again.")
    db.deleteChunks(book.id)

    val job = currentCoroutineContext()[Job]
    var bookTitle = book.title
    var total = 0
    var chunkCount = 0
    var lastUiUpdate = 0L
    val buffer = ArrayList<Pair<String, String>>()

    fun flush() {
      if (buffer.isEmpty()) return
      db.insertChunks(book.id, buffer)
      buffer.clear()
    }

    Extractors.forFormat(book.format).extract(file, object : ExtractListener {
      override fun onStart(title: String?, totalSections: Int) {
        total = totalSections
        if (!title.isNullOrBlank() && title.length < 200) {
          bookTitle = title
          db.updateTitle(book.id, title)
        }
      }

      override fun onSection(index: Int, section: Section) {
        if (isStopped || job?.isActive == false) throw CancellationException("Indexing cancelled")
        val chunks = Chunker.chunk(section.text)
        for (chunk in chunks) buffer.add(section.label to chunk)
        chunkCount += chunks.size
        if (buffer.size >= 64) flush()
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdate > 750) {
          lastUiUpdate = now
          db.setProgress(book.id, index + 1, total, chunkCount)
          repo.notifyChanged()
          runCatching { setForegroundAsync(foregroundInfo(bookTitle, index + 1, total)) }
        }
      }
    })
    flush()
    if (chunkCount == 0) throw UnsupportedBookException("No readable text was found in this file.")
    db.setReady(book.id, total, chunkCount)
    repo.notifyChanged()
  }

  private fun foregroundInfo(title: String, progress: Int, total: Int): ForegroundInfo {
    val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
    val notification = NotificationCompat.Builder(applicationContext, ChatBookApp.CHANNEL_INDEXING)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Indexing $title")
      .setContentText(if (total > 0) "$progress of $total sections" else "Reading file…")
      .setProgress(total, progress, total == 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .addAction(0, "Cancel", cancel)
      .build()
    return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  companion object {
    const val KEY_BOOK_ID = "book_id"
    private const val NOTIFICATION_ID = 1001

    fun uniqueName(bookId: Long) = "index-book-$bookId"

    fun enqueue(context: Context, bookId: Long, policy: ExistingWorkPolicy) {
      val request = OneTimeWorkRequestBuilder<IndexWorker>()
        .setInputData(workDataOf(KEY_BOOK_ID to bookId))
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(bookId), policy, request)
    }

    fun ensureChannel(context: Context) {
      val manager = context.getSystemService(NotificationManager::class.java)
      if (manager.getNotificationChannel(ChatBookApp.CHANNEL_INDEXING) == null) {
        manager.createNotificationChannel(
          android.app.NotificationChannel(
            ChatBookApp.CHANNEL_INDEXING,
            "Book indexing",
            NotificationManager.IMPORTANCE_LOW,
          ).apply { description = "Progress while a book is being read and indexed" }
        )
      }
    }
  }
}
