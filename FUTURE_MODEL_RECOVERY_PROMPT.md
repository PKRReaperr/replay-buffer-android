# Future Model Recovery Prompt

Use this prompt in a future session if local chat history, device data, or project memory is gone.

---

You are helping me with the Android project in this repository.

Before making changes or answering architectural questions, rebuild project context from the repo itself.

Follow this exact startup process:

1. Read `docs/AI_CONTEXT.md` first if it exists.
2. Then read these files in order:
   - `README.md`
   - `app/src/main/AndroidManifest.xml`
   - `app/build.gradle.kts`
   - `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferController.kt`
   - `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferViewModel.kt`
   - `app/src/main/java/com/pkrreaperr/replaybufferandroid/MainActivity.kt`
3. Summarize your understanding of:
   - what the app does
   - its design ideology
   - the UI structure
   - the recording/export pipeline
   - storage and permission behavior
   - what counts as the "backend" in this project
   - current constraints, risks, and likely extension points
4. Treat `ReplayBufferController.kt` as the operational core of the app.
5. Treat this as a local-only Android app unless the repo clearly shows otherwise. Do not assume any remote backend, auth system, or cloud storage unless you find code proving it.
6. Preserve the current product identity unless I explicitly ask for a redesign:
   - camera-first
   - single-screen
   - local-first
   - rolling replay buffer
   - save recent footage to `Movies/ReplayBuffer`
7. Before editing, check for any newer code that may differ from the context document. Prefer the current source of truth in code if the document and code disagree.

Important context to keep in mind:

- This app is a replay-buffer camera app, not a general social/video platform.
- The "backend" is local runtime logic, mainly camera control, buffering, export, and gallery saving.
- The project currently centers around one main screen, one ViewModel, and one controller.
- The app uses CameraX for capture, Media3 Transformer for export/merge, and Android storage APIs for saving.
- Android 9 and Android 10+ storage behavior differ and both may matter depending on `minSdk`.
- UI should stay responsive and simple; avoid adding unnecessary architectural complexity unless requested.

If `docs/AI_CONTEXT.md` is missing:

- reconstruct the same understanding by reading the files above
- create or refresh a context summary document before major feature work if helpful

After reading everything, ask me what I want to change next, or proceed directly if I already asked for a concrete change.

---

Suggested one-line version:

"Read `docs/AI_CONTEXT.md` and then inspect the core Android files to fully reconstruct the app's architecture, design ideology, local backend behavior, and storage/export flow before doing any work."
