package com.example.chatapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    // We create the engine here at the Activity level so it doesn't get
    // destroyed and recreated every time the screen rotates or updates.
    private lateinit var llmEngine: LlmEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the engine class with the activity context
        llmEngine = LlmEngine(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Start the main app flow
                    AppNavigation(llmEngine)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(llmEngine: LlmEngine) {
    // State to track if we should show the Chat screen yet
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
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading 1.3GB Model into RAM... Please wait.")
            } else {
                Button(onClick = {
                    isInitializing = true

                    coroutineScope.launch {
                        // ⚠️ YOUR INIT CODE GOES HERE ⚠️
                        // I don't know the exact name of your init function,
                        // but it might look something like this:
                        llmEngine.initialize()

                        // Once it finishes loading without crashing, switch screens:
                        isModelLoaded = true
                        isInitializing = false
                    }
                }) {
                    Text("1. Initialise Model")
                }
            }
        }
    } else {
        // --- SCREEN 2: The Chat UI ---
        // Once isModelLoaded is true, the UI automatically flips to this screen!
        ChatScreen(llmEngine = llmEngine)
    }
}
@Composable
fun ChatScreen(llmEngine: LlmEngine, viewModel: ChatViewModel = viewModel()) {
    // Correctly observe the StateFlow
    val responseText by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = responseText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.sendPrompt("Tell me a dad joke.", llmEngine) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. Test Prompt")
        }
    }
}