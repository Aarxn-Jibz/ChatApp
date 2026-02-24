package com.example.chatapplication

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow("Type a message below to start chatting!")
    val uiState: StateFlow<String> = _uiState.asStateFlow()

    fun sendPrompt(prompt: String, routingManager: RoutingManager) {
        viewModelScope.launch(Dispatchers.Main) {
            // Update the UI immediately to show the user's question
            _uiState.value = "You: $prompt\n\nBot: (Thinking...)"

            // Set up the string that will hold the final typed-out response
            var currentResponse = "You: $prompt\n\nBot: "

            try {
                routingManager.getChatResponse(prompt).collect { token ->
                    currentResponse += token
                    _uiState.value = currentResponse // Push the new token to the screen
                    Log.d("LLM_TEST", "Token received: $token")
                }
            } catch (e: Exception) {
                _uiState.value = "Error: ${e.message}"
            }
        }
    }
}