package com.example.chatapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // <-- Added for Color.Black / Color.White
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private lateinit var llmEngine: LlmEngine
    private lateinit var routingManager: RoutingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        llmEngine = LlmEngine(this)
        routingManager = RoutingManager(this, llmEngine)

        setContent {
            MaterialTheme {
                // FORCED BLACK BACKGROUND
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    AppNavigation(llmEngine, routingManager)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(llmEngine: LlmEngine, routingManager: RoutingManager) {
    var isModelLoaded by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (!isModelLoaded) {
        // --- SCREEN 1: Initialization ---
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isInitializing) {
                CircularProgressIndicator(color = Color.White) // White spinner
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading Local Model into RAM...", color = Color.White) // White text
            } else {
                Button(onClick = {
                    isInitializing = true
                    coroutineScope.launch {
                        llmEngine.initialize()
                        isModelLoaded = true
                        isInitializing = false
                    }
                }) {
                    Text("1. Initialise Model", color = Color.White)
                }
            }
        }
    } else {
        // --- SCREEN 2: The Interactive Chat UI ---
        ChatScreen(routingManager = routingManager)
    }
}

@Composable
fun ChatScreen(routingManager: RoutingManager, viewModel: ChatViewModel = viewModel()) {
    val responseText by viewModel.uiState.collectAsState()
    var userInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // The Conversation Display
        Text(
            text = responseText,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White, // FORCED WHITE TEXT
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // The Text Input and Send Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type your message...", color = Color.LightGray) },
                // Force the text field to be visible on black background
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = Color.White
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (userInput.isNotBlank()) {
                        viewModel.sendPrompt(userInput, routingManager)
                        userInput = ""
                    }
                }
            ) {
                Text("Send", color = Color.White)
            }
        }
    }
}