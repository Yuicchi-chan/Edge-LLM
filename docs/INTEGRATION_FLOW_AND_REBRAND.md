# Llama Android Sample: Integration Flow and Rebrand Guide

This document explains:
- How this sample app is wired end-to-end
- How to copy the inference integration into an existing Android app
- How to rename package/app/library safely and build on top of this project

## 1) Architecture at a glance

- `:app` is a thin UI shell that picks a GGUF file, copies it to app private storage, and streams generated tokens into a chat list.
- `:lib` contains all reusable model logic:
  - Kotlin API surface (`HigginsAi`, `InferenceEngine`)
  - GGUF metadata parser
  - JNI bridge
  - C++ llama.cpp integration and token generation loop

### Call path

1. UI asks for singleton engine via `HigginsAi.getInferenceEngine(context)`.
2. Engine loads native library `ai-chat` and initializes llama backends.
3. User picks GGUF file.
4. App parses GGUF metadata for display.
5. App copies model to app-private `files/models` if needed.
6. App calls `engine.loadModel(path)`.
7. On user prompt, app calls `engine.sendUserPrompt(message)`.
8. Engine processes prompt natively and emits generated tokens as Kotlin `Flow<String>`.
9. UI appends each token to assistant message in RecyclerView.
10. On teardown, app calls `engine.destroy()`.

## 2) Source map (where each responsibility lives)

### App module (`:app`)

- Gradle wiring and dependency on `:lib`
  - `app/build.gradle.kts`
- Manifest + launcher activity
  - `app/src/main/AndroidManifest.xml`
- Chat UI and integration orchestration
  - `app/src/main/java/com/athera/higgins/MainActivity.kt`
  - `app/src/main/java/com/athera/higgins/MessageAdapter.kt`
- Layouts
  - `app/src/main/res/layout/activity_main.xml`
  - `app/src/main/res/layout/item_message_user.xml`
  - `app/src/main/res/layout/item_message_assistant.xml`

### Library module (`:lib`)

- Public library entrypoint
  - `lib/src/main/java/com/athera/higgins/ai/HigginsAi.kt`
- API contract and state model
  - `lib/src/main/java/com/athera/higgins/ai/InferenceEngine.kt`
- Engine implementation + JNI declarations
  - `lib/src/main/java/com/athera/higgins/ai/internal/InferenceEngineImpl.kt`
- GGUF metadata parser
  - `lib/src/main/java/com/athera/higgins/ai/gguf/GgufMetadataReader.kt`
  - `lib/src/main/java/com/athera/higgins/ai/internal/gguf/GgufMetadataReaderImpl.kt`
- Native build and implementation
  - `lib/src/main/cpp/CMakeLists.txt`
  - `lib/src/main/cpp/ai_chat.cpp`

## 3) Exact integration steps for your existing app

Use this when you want inference in your own app and keep your own UI.

### Step A: Bring in `:lib`

1. Copy the entire `lib` module into your project root.
2. In your root `settings.gradle.kts`, add `include(":lib")`.
3. In your app module `build.gradle.kts`, add dependency:
   - `implementation(project(":lib"))`
4. Keep Android/Kotlin plugin versions compatible with this library (AGP + Kotlin).

### Step B: Keep required native config

In `lib/build.gradle.kts` keep:
- `ndkVersion`
- `externalNativeBuild.cmake.path("src/main/cpp/CMakeLists.txt")`
- `defaultConfig.ndk.abiFilters`
- CMake arguments under `externalNativeBuild.cmake.arguments`

If your project root is different from this sample, update `LLAMA_SRC` in `lib/src/main/cpp/CMakeLists.txt`. It currently points relatively to the parent llama.cpp source tree.

### Step C: Add runtime flow in your UI layer

1. Initialize engine once (application/activity scope):
  - `engine = HigginsAi.getInferenceEngine(applicationContext)`
2. Let user pick a GGUF file (Storage Access Framework).
3. Parse metadata (optional but useful):
   - `GgufMetadataReader.create().readStructuredMetadata(inputStream)`
4. Copy selected model into internal storage.
5. Load model:
   - `engine.loadModel(modelFile.path)`
6. For each user message, collect stream:
   - `engine.sendUserPrompt(userText).collect { token -> ... }`
7. On destroy, always call:
   - `engine.destroy()`

### Step D: Threading and lifecycle rules

