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

    // A channel to safely catch the tokens from MediaPipe and pass them to our UI
    // Change this line near the top of LlmEngine.kt:
    private val tokenChannel = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)

    // 1. Initialize the model
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // THE FIX: Changed from filesDir to getExternalFilesDir so Windows can see it!
        val modelFile = File(context.getExternalFilesDir(null), "gemma-1.1-2b-it-cpu-int4.bin")
        Log.d("LLM_TEST", "Reality Check -> File exists: ${modelFile.exists()}")
        Log.d("LLM_TEST", "Reality Check -> File size: ${modelFile.length() / (1024 * 1024)} MB")

        try {
            Log.d("LLM_TEST", "Starting initialization. Watch the RAM!")

            val options = LlmInference.LlmInferenceOptions.builder()
                // THE FIX: Use the external file path we just created above
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setResultListener { partialResult, done ->
                    tokenChannel.trySend(Pair(partialResult ?: "", done))
                }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.d("LLM_TEST", "SUCCESS: Model loaded into memory without crashing.")
        } catch (e: Exception) {
            Log.e("LLM_TEST", "CRASH: Failed to load model: ${e.message}")
        }
    }

    // 2. Generate the response
    fun generateResponseStream(prompt: String): Flow<String> = flow {
        try {
            // Trigger the model
            llmInference?.generateResponseAsync(prompt)

            // Listen to our channel and emit tokens to the UI until it says "done"
            for ((token, done) in tokenChannel) {
                emit(token)
                if (done) break
            }
        } catch (e: Exception) {
            Log.e("LLM_TEST", "Inference failed: ${e.message}")
        }
    }
}