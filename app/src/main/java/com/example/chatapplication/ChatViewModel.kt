package com.example.chatapplication

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow("Tap 'Test Prompt' to start.")
    val uiState: StateFlow<String> = _uiState.asStateFlow()

    fun sendPrompt(prompt: String, llmEngine: LlmEngine) {
        viewModelScope.launch {
            _uiState.value = "Generating... (Thinking)"
            val stringBuilder = StringBuilder()
            var isGenerating = true

            // ⚠️ THE FIX: Format the prompt specifically for Gemma IT models
            val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"

            val uiUpdaterJob = launch {
                while (isGenerating) {
                    delay(100)
                    if (stringBuilder.isNotEmpty()) {
                        _uiState.value = stringBuilder.toString()
                    }
                }
                if (stringBuilder.isNotEmpty()) {
                    _uiState.value = stringBuilder.toString()
                }
            }

            try {
                // Send the formatted prompt instead of the raw one
                llmEngine.generateResponseStream(formattedPrompt).collect { token ->
                    stringBuilder.append(token)
                    // Let's also print to Logcat so you can prove it's working behind the scenes!
                    Log.d("LLM_TEST", "Token received: $token")
                }
            } catch (e: Exception) {
                _uiState.value = "Error: ${e.message}"
            } finally {
                isGenerating = false
                uiUpdaterJob.join()
            }
        }
    }
}