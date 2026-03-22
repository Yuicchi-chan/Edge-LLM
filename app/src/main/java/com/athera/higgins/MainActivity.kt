package com.athera.higgins

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.athera.higgins.ai.HigginsAi
import com.athera.higgins.ai.InferenceEngine
import com.athera.higgins.ai.isModelLoaded
import com.athera.higgins.ai.gguf.GgufMetadata
import com.athera.higgins.ai.gguf.GgufMetadataReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var ggufTv: TextView
    private lateinit var emptyStateContainer: View
    private lateinit var emptyHi: TextView
    private lateinit var emptyName: TextView
    private lateinit var emptyPrompt: TextView
    private lateinit var metadataPanel: View
    private lateinit var metadataPanelToggle: SwitchMaterial
    private lateinit var toolCallingToggle: SwitchMaterial
    private lateinit var messagesRv: RecyclerView
    private lateinit var cachedModelsRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var systemPromptInput: EditText
    private lateinit var userActionFab: ImageButton
    private lateinit var reasoningIconButton: ImageButton
    private lateinit var addModelButton: MaterialButton
    private lateinit var saveSystemPromptButton: MaterialButton

    // Higgins AI inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private var modelLoadingJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private var isModelLoading = false
    private var pendingModelUri: Uri? = null
    private var hasPlayedEmptyIntro = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)
    private val cachedModels = mutableListOf<CachedModel>()
    private val cacheAdapter = ModelCacheAdapter(cachedModels) { model ->
        onCachedModelSelected(model)
    }
    private var reasoningEnabled = true
    private var toolCallingEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                Log.w(TAG, "Ignore back press for simplicity")
            }
        }

        // Find views
        drawerLayout = findViewById(R.id.main)
        val topAppBar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        ggufTv = findViewById(R.id.gguf)
        emptyStateContainer = findViewById(R.id.empty_state_container)
        emptyHi = findViewById(R.id.empty_hi)
        emptyName = findViewById(R.id.empty_name)
        emptyPrompt = findViewById(R.id.empty_prompt)
        metadataPanel = findViewById(R.id.metadata_panel)
        metadataPanelToggle = findViewById(R.id.metadata_panel_toggle)
        toolCallingToggle = findViewById(R.id.tool_calling_toggle)
        messagesRv = findViewById(R.id.messages)
        cachedModelsRv = findViewById(R.id.cached_models)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        cachedModelsRv.layoutManager = LinearLayoutManager(this)
        cachedModelsRv.adapter = cacheAdapter
        userInputEt = findViewById(R.id.user_input)
        systemPromptInput = findViewById(R.id.system_prompt_input)
        userActionFab = findViewById(R.id.fab)
        reasoningIconButton = findViewById(R.id.reasoning_icon_button)
        addModelButton = findViewById(R.id.add_model_button)
        saveSystemPromptButton = findViewById(R.id.save_system_prompt_button)

        topAppBar.inflateMenu(R.menu.main_actions)
        topAppBar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_chat -> {
                    startNewConversation()
                    true
                }

                else -> false
            }
        }

        val showMetadataPanel = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getBoolean(KEY_SHOW_METADATA_PANEL, false)
        metadataPanelToggle.isChecked = showMetadataPanel
        metadataPanel.visibility = if (showMetadataPanel) View.VISIBLE else View.GONE
        metadataPanelToggle.setOnCheckedChangeListener { _, isChecked ->
            metadataPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHOW_METADATA_PANEL, isChecked)
                .apply()
        }

        addModelButton.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            getContent.launch(arrayOf("*/*"))
        }

        reasoningEnabled = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getBoolean(KEY_REASONING_ENABLED, true)
        toolCallingEnabled = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getBoolean(KEY_TOOL_CALLING_ENABLED, true)
        toolCallingToggle.isChecked = toolCallingEnabled
        toolCallingToggle.setOnCheckedChangeListener { _, isChecked ->
            toolCallingEnabled = isChecked
            getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TOOL_CALLING_ENABLED, toolCallingEnabled)
                .apply()
        }

        systemPromptInput.setText(loadCustomSystemPrompt())
        saveSystemPromptButton.setOnClickListener {
            saveCustomSystemPrompt(systemPromptInput.text?.toString().orEmpty())
            Toast.makeText(this, "System prompt saved.", Toast.LENGTH_SHORT).show()
        }

        updateReasoningIcon()
        reasoningIconButton.setOnClickListener {
            reasoningEnabled = !reasoningEnabled
            getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_REASONING_ENABLED, reasoningEnabled)
                .apply()
            updateReasoningIcon()
        }

        loadCachedModels()
        loadConversationMessages()
        updateEmptyState(animated = true)

        // Higgins AI initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = HigginsAi.getInferenceEngine(applicationContext)
            withContext(Dispatchers.Main) {
                tryAutoLoadLastModel()
            }
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelLoading) {
                Toast.makeText(this, "Model is still loading...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Best effort only; some providers do not allow persistable grants.
            }
            handleSelectedModel(it)
        }
    }

    private fun onCachedModelSelected(model: CachedModel) {
        drawerLayout.closeDrawer(GravityCompat.START)
        saveLastModelUri(model.uri)
        handleSelectedModel(Uri.parse(model.uri))
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        if (isModelLoading) {
            pendingModelUri = uri
            Toast.makeText(this, "Queued selected model. It will load next.", Toast.LENGTH_SHORT).show()
            return
        }

        generationJob?.cancel()

        isModelLoading = true
        isModelReady = false

        // Update UI states
        userActionFab.isEnabled = false
        addModelButton.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        userInputEt.isEnabled = false
        ggufTv.text = "Parsing metadata from selected file \n$uri"

        modelLoadingJob?.cancel()
        modelLoadingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Parse GGUF metadata
                Log.i(TAG, "Parsing GGUF metadata...")
                val metadata = contentResolver.openInputStream(uri)?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                } ?: throw IllegalArgumentException("Unable to read model metadata.")

                addOrUpdateCachedModel( metadata.filename(), uri.toString())

                // Update UI to show GGUF metadata to user
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    ggufTv.text = metadata.toString()
                }

                // Ensure the model file is available
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                val modelFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                } ?: throw IllegalArgumentException("Unable to open selected model file.")

                loadModel(modelName, modelFile)

                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.ic_send_fab)
                    userActionFab.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load selected model", e)
                withContext(Dispatchers.Main) {
                    isModelReady = false
                    userInputEt.hint = "Pick a model from sidebar first"
                    userInputEt.isEnabled = false
                    userActionFab.setImageResource(R.drawable.ic_folder_fab)
                    userActionFab.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: "Failed to load model",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isModelLoading = false
                    addModelButton.isEnabled = true

                    val nextUri = pendingModelUri
                    pendingModelUri = null
                    if (nextUri != null) {
                        handleSelectedModel(nextUri)
                    }
                }
            }
        }
    }

    private fun loadCachedModels() {
        cachedModels.clear()
        val rawJson = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getString(KEY_MODELS_JSON, "[]")
            ?: "[]"

        val parsed = runCatching { JSONArray(rawJson) }.getOrNull() ?: JSONArray()
        for (i in 0 until parsed.length()) {
            val item = parsed.optJSONObject(i) ?: continue
            val name = item.optString(KEY_NAME)
            val uri = item.optString(KEY_URI)
            if (name.isNotBlank() && uri.isNotBlank()) {
                cachedModels.add(CachedModel(name = name, uri = uri))
            }
        }
        cacheAdapter.notifyDataSetChanged()
    }

    private fun saveCachedModels() {
        val array = JSONArray()
        cachedModels.forEach { model ->
            val obj = JSONObject()
                .put(KEY_NAME, model.name)
                .put(KEY_URI, model.uri)
            array.put(obj)
        }

        getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .edit()
            .putString(KEY_MODELS_JSON, array.toString())
            .apply()
    }

    private fun addOrUpdateCachedModel(name: String, uri: String) {
        cachedModels.removeAll { it.uri == uri }
        cachedModels.add(0, CachedModel(name = name, uri = uri))
        while (cachedModels.size > MAX_CACHED_MODELS) {
            cachedModels.removeAt(cachedModels.lastIndex)
        }
        saveCachedModels()
        saveLastModelUri(uri)

        runOnUiThread {
            cacheAdapter.notifyDataSetChanged()
        }
    }

    private fun saveLastModelUri(uri: String) {
        getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MODEL_URI, uri)
            .apply()
    }

    private fun startNewConversation() {
        if (isModelLoading) {
            Toast.makeText(this, "Model is loading. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        generationJob?.cancel()
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        updateEmptyState(animated = true)
        saveConversationMessages()

        val lastUri = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getString(KEY_LAST_MODEL_URI, null)

        if (isModelReady && !lastUri.isNullOrBlank()) {
            handleSelectedModel(Uri.parse(lastUri))
        } else {
            Toast.makeText(this, "Started a new conversation.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryAutoLoadLastModel() {
        val lastUri = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getString(KEY_LAST_MODEL_URI, null)
            ?: return

        val existing = cachedModels.firstOrNull { it.uri == lastUri }
        if (existing != null) {
            handleSelectedModel(Uri.parse(existing.uri))
        }
    }

    private fun updateReasoningIcon() {
        val enabledTint = 0xFFFFFFFF.toInt()
        val disabledTint = 0xFF8F8F8F.toInt()
        reasoningIconButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (reasoningEnabled) enabledTint else disabledTint
        )
        reasoningIconButton.alpha = if (reasoningEnabled) 1f else 0.75f
    }

    private fun updateEmptyState(animated: Boolean) {
        if (messages.isNotEmpty()) {
            if (emptyStateContainer.visibility == View.VISIBLE) {
                emptyStateContainer.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction {
                        emptyStateContainer.visibility = View.GONE
                    }
                    .start()
            }
            return
        }

        emptyStateContainer.visibility = View.VISIBLE
        if (!animated || hasPlayedEmptyIntro) {
            emptyStateContainer.alpha = 1f
            emptyHi.alpha = 1f
            emptyName.alpha = 1f
            emptyPrompt.alpha = 1f
            return
        }

        hasPlayedEmptyIntro = true
        emptyStateContainer.alpha = 1f

        emptyHi.alpha = 0f
        emptyName.alpha = 0f
        emptyPrompt.alpha = 0f

        emptyHi.translationY = 10f
        emptyName.translationY = 16f
        emptyPrompt.translationY = 10f

        emptyHi.animate().alpha(1f).translationY(0f).setDuration(600).start()
        emptyName.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(800).start()
        emptyPrompt.animate().alpha(1f).translationY(0f).setStartDelay(560).setDuration(650).start()
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
            }

            val switchingFromLoadedModel = engine.state.value.isModelLoaded ||
                engine.state.value is InferenceEngine.State.Error

            if (switchingFromLoadedModel) {
                withContext(Dispatchers.Main) {
                    userInputEt.hint = "Switching model..."
                }
                unloadCurrentModelIfNeeded()
            }

            engine.loadModel(modelFile.path)

            val systemPrompt = buildSystemPrompt()
            if (systemPrompt.isNotBlank()) {
                engine.setSystemPrompt(systemPrompt)
            }
        }

    private suspend fun unloadCurrentModelIfNeeded() = withContext(Dispatchers.IO) {
        repeat(40) {
            when (val state = engine.state.value) {
                is InferenceEngine.State.Initialized,
                is InferenceEngine.State.Uninitialized,
                is InferenceEngine.State.Initializing -> return@withContext

                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error -> {
                    engine.cleanUp()
                    return@withContext
                }

                else -> {
                    // Wait for ongoing processing/generation to settle before unloading.
                    delay(50)
                }
            }
        }

        when (engine.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Error -> engine.cleanUp()
            else -> throw IllegalStateException("Engine busy while switching model: ${engine.state.value}")
        }
    }

    private fun buildSystemPrompt(): String {
        val history = messages
            .filter { it.content.isNotBlank() }
            .takeLast(MAX_CONTEXT_CARRY_MESSAGES)
        val customSystemPrompt = loadCustomSystemPrompt()

        val boundedHistory = mutableListOf<Message>()
        var usedChars = 0
        for (msg in history.asReversed()) {
            val line = "${if (msg.isUser) "User" else "Assistant"}: ${msg.content.trim()}"
            if (line.length + usedChars > MAX_CONTEXT_CARRY_CHARS && boundedHistory.isNotEmpty()) {
                break
            }
            boundedHistory.add(0, msg)
            usedChars += line.length
        }

        val hasHistory = boundedHistory.isNotEmpty()
        val shouldIncludeTools = toolCallingEnabled

        if (customSystemPrompt.isBlank() && !hasHistory && !shouldIncludeTools) return ""

        return buildString {
            if (customSystemPrompt.isNotBlank()) {
                appendLine(customSystemPrompt)
                appendLine()
            }

            if (shouldIncludeTools) {
                appendLine(TOOL_CALL_INSTRUCTIONS)
                appendLine()
            }

            if (hasHistory) {
                appendLine("Continue this conversation context with the user naturally.")
                appendLine("Conversation so far:")
                boundedHistory.forEach { msg ->
                    appendLine("${if (msg.isUser) "User" else "Assistant"}: ${msg.content.trim()}")
                }
            }
        }
    }

    private fun loadCustomSystemPrompt(): String {
        return getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getString(KEY_CUSTOM_SYSTEM_PROMPT, "")
            .orEmpty()
            .trim()
    }

    private fun saveCustomSystemPrompt(prompt: String) {
        getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_SYSTEM_PROMPT, prompt.trim())
            .apply()
    }

    private data class ToolRequest(val name: String, val arguments: JSONObject)

    private fun parseToolRequest(text: String): ToolRequest? {
        val trimmed = text.trim()
        val rawJson = when {
            trimmed.startsWith("```") -> {
                val match = Regex("""^```(?:json)?\\s*(\\{[\\s\\S]*})\\s*```$""")
                    .find(trimmed)
                match?.groupValues?.get(1)
            }
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            else -> null
        } ?: return null

        val parsed = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val name = parsed.optString("tool").trim()
        if (name.isBlank()) return null
        val args = parsed.optJSONObject("arguments") ?: JSONObject()
        return ToolRequest(name, args)
    }

    private suspend fun executeToolRequest(request: ToolRequest): String {
        return when (request.name) {
            "get_time" -> {
                ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            }

            "list_cached_models" -> {
                withContext(Dispatchers.Main) {
                    if (cachedModels.isEmpty()) {
                        "No cached models available."
                    } else {
                        cachedModels.joinToString(separator = "\n") { model ->
                            "- ${model.name} (${model.uri})"
                        }
                    }
                }
            }

            "get_app_state" -> {
                withContext(Dispatchers.Main) {
                    "isModelReady=$isModelReady, isModelLoading=$isModelLoading, reasoningEnabled=$reasoningEnabled, cachedModels=${cachedModels.size}, messages=${messages.size}"
                }
            }

            else -> "Unknown tool: ${request.name}. Available tools: get_time, list_cached_models, get_app_state"
        }
    }

    private fun buildToolResultPrompt(request: ToolRequest, result: String): String {
        return buildString {
            appendLine("TOOL_RESULT")
            appendLine("tool: ${request.name}")
            appendLine("arguments: ${request.arguments}")
            appendLine("result:")
            appendLine(result)
            appendLine()
            appendLine("Now respond to the user naturally using this result.")
        }
    }

    private suspend fun streamAssistantIntoLastMessage(prompt: String) {
        lastAssistantMsg.clear()
        engine.sendUserPrompt(prompt).collect { token ->
            withContext(Dispatchers.Main) {
                val messageCount = messages.size
                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                messages.removeAt(messageCount - 1).copy(
                    content = lastAssistantMsg.append(token).toString()
                ).let { messages.add(it) }

                messageAdapter.notifyItemChanged(messages.size - 1)
                messagesRv.scrollToPosition(messages.lastIndex)
            }
        }
    }

    private suspend fun maybeRunToolCalls() {
        if (!toolCallingEnabled) return

        repeat(MAX_TOOL_CALL_STEPS) {
            val currentAssistantMessage = withContext(Dispatchers.Main) {
                messages.lastOrNull()?.takeIf { !it.isUser }?.content.orEmpty()
            }
            val request = parseToolRequest(currentAssistantMessage) ?: return
            val result = executeToolRequest(request)

            withContext(Dispatchers.Main) {
                val idx = messages.lastIndex
                if (idx >= 0 && !messages[idx].isUser) {
                    messages[idx] = messages[idx].copy(content = "Tool executed: `${request.name}`")
                    messageAdapter.notifyItemChanged(idx)
                }

                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), "", false))
                messageAdapter.notifyItemInserted(messages.lastIndex)
                messagesRv.scrollToPosition(messages.lastIndex)
            }

            streamAssistantIntoLastMessage(buildToolResultPrompt(request, result))
        }
    }

    private fun loadConversationMessages() {
        val rawJson = getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .getString(KEY_CONVERSATION_JSON, "[]")
            ?: "[]"

        val parsed = runCatching { JSONArray(rawJson) }.getOrNull() ?: JSONArray()
        messages.clear()
        for (i in 0 until parsed.length()) {
            val item = parsed.optJSONObject(i) ?: continue
            val role = item.optString(KEY_ROLE)
            val content = item.optString(KEY_CONTENT).trim()
            if (content.isBlank()) continue
            messages.add(
                Message(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    isUser = role == ROLE_USER
                )
            )
        }
        messageAdapter.notifyDataSetChanged()
    }

    private fun saveConversationMessages() {
        val array = JSONArray()
        messages
            .filter { it.content.isNotBlank() }
            .takeLast(MAX_PERSISTED_MESSAGES)
            .forEach { message ->
                array.put(
                    JSONObject()
                        .put(KEY_ROLE, if (message.isUser) ROLE_USER else ROLE_ASSISTANT)
                        .put(KEY_CONTENT, message.content.trim())
                )
            }

        getSharedPreferences(PREF_MODELS, MODE_PRIVATE)
            .edit()
            .putString(KEY_CONVERSATION_JSON, array.toString())
            .apply()
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))
                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                messagesRv.scrollToPosition(messages.lastIndex)
                updateEmptyState(animated = false)
                saveConversationMessages()

                val enginePrompt = if (reasoningEnabled) {
                    userMsg
                } else {
                    "</no_think>$userMsg"
                }

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(enginePrompt)
                        .onCompletion { cause ->
                            val cancelled = cause is CancellationException
                            if (!cancelled) {
                                runCatching { maybeRunToolCalls() }
                                    .onFailure { toolErr ->
                                        Log.e(TAG, "Tool-calling pipeline failed", toolErr)
                                    }
                            }

                            withContext(Dispatchers.Main) {
                                if (!cancelled) {
                                    saveConversationMessages()
                                }
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                                messagesRv.scrollToPosition(messages.lastIndex)
                            }
                        }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        modelLoadingJob?.cancel()
        generationJob?.cancel()
        saveConversationMessages()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val PREF_MODELS = "higgins_model_cache"
        private const val KEY_MODELS_JSON = "models_json"
        private const val KEY_LAST_MODEL_URI = "last_model_uri"
        private const val KEY_REASONING_ENABLED = "reasoning_enabled"
        private const val KEY_TOOL_CALLING_ENABLED = "tool_calling_enabled"
        private const val KEY_SHOW_METADATA_PANEL = "show_metadata_panel"
        private const val KEY_CONVERSATION_JSON = "conversation_json"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_NAME = "name"
        private const val KEY_URI = "uri"
        private const val KEY_ROLE = "role"
        private const val KEY_CONTENT = "content"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val MAX_CACHED_MODELS = 12
        private const val MAX_CONTEXT_CARRY_MESSAGES = 16
        private const val MAX_CONTEXT_CARRY_CHARS = 6000
        private const val MAX_PERSISTED_MESSAGES = 24
        private const val MAX_TOOL_CALL_STEPS = 2

        private const val TOOL_CALL_INSTRUCTIONS = """
    You can call tools when needed.
    If a tool is needed, respond ONLY with a JSON object in this exact shape:
    {"tool":"<name>","arguments":{...}}

    Available tools:
    - get_time: returns current local device time (ISO format)
    - list_cached_models: returns cached model names and URIs
    - get_app_state: returns basic app runtime state

    When no tool is needed, respond normally.
    """.trimIndent()

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}

fun GgufMetadata.filename(): String = when {
    basic.name != null -> {
        val name = basic.name!!
        basic.sizeLabel?.let { size -> "$name-$size" } ?: name
    }
    architecture?.architecture != null -> {
        val arch = architecture?.architecture!!
        basic.uuid?.let { uuid -> "$arch-$uuid" } ?: "$arch-${System.currentTimeMillis()}"
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
