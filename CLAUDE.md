# ChatBook — project brief

Android chat app for asking questions about the user's own books. Fully offline.
The owner is Arth (Windows 10, a video editor rather than an Android developer), so give plain-language steps and avoid assuming Android Studio knowledge.

## Requirements (decided with the user)
- **AI runs fully offline, on the phone.** No cloud API and no API key.
- **Book formats:** PDF, EPUB and MOBI.
- **Loading:** books are imported inside the app from phone storage (Android file picker), and more can be added later. Books are NOT bundled in the APK.
- **Library size:** 30+ books, possibly very large. A whole book can't fit in the model context, so the app must do retrieval: search the books, then send only the relevant passages.
- **Build:** the user doesn't want Android Studio. A GitHub Actions workflow builds the APK, and the user downloads it from the Actions tab (repo is private; 2,000 free minutes/month).

## Environment constraint
Claude's cloud sandboxes can't reach maven.google.com, repo.maven.apache.org, services.gradle.org or dl.google.com (proxy rejects them), so **Gradle builds can't run in the sandbox**. Write the code carefully, push, and let GitHub Actions compile it. Read CI logs with `gh run view --log-failed` if the session's gh token works, or ask the user to paste the error.

## Chosen stack (verified Nov 2026 against Google's AI Edge Gallery app, which ships with these)
- **LLM runtime: LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.18.1`). The MediaPipe LLM Inference API (`tasks-genai`) is in maintenance mode, so don't use it.
- AGP **9.0.1** (has built-in Kotlin, so don't apply `org.jetbrains.kotlin.android` in the app module), Kotlin compose plugin **2.2.21**, Gradle wrapper **9.2.1**, Compose BOM **2026.02.00**, minSdk **31**, Java/JVM target 11.
- Kotlin compiler arg `-Xskip-metadata-version-check` (Gallery needs it for the litertlm AAR).
- `packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }`
- Manifest, inside `<application>`: `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and the same for `libvndksupport.so` (needed for the GPU backend).
- Reference project: https://github.com/google-ai-edge/gallery (`Android/src`, especially `ui/llmchat/LlmChatModelHelper.kt`).

### LiteRT-LM Kotlin API (from the source, package `com.google.ai.edge.litertlm`)
```kotlin
val engine = Engine(EngineConfig(
    modelPath = path,                 // absolute file path to a .litertlm file
    backend = Backend.GPU(),          // or Backend.CPU(); try GPU first and fall back to CPU on exception
    maxNumTokens = 4096,
    cacheDir = context.cacheDir.absolutePath,
))
engine.initialize()                   // slow (up to ~10 s): run on Dispatchers.IO
val conv = engine.createConversation(ConversationConfig(
    systemInstruction = Contents.of("..."),
    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
))
conv.sendMessageAsync(text).collect { msg -> append(msg.toString()) }   // Flow<Message> streaming
// or sendMessageAsync(text, object : MessageCallback { onMessage(m: Message); onDone(); onError(t: Throwable) })
conv.cancelProcess()   // stop generating
conv.close(); engine.close()
```
`Message.toString()` returns the text chunk. A fresh `Conversation` per question keeps the context small; keep only a short history.

### Models (user downloads them once and picks the file in the app)
Hugging Face "litert-community" `.litertlm` files (Gemma needs license acceptance on HF, so the app can't auto-download them easily):
- Gemma 4 E2B (~2.6 GB, featured, best quality): https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Gemma 3 1B (~0.5 GB, for weaker phones): https://huggingface.co/litert-community/Gemma3-1B-IT
- Qwen2.5-1.5B / Qwen3-0.6B are alternatives.
The app copies the picked model into `filesDir`/`getExternalFilesDir` (LiteRT-LM needs a real file path, not a content URI). Show copy progress, since the file is GBs.

## Planned architecture (not written yet; the repo is empty apart from this file)
Kotlin + Jetpack Compose, single `:app` module. Screens: **Library** (import or remove books, indexing progress), **Chat** (ask questions; answers stream in and cite book + page/chapter), **Model setup** (pick the .litertlm file, GPU/CPU toggle).

1. **Text extraction**
   - PDF: PdfBox-Android `com.tom-roush:pdfbox-android` (call `PDFBoxResourceLoader.init(context)`), extracting per page so citations can give page numbers.
   - EPUB: it's a zip, so read `META-INF/container.xml` → OPF → spine order → XHTML; strip the HTML with jsoup (`org.jsoup:jsoup`). Cite by chapter.
   - MOBI: no good Android library exists. Write a small parser: PalmDB header → MOBI header → PalmDOC LZ77 decompression (compression type 2) of the text records → HTML → jsoup. Support uncompressed (1) and PalmDOC (2); report HUFF/CDIC (17480) and DRM-protected files (Kindle store books) as unsupported with a clear message. KF8/AZW3 text records use the same PalmDOC compression.
2. **Chunking:** ~250–350-word chunks with ~50-word overlap, storing bookId, page or chapter label, and text.
3. **Retrieval: SQLite FTS4 full-text index (BM25-style ranking)**, with no embedding model. Use Room `@Fts4` or raw SQLite with `matchinfo('pcnalx')` and compute BM25 in Kotlin. This is chosen because it needs no second model, scales to 30+ large books, and is fast. Query flow: LLM (or a simple stopword filter) turns the question into keywords → FTS `MATCH` with OR terms → top ~6 chunks → prompt. A later option is LiteRT-LM's `EmbeddingEngine` (it exists in the API) for hybrid semantic search.
4. **Indexing** runs in a WorkManager / foreground job with progress, since big PDFs take minutes. Make it survive app close.
5. **Prompt:** system says answer only from the provided excerpts, say when the answer isn't in them, and cite `[Book title, p. N]`. Keep excerpts + question within ~3,000 tokens for a 4,096-token context.
6. **Chat UI:** streaming text, a stop button, source chips below each answer showing which book and page it came from.

## CI
`.github/workflows/build.yml`: `ubuntu-latest`, `actions/setup-java` (temurin 17 or 21), `gradle/actions/setup-gradle`, `./gradlew assembleDebug`, upload `app/build/outputs/apk/debug/*.apk` with `actions/upload-artifact`. Commit the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` + `.properties`). The jar can't be downloaded in the sandbox; copy it from https://github.com/google-ai-edge/gallery (GitHub is reachable) or have the workflow run `gradle wrapper` first.

## Things to tell the user
- Needs a fairly recent phone with 6–8 GB+ RAM for Gemma 4 E2B; Gemma 3 1B works on weaker phones but gives weaker answers.
- Kindle-store MOBI/AZW files are DRM-protected and can't be read. DRM-free MOBI files work.
- Scanned-image PDFs have no text layer and would need OCR (out of scope for v1).
