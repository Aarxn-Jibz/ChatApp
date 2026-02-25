package com.example.chatapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val role: String,         // "User" or "Bot"
    val text: String,
    val isThinking: Boolean = false,
    val isError: Boolean = false,
    val source: String? = null // "Cloud" or "Local"
)

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<List<ChatMessage>>(
        listOf(ChatMessage("Bot", "Type a message below to start chatting!"))
    )
    val uiState: StateFlow<List<ChatMessage>> = _uiState.asStateFlow()

    // Prevents spamming Send while a response is still streaming
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Context window size — configurable via Settings (Phase 5)
    var contextWindowSize: Int = 6

    fun sendPrompt(prompt: String, routingManager: RoutingManager, isOnlineMode: Boolean, apiKey: String, networkMonitor: NetworkMonitor) {
        if (_isGenerating.value) return // Guard against concurrent sends

        viewModelScope.launch(Dispatchers.Main) {
            _isGenerating.value = true

            val currentHistory = _uiState.value
                .filter { !it.isThinking }
                .toMutableList()
            currentHistory.add(ChatMessage("User", prompt))

            val trimmedHistory = if (currentHistory.size > contextWindowSize) {
                currentHistory.takeLast(contextWindowSize).toMutableList()
            } else {
                currentHistory
            }

            // Show thinking indicator
            _uiState.value = trimmedHistory.toMutableList().apply {
                add(ChatMessage("Bot", "...", isThinking = true))
            }

            val fullPrompt = trimmedHistory.joinToString("\n") { "${it.role}: ${it.text}" } + "\nBot: "
            var currentBotResponse = ""

            withContext(Dispatchers.IO) {
                try {
                    routingManager.getChatResponse(fullPrompt, isOnlineMode, apiKey).collect { token ->
                        currentBotResponse += token
                        withContext(Dispatchers.Main) {
                            val actualSource = if (currentBotResponse.contains("Falling back to Local Model")) "Local" 
                                               else if (isOnlineMode && networkMonitor.isConnected.value) "Cloud" 
                                               else "Local"
                            _uiState.value = trimmedHistory.toMutableList().apply {
                                add(ChatMessage("Bot", currentBotResponse, source = actualSource))
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = trimmedHistory.toMutableList().apply {
                            add(ChatMessage("Bot", "Error: ${e.message}", isError = true))
                        }
                    }
                }
            }

            _isGenerating.value = false
        }
    }

    fun clearChat() {
        _uiState.value = listOf(ChatMessage("Bot", "Chat cleared. Start a new conversation!"))
    }
}