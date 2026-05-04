# Replay Buffer Android: Full AI Context

## Purpose Of This Document

This file is a single-document context handoff for an AI model. It explains what the app is, how it is intended to feel, how the code is structured, what the important invariants are, and what "backend" means in this project.

If an AI reads only one file before making changes, it should read this one.

## One-Sentence Summary

Replay Buffer Android is a single-screen Android camera app that continuously records short rolling video segments into local cache, keeps only the newest few minutes, and exports the most recent user-selected duration into the device gallery when asked.

## Product Summary

The app behaves like a lightweight replay buffer:

- It does not record permanently all the time.
- It records only after the user taps the main record button.
- While recording is enabled, it keeps a rolling buffer of recent footage.
- The user can choose a replay length from 10 seconds to 5 minutes.
- When the user taps save, the app exports the newest buffered footage matching that duration into `Movies/ReplayBuffer`.

This is not meant to be a full camera replacement, video editor, social app, or media library. It is a focused capture utility for "save the last N seconds/minutes" behavior.

## Design Ideology

### Core design goals

1. Immediate camera-first experience  
   The camera preview is the entire app. The UI is layered on top of it rather than navigating between screens.

2. Minimal friction  
   The user should be able to open the app, grant permissions, tap record, and later tap save. Most controls are optional.

3. Rolling capture instead of timeline editing  
   The app is optimized around a recent-history buffer, not around browsing a long media timeline.

4. Simple, tactile camera-style controls  
   Zoom, exposure, torch, and shutter presets are treated like quick capture controls, not like settings pages.

5. Local-first behavior  
   The app has no cloud sync, no account system, no remote API, and no server dependency. All capture, buffering, merging, and saving happen on-device.

### UI philosophy

- Single-screen immersive UI
- Overlay controls that fade back to prioritize the camera view
- Small number of high-value actions
- Fast access to replay duration and save
- No deep menus, no multi-screen flow, no setup wizard

### Architectural philosophy

- Thin Activity
- State lives in a ViewModel
- Camera and export behavior lives in a controller/service-like class
- Platform APIs are used directly instead of building an unnecessary abstraction stack
- The app favors understandable, compact code over framework-heavy indirection

## Important Clarification About "Backend"

This project does **not** have a remote backend.

There is:

- no web server
- no REST API
- no database server
- no authentication system
- no analytics pipeline
- no cloud storage

In this app, the closest thing to a "backend" is the **local runtime control layer**, mainly `ReplayBufferController.kt`, which coordinates:

- CameraX preview and recording
- the rolling segment buffer
- export/merge operations with Media3 Transformer
- saving into Android shared storage / MediaStore

So if someone asks about the backend, the correct answer is:

> This is a local-only app. Its backend is effectively the on-device recording/export controller plus Android system APIs.

## Repository Shape

This is a very small single-module Android project.

### Root-level files

- `settings.gradle.kts`: declares a single module, `:app`
- `build.gradle.kts`: plugin versions
- `gradle.properties`: Android/Gradle flags
- `README.md`: short project summary and testing notes

### App module

- `app/build.gradle.kts`: Android configuration and dependencies
- `app/src/main/AndroidManifest.xml`: permissions, activity declaration, hardware requirement
- `app/src/main/java/com/pkrreaperr/replaybufferandroid/MainActivity.kt`: the full Compose UI
- `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferViewModel.kt`: UI state and bridge logic
- `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferController.kt`: camera, rolling buffer, export, and storage logic

There are no additional feature modules, no data layer module, and no test-heavy architecture around this app yet.

## Technology Stack

- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Lifecycle/state: Android ViewModel + `StateFlow`
- Camera: CameraX (`camera-camera2`, `camera-lifecycle`, `camera-video`, `camera-view`)
- Video export/merge: AndroidX Media3 Transformer
- Async/concurrency: Kotlin coroutines
- Storage: MediaStore on Android 10+, legacy public Movies directory on Android 9

### Android targets

- `minSdk = 28`
- `targetSdk = 35`
- `compileSdk = 35`

This matters because storage behavior is split between pre-scoped-storage and scoped-storage Android versions.

## Runtime Architecture

The app follows a simple three-layer flow:

1. `MainActivity.kt`
   Owns the screen and Compose UI.

2. `ReplayBufferViewModel.kt`
   Owns observable UI state and translates user actions into controller calls.

3. `ReplayBufferController.kt`
   Owns camera lifecycle binding, rolling buffer logic, export logic, and storage writes.

### Layer responsibilities

#### 1. Activity / Compose layer

`MainActivity` sets the content and renders one main composable, `ReplayBufferScreen`.

The UI layer is responsible for:

- requesting runtime permissions
- displaying live state from the ViewModel
- rendering the preview
- rendering the top, floating, and bottom overlays
- showing toasts from `toastMessage`
- forwarding user actions such as record/save/adjustments to the ViewModel

