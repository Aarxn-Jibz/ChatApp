package com.example.chatapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow("Type a message below to start chatting!")
    val uiState: StateFlow<String> = _uiState.asStateFlow()

    // This list holds our conversation history
    private var conversationHistory = mutableListOf<String>()

    fun sendPrompt(prompt: String, routingManager: RoutingManager) {
        viewModelScope.launch(Dispatchers.Main) {
            // 1. Add the new user prompt
            conversationHistory.add("User: $prompt")

            // 🛑 CRITICAL DEMO FIX: Shrink the memory window!
            // Budget phones will choke on large context windows.
            // We only keep the last 3 messages (Prev Q, Prev A, New Q).
            if (conversationHistory.size > 3) {
                conversationHistory = conversationHistory.takeLast(3).toMutableList()
            }

            // Update UI to show "Thinking"
            _uiState.value = conversationHistory.joinToString("\n\n") + "\n\nBot: (Thinking...)"

            val fullPromptContext = conversationHistory.joinToString("\n") + "\nBot: "
            var currentBotResponse = ""

            // 🛑 CRITICAL DEMO FIX: Move the heavy AI generation OFF the main thread!
            // This prevents the app from freezing (the ANR warning in your logs).
            withContext(Dispatchers.IO) {
                try {
                    routingManager.getChatResponse(fullPromptContext).collect { token ->
                        currentBotResponse += token

                        // Switch back to Main Thread ONLY to update the screen
                        withContext(Dispatchers.Main) {
                            _uiState.value = conversationHistory.joinToString("\n\n") + "\n\nBot: $currentBotResponse"
                        }
                    }

                    // When finished, save to history
                    withContext(Dispatchers.Main) {
                        conversationHistory.add("Bot: ${currentBotResponse.trim()}")
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = "Error: ${e.message}"
                    }
                }
            }
        }
    }
}