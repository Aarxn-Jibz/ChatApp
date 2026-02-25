package com.example.chatapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File

class LlmEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var llamaHelper: LlamaHelper? = null

    // Target the specific GGUF model downlaoded by the worker / user
    var modelPath: String = File(context.getExternalFilesDir(null), "gemma-3-4b-it-q4_0.gguf").absolutePath
    
    private var isModelLoaded = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val modelFile = File(modelPath)
        Log.d("LLM", "Loading model from: $modelPath")
        Log.d("LLM", "File exists: ${modelFile.exists()}, Size: ${modelFile.length() / (1024 * 1024)} MB")

        if (llamaHelper == null) {
            llamaHelper = LlamaHelper(
                contentResolver = context.contentResolver,
                scope = scope,
                sharedFlow = _llmFlow
            )
        }

        try {
            // Suspend the initialization coroutine until LlamaHelper calls the completion callback
            suspendCancellableCoroutine<Unit> { continuation ->
                // FIX: LlamaHelper strips file:// so we MUST provide a content:// URI
                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    modelFile
                )
                
                llamaHelper?.load(
                    path = fileUri.toString(),
                    contextLength = 4096,
                ) { ctxId ->
                    isModelLoaded = true
                    Log.d("LLM", "SUCCESS: Model loaded with ctxId: $ctxId")
                    if (continuation.isActive) continuation.resume(Unit) {}
                }
            }
        } catch(e: Exception) {
            Log.e("LLM", "FAILED to load model: ${e.message}")
        }
    }

    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (!isModelLoaded || llamaHelper == null) {
            emit("[Error: Local model is not loaded. Please wait for model download or load it first.]")
            return@flow
        }
        
        // We use a local channel to bridge the SharedFlow events back into this synchronous flow builder
        val tokenChannel = Channel<String>(Channel.UNLIMITED)
        
        val collectorJob = scope.launch {
            _llmFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> tokenChannel.trySend(event.word)
                    is LlamaHelper.LLMEvent.Error -> {
                        tokenChannel.trySend("[Error generating text. Verify model integrity.]")
                        llamaHelper?.stopPrediction()
                        tokenChannel.close()
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        llamaHelper?.stopPrediction()
                        tokenChannel.close()
                    }
                    else -> {}
                }
            }
        }

        try {
            llamaHelper?.predict(prompt)
            // Stream the tokens to the UI as they arrive from the C++ layer
            for (token in tokenChannel) {
                emit(token)
            }
        } catch (e: Exception) {
            Log.e("LLM", "Inference failed: ${e.message}")
            emit("[Local inference error: ${e.message}]")
        } finally {
            collectorJob.cancel()
            tokenChannel.close()
        }
    }

    fun isLoaded(): Boolean = isModelLoaded

    fun release() {
        llamaHelper?.abort()
        llamaHelper?.release()
        llamaHelper = null
        isModelLoaded = false
    }
}