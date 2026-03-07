package com.example.chatapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class LlmEngine(private val context: Context) {

    // Native pointers to C++ memory
    private var enginePtr: Long = 0L

    // We use a shared reference to route the callbacks from the JNI listener to the active flow.
    private var currentTokenChannel: Channel<Pair<String, Boolean>>? = null

    // Configurable model path — set before calling initialize()
    var modelPath: String = File(context.getExternalFilesDir(null), "qwen2.5-1.5b-instruct-q3_k_m.gguf").absolutePath

    companion object {
        init {
            try {
                System.loadLibrary("llama_android")
                Log.d("LLM", "Native library llama_android loaded.")
            } catch (e: Exception) {
                Log.e("LLM", "Failed to load llama native library.", e)
            }
        }
    }

    // External JNI methods
    private external fun initNative(modelPath: String): Long
    private external fun generateNative(enginePtr: Long, prompt: String)
    private external fun releaseNative(enginePtr: Long)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val modelFile = File(modelPath)
        Log.d("LLM", "Loading model from: $modelPath")
        Log.d("LLM", "File exists: ${modelFile.exists()}, Size: ${modelFile.length() / (1024 * 1024)} MB")

        try {
            if (modelFile.exists()) {
                enginePtr = initNative(modelPath)
                if (enginePtr != 0L) {
                    Log.d("LLM", "SUCCESS: Model loaded via JNI. Ptr: $enginePtr")
                } else {
                    Log.e("LLM", "FAILED: JNI returned 0 pointer.")
                }
            } else {
                Log.e("LLM", "FAILED: Model file not found.")
            }
        } catch (e: Exception) {
            Log.e("LLM", "FAILED to load model natively: ${e.message}")
        }
    }

    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (enginePtr == 0L) {
            emit("[Error: Local model is not loaded. Please wait for download.]")
            return@flow
        }

        val tokenChannel = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
        currentTokenChannel = tokenChannel

        try {
            // Run the blocking JNI call on a background thread.
            // The thread closes the channel in its finally block, so the collection
            // loop below terminates cleanly whether the call succeeds or crashes.
            Thread {
                try {
                    generateNative(enginePtr, prompt)
                } catch (e: Exception) {
                    Log.e("LLM", "Inference native crashed: ${e.message}")
                } finally {
                    // Always close the channel when the native call finishes or crashes,
                    // so the for-loop below is guaranteed to exit.
                    tokenChannel.close()
                }
            }.start()

            // Collect tokens from the JNI callback loop.
            // ensureActive() throws CancellationException if the collecting coroutine
            // was cancelled, causing the finally block below to run and clean up.
            for ((token, done) in tokenChannel) {
                currentCoroutineContext().ensureActive()
                emit(token)
                if (done) break
            }
        } catch (e: Exception) {
            Log.e("LLM", "Inference failed: ${e.message}")
            emit("[Local inference error: ${e.message}]")
        } finally {
            tokenChannel.close()
            if (currentTokenChannel == tokenChannel) {
                currentTokenChannel = null
            }
        }
    }

    // This method is called by C++ JNI code when a new token is predicted
    @Suppress("unused")
    fun onTokenGenerated(tokenBytes: ByteArray, isComplete: Boolean) {
        val decodedToken = String(tokenBytes, Charsets.UTF_8)
        currentTokenChannel?.trySend(Pair(decodedToken, isComplete))
        if (isComplete) {
            currentTokenChannel?.close()
        }
    }

    fun isLoaded(): Boolean = enginePtr != 0L

    @Suppress("unused")
    fun release() {
        if (enginePtr != 0L) {
            releaseNative(enginePtr)
            enginePtr = 0L
        }
        currentTokenChannel?.close()
        currentTokenChannel = null
    }
}