The UI is intentionally "dumb" about the recording pipeline. It should not directly manage video segments or storage behavior.

#### 2. ViewModel layer

`ReplayBufferViewModel` is the state owner for the screen.

It is responsible for:

- storing `ReplayBufferUiState`
- exposing state as `StateFlow`
- receiving callbacks from the controller
- updating derived values for the UI
- forwarding commands like `toggleRecording()`, `saveReplay()`, `setZoomRatio()`, `toggleTorch()`, etc.

It is a coordinator, not a business-logic-heavy layer.

#### 3. Controller layer

`ReplayBufferController` is the app's operational core.

It is responsible for:

- attaching CameraX preview/capture to lifecycle
- creating rolling 5-second recording segments
- tracking only the latest buffered footage
- trimming older cached segments
- selecting the newest segments that satisfy the requested replay duration
- merging those segments into a final replay file
- saving the merged file into the gallery
- reporting status/progress back upward through callbacks

This file is the most important "backend-like" class in the codebase.

## Core Data Model

### `ReplayBufferUiState`

The UI state includes:

- permission status
- selected replay duration
- status text
- recording/saving booleans
- toast message
- buffered duration info
- zoom range and current zoom
- exposure range and current exposure compensation
- torch state
- selected shutter preset

It also exposes derived presentation values such as:

- formatted replay length
- whether any clip is buffered
- the current buffer progress toward the selected replay duration
- a label such as `"20s / 30s"`

### `RecordedSegment`

Inside the controller, each recorded segment is represented by:

- `file: File`
- `durationMs: Long`

The buffered replay is therefore modeled as an ordered in-memory list of temporary local files.

## End-To-End App Flow

### 1. App launch

- `MainActivity` launches.
- Compose content is created.
- `ReplayBufferScreen` requests runtime permissions.

Permissions requested:

- `CAMERA`
- `RECORD_AUDIO`
- `WRITE_EXTERNAL_STORAGE` only on API 28 and below

### 2. Camera setup

Once permissions are granted:

- the UI creates a `PreviewView` through `AndroidView`
- `ReplayBufferViewModel.attachPreview(...)` is called
- the controller binds `Preview` and `VideoCapture` to the lifecycle using CameraX
- the controller reports zoom and exposure ranges upward
- the ViewModel updates UI state to "Camera ready"

### 3. Recording into the replay buffer

When the user taps the central record button:

- the ViewModel calls `controller.startBuffering()`
- the controller starts recording a 5-second segment to app cache
- when a segment finalizes successfully, it is added to the in-memory list
- older segments are removed once the total buffered duration exceeds 5 minutes
- if recording stays active, the next segment starts automatically

This creates a rolling local buffer.

### 4. Saving a replay

When the user taps save:

- the ViewModel passes the current replay duration in seconds
- the controller converts that duration to milliseconds
- if a segment is still being recorded, it is closed first
- the controller selects the newest segments that cover the requested duration
- the selected segment files are merged using Media3 Transformer
- the merged output is written to the gallery
- the temporary merged file is deleted afterward

### 5. Storage behavior

On Android 10+:

- the file is inserted into `MediaStore`
- the destination is `Movies/ReplayBuffer`
- `IS_PENDING` is used during write

On Android 9:

- the file is copied into the public Movies directory
- specifically `Movies/ReplayBuffer`
- `MediaScannerConnection` is used so the gallery can discover it

## Recording Model

The app does **not** keep one long continuously growing video file.

Instead it:

- records many short files, each nominally 5 seconds long
- stores them in app cache under `replay_segments`
- merges the selected recent files when saving

### Why this design was chosen

This design is simpler for a replay-buffer product because it makes it easy to:

- cap total buffered duration
- delete old footage
- save the most recent N seconds/minutes
- avoid building a custom circular container format

### Tradeoff

Replay selection is coarse to the segment boundary. Since the app saves whole recent segments, the exact exported replay length can overshoot slightly rather than trimming to frame-perfect precision.

## UI Structure

The screen is composed of three overlay zones above the full-screen preview.

### Top overlay

Contains:

- replay settings button
- show/hide controls chevron
- light/torch button

### Floating panel

This appears when a submenu is open.

Panels:

- `Replay`: replay duration, buffer meter, presets, save action
- `Adjust`: zoom, exposure, shutter preset chips
- `Light`: torch state and toggle

### Bottom overlay

Contains:

- app title and status text
- compact or expanded buffer progress display
- zoom preset pills
- save button
- main record button
- adjust button

### Current UI behavior detail

Recent UI cleanup changed two things:

- the old center "current 5-second segment" progress display is no longer shown
- when a submenu is open, the main bottom replay-duration progress area collapses into a smaller label pill instead of staying large

## Camera Controls

The app currently exposes these camera adjustments:

- zoom ratio
- exposure compensation
- torch on/off
- shutter presets

### Shutter presets

The presets are:

- `AUTO`
- `ACTION`
- `CINEMATIC`
- `NIGHT`

