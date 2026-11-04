# ChatBook

Ask questions about your own books, fully offline, on an Android phone.

ChatBook reads PDF, EPUB and MOBI files you pick from your phone, builds a search index, and answers
questions with a small AI model that runs entirely on the device (Google's LiteRT-LM runtime with a
Gemma or Qwen model). No account, no cloud, no API key. The app does not even ask for internet permission.

## How to get the app on your phone

You do not need Android Studio. GitHub builds the APK for you.

1. Open this repository on GitHub and click the **Actions** tab.
2. Click the newest **Build APK** run (green check mark = success).
3. Scroll down to **Artifacts** and download **ChatBook-debug-apk**. It is a zip file.
4. Unzip it; inside is `app-debug.apk`. Copy that file to your phone (USB cable, Google Drive, email to yourself, ...).
5. On the phone, open the file. Android will ask you to allow installing from this source. Allow it, then tap Install.

Every push to the repository builds a fresh APK. To update the app, repeat the steps and install over the old one.

## First-time setup on the phone

1. **Get an AI model.** Open ChatBook, go to the **Model** tab and tap one of the "Open download page" links.
   - *Gemma 4 E2B* (about 2.6 GB) gives the best answers but needs a phone with 6 to 8 GB of RAM or more.
   - *Gemma 3 1B* (about 0.5 GB) works on most phones with shorter answers.
   - Gemma models need a free Hugging Face account and a one-time click to accept Google's license.
   - On the model page open **Files and versions** and download the file ending in `.litertlm`.
2. Back in ChatBook, tap **Choose model file** and pick the downloaded `.litertlm` file. The app copies it
   into its own storage (a progress bar shows this; it can take a minute for a 2.6 GB file).
3. Go to the **Library** tab, tap **Add books** and pick your PDF, EPUB or MOBI files. You can pick many at once.
   Each book is read and indexed in the background; a notification shows progress. A 500-page PDF takes
   a minute or two. You can add more books any time.
4. Go to the **Chat** tab and ask a question. The first question loads the model, which takes 10 to 30
   seconds. Answers stream in and end with chips showing which book and page they came from. Tap a chip to
   see the passage.

Use the drop-down at the top of the Chat tab to limit a question to one book.

## Good to know

- **Kindle store books cannot be read.** Files bought from Amazon (`.azw`, most `.mobi` from Kindle) are
  DRM-protected. DRM-free MOBI files, for example from Project Gutenberg or converted with Calibre, work fine.
- **Scanned PDFs** that are only pictures of pages have no text to read. ChatBook will tell you. Running OCR
  on them first (for example with Adobe Scan or Google Drive) produces a PDF that works.
- **Answers only come from your books.** If the model cannot find the answer in the retrieved passages it says so
  instead of guessing. Asking with the words the book itself uses works best, because the search is keyword based.
- **GPU or CPU.** The model tries the phone's GPU first and falls back to the CPU. If answers look garbled or the
  app crashes while answering, switch to CPU in the Model tab.
- **Storage.** Books are copied into the app, so removing a book in ChatBook never touches your original file.
  Uninstalling the app deletes the copied books and the model.

## How it works (for the curious)

1. **Text extraction.** PDFs are read page by page with PdfBox-Android. EPUBs are unzipped and each chapter's
   XHTML is turned into text with jsoup. MOBI files are parsed by a small built-in reader (PalmDB container,
   PalmDOC decompression, then the same HTML to text step).
2. **Chunking.** Each page or chapter is split into roughly 300-word passages with a 50-word overlap.
3. **Retrieval.** Passages live in a SQLite FTS4 full-text table with the Porter stemmer. A question is reduced
   to keywords, matched against the index, and the hits are ranked with BM25 (computed in Kotlin from SQLite's
   `matchinfo`). The best six passages go to the model.
4. **Answering.** LiteRT-LM runs the model on the phone. Each question gets a fresh conversation containing a
   system instruction ("answer only from the excerpts, cite them"), the passages and the question, so the whole
   exchange fits in a 4,096-token context.
5. **Indexing in the background.** A WorkManager foreground worker does the reading, so it continues when you
   leave the app and restarts by itself if the phone kills the process.

## Project layout

```
app/src/main/java/com/chatbook/
  ChatBookApp.kt            Application + dependency container
  MainActivity.kt           Single activity hosting the Compose UI
  data/db/                  SQLite schema, books table, FTS4 chunks table, BM25-ranked search
  data/extract/             PDF, EPUB and MOBI (PalmDOC) text extraction
  data/index/               Chunker and the background indexing worker
  data/retrieval/           Keyword extraction and BM25 scoring
  llm/                      LiteRT-LM wrapper, model file management, prompt building
  ui/                       Library, Chat and Model screens plus their view models
app/src/test/               JUnit tests for the parsers, chunker, ranking and prompt
.github/workflows/build.yml GitHub Actions workflow that builds and uploads the APK
```

## Building locally (optional)

If you ever install Android Studio or the command-line SDK:

```bash
./gradlew assembleDebug
```

The APK appears at `app/build/outputs/apk/debug/app-debug.apk`.