- Do model load and generation work off main thread (coroutines IO/Default).
- Keep one engine instance process-wide; avoid creating many instances.
- Cancel active generation job when leaving screen (`onStop` in sample).
- Keep UI disabled while model is loading/generating.

### Step E: Error handling you should add

Sample is intentionally minimal. Add in production:
- File size checks before copy
- Available storage check
- GGUF validation before expensive operations
- Friendly mapping for `UnsupportedArchitectureException`
- Timeout/cancel controls for long generations

## 4) Full app flow from current sample

### Startup

- Activity initializes views and adapter.
- Engine creation starts in background.
- FAB initially means "pick model".

### Model selection and load

- User picks file via `OpenDocument` contract.
- Sample reads GGUF metadata and displays it.
- Sample persists model under `files/models/<derived-name>.gguf`.
- Engine loads model and prepares context/sampler.
- FAB switches to send mode; input is enabled.

### Prompt/generation loop

- User message is appended immediately.
- Placeholder assistant message row is inserted.
- Flow tokens stream in and append to same assistant row.
- On completion/cancel/error, input and button are re-enabled.

### Teardown

- `onStop`: cancels generation coroutine.
- `onDestroy`: calls `engine.destroy()` which unloads native resources.

## 5) Rename package/app/library safely (important)

If you want to build on this sample and rebrand it, follow this order.

### 5.1 Rename Android app identity

1. App display name:
   - update `app/src/main/res/values/strings.xml` (`app_name`)
2. App package/application id:
   - update `applicationId` in `app/build.gradle.kts`
3. App namespace:
   - update `namespace` in `app/build.gradle.kts`
4. Move Kotlin package directories in app source to match new namespace.
5. Update `package` line in moved Kotlin files.

### 5.2 Rename library namespace/package

1. Update `namespace` in `lib/build.gradle.kts`.
2. Move Kotlin files from your previous package path to your new package path.
3. Update all package/import declarations.

### 5.3 Critical JNI rule (do not skip)

Current native functions are statically named with package path, for example:
- `Java_com_athera_higgins_ai_internal_InferenceEngineImpl_init`
- `Java_com_athera_higgins_ai_internal_InferenceEngineImpl_load`
- ... and all others in `ai_chat.cpp`

If you rename package/class of `InferenceEngineImpl`, you MUST also update JNI symbol names in `lib/src/main/cpp/ai_chat.cpp` to the new mangled Java path.

If you forget this, app will compile but fail at runtime with missing native method/link errors.

### 5.4 Native library name

- Kotlin loads `System.loadLibrary("ai-chat")`.
- CMake project currently builds shared lib from `project("ai-chat")`.

If you rename native lib, update both sides consistently:
- `lib/src/main/java/.../InferenceEngineImpl.kt` `System.loadLibrary(...)`
- `lib/src/main/cpp/CMakeLists.txt` project/target names as needed

### 5.5 Manifest/theme cleanup

- Update theme name references if you rename styles.
- Ensure launcher activity path still points to your moved class.

### 5.6 Proguard/minify caution

This sample has minify enabled for debug and release in app build types.
If you hit obfuscation issues with JNI/reflection, add keep rules before shipping.

## 6) Validation checklist after integration/rebrand

Run these checks in order:

1. Gradle sync succeeds.
2. `:lib:assembleDebug` succeeds.
3. `:app:assembleDebug` succeeds.
4. App launches.
5. GGUF file picker opens and returns file.
6. Metadata appears in UI.
7. Model copy completes to app private storage.
8. Model load succeeds.
9. Prompt sends and token streaming updates UI.
10. Rotate/close/reopen app; no native crash.

## 7) Fast path choices

### Option A: Keep your app, copy only integration

- Keep your existing UI.
- Import `:lib` only.
- Recreate the small controller flow from `MainActivity` in your architecture (ViewModel recommended).

### Option B: Fork this sample and customize

- Rename app id + packages + labels.
- Keep existing working integration and iterate UI/features.
- This is usually the fastest path to production prototype.

## 8) Suggested next execution plan

If you want, the next safe automated step is:

1. I perform full package/app rename with your target values.
2. I update JNI symbol names automatically.
3. I run Gradle tasks to verify no linkage breaks.

Provide three values and I will apply everything end-to-end:
- New app name (display label)
- New applicationId (e.g., `com.yourco.chat`)
- New base package for app + library