These map to exposure times in nanoseconds except `AUTO`, which clears manual capture request overrides.

This is implemented through `Camera2CameraControl` and `CaptureRequestOptions`.

### Important caveat

These shutter settings are best-effort. Device camera drivers may ignore or partially honor them.

## Concurrency Model

Coroutines are used in a fairly direct way.

- The controller owns its own `CoroutineScope`
- Main-thread work is used for camera and Transformer start orchestration where needed
- IO dispatching is used for export and cleanup work
- A repeating coroutine updates buffer metrics during the active segment
- A delayed coroutine stops each segment after roughly 5 seconds

The code is compact and understandable, but it is not yet heavily abstracted or instrumented.

## Error Handling Model

Errors are handled in a user-visible but lightweight way:

- controller failures emit a status message
- most failures also emit a toast
- failed segments are deleted
- failed MediaStore writes delete the partially created URI

There is no crash reporting, structured telemetry, retry policy, or analytics in the current codebase.

## State And Event Direction

The intended flow is:

1. user acts in Compose UI
2. UI calls ViewModel method
3. ViewModel calls controller method
4. controller performs work
5. controller sends callback data upward
6. ViewModel updates `ReplayBufferUiState`
7. Compose re-renders

This direction should generally be preserved. Avoid putting recording logic directly in composables.

## Current Limitations

These are important for any AI modifying the project:

### Product limitations

- single-screen only
- no front camera switching
- no clip browser/history
- no trimming UI
- no background recording service
- no cloud backup or sharing flow

### Technical limitations

- replay export is based on whole segments rather than fine-grained trimming
- current code has no automated tests
- permissions UX is minimal
- there is no dependency injection framework
- there is no repository/data-source abstraction
- there is no persistence of user settings between launches

### Code cleanliness note

`ReplayBufferUiState` still contains `currentSegmentProgress`, `currentSegmentElapsedMs`, and `currentSegmentLabel`-related logic even though the dedicated center display for segment progress has been removed from the current UI. That data is still updated by the controller and ViewModel, but it is now effectively latent state. An AI should be aware of this before either deleting it or reusing it.

## Important Invariants To Preserve

If an AI changes this project, these behaviors should usually remain true unless intentionally redesigning the app:

1. The app remains local-first and offline-capable.
2. The camera preview stays the primary surface.
3. Recording is opt-in; the app should not start buffering silently without user action.
4. The rolling buffer keeps only recent footage, not permanent endless recording.
5. Saving exports recent footage into the gallery under `Movies/ReplayBuffer`.
6. Android 9 and Android 10+ storage behavior both remain supported unless `minSdk` changes.
7. UI state should continue flowing through the ViewModel instead of moving logic into the composables.
8. The controller should remain the owner of camera/export operations.

## Extension Points For Future Work

An AI model extending this app will likely work in one of these areas:

### UI/UX

- add front/back camera switching
- add gesture controls
- refine overlay animations
- add settings persistence
- add a stronger save/export confirmation flow

### Recording pipeline

- configurable segment length
- finer replay trimming
- alternate export qualities
- pause/resume semantics

### Architecture

- separate camera code from export/storage code
- introduce dependency injection
- add tests around state transitions and segment selection
- add structured logging

### Storage/media

- custom export naming
- share sheet integration
- export progress UI
- optional app-private saved clip index

## How An AI Should Reason About This Codebase

### If asked about the frontend

The frontend is almost entirely `MainActivity.kt`, written in Compose, with a camera preview behind overlay controls.

### If asked about the backend

Say there is no remote backend. The operational core is `ReplayBufferController.kt`.

### If asked where app state lives

Say `ReplayBufferViewModel.kt` owns `ReplayBufferUiState` and is the reactive bridge between UI and controller.

### If asked where saving logic lives

Say saving is handled in `ReplayBufferController.kt`, including segment selection, Media3 merge/export, and MediaStore/gallery persistence.

### If asked where the replay buffer itself lives

Say it is implemented as cached temporary segment files plus an in-memory ordered list of `RecordedSegment` entries inside the controller.

## Suggested Reading Order For Another AI

If another AI has time to read more than this document, the best order is:

1. `docs/AI_CONTEXT.md`
2. `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferController.kt`
3. `app/src/main/java/com/pkrreaperr/replaybufferandroid/ReplayBufferViewModel.kt`
4. `app/src/main/java/com/pkrreaperr/replaybufferandroid/MainActivity.kt`
5. `app/src/main/AndroidManifest.xml`
6. `app/build.gradle.kts`

## Bottom Line

This project is a compact, local-only Android replay-buffer camera app with:

- one screen
- one ViewModel
- one controller that acts as the operational backend
- CameraX for capture
- Media3 Transformer for merge/export
- MediaStore/public Movies saving for gallery output

Its most important identity is not "camera app" in the broad sense, but "save the last N seconds/minutes" in the simplest possible, fast, touch-friendly form.
