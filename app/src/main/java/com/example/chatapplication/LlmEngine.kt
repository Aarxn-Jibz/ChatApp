package com.example.chatapplication

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class LlmEngine(private val context: Context) {
    private var llmInference: LlmInference? = null

    // We use a shared reference to route the callbacks from the listener to the active flow.
    private var currentTokenChannel: Channel<Pair<String, Boolean>>? = null

    // Configurable model path — set before calling initialize()
    var modelPath: String = File(context.getExternalFilesDir(null), "gemma-1.1-2b-it-cpu-int4.bin").absolutePath

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val modelFile = File(modelPath)
        Log.d("LLM", "Loading model from: $modelPath")
        Log.d("LLM", "File exists: ${modelFile.exists()}, Size: ${modelFile.length() / (1024 * 1024)} MB")

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512)
                // FIX: Attach the result listener here during configuration
                .setResultListener { partialResult, done ->
                    currentTokenChannel?.trySend(Pair(partialResult ?: "", done))
                    if (done) currentTokenChannel?.close()
                }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.d("LLM", "SUCCESS: Model loaded.")
        } catch (e: Exception) {
            Log.e("LLM", "FAILED to load model: ${e.message}")
        }
    }

    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (llmInference == null) {
            emit("[Error: Local model is not loaded. Please load a model first.]")
            return@flow
        }

        // Create a new channel for this specific interaction
        val tokenChannel = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)

        // Assign it to the class-level variable so the listener in initialize() can send to it
        currentTokenChannel = tokenChannel

        try {
            // FIX: generateResponseAsync takes ONLY the prompt.
            llmInference!!.generateResponseAsync(prompt)

            for ((token, done) in tokenChannel) {
                emit(token)
                if (done) break
            }
        } catch (e: Exception) {
            Log.e("LLM", "Inference failed: ${e.message}")
            emit("[Local inference error: ${e.message}]")
        } finally {
            // FIX: close() is safe to call repeatedly. Removes the "Delicate API" warning.
            tokenChannel.close()

            // Clean up the reference to prevent memory leaks or stray emissions
            if (currentTokenChannel == tokenChannel) {
                currentTokenChannel = null
            }
        }
    }

    fun isLoaded(): Boolean = llmInference != null

    @Suppress("unused") // Suppresses the unused warning until you implement it in UI
    fun release() {
        llmInference?.close()
        llmInference = null
        currentTokenChannel?.close()
        currentTokenChannel = null
    }
}