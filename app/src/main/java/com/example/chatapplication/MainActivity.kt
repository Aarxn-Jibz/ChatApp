package com.example.chatapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState // <-- Added for scrolling
import androidx.compose.foundation.verticalScroll // <-- Added for scrolling
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isInitializing) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Loading Local Model into RAM...", color = Color.White)
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
        ChatScreen(routingManager = routingManager)
    }
}

@Composable
fun ChatScreen(routingManager: RoutingManager, viewModel: ChatViewModel = viewModel()) {
    val responseText by viewModel.uiState.collectAsState()
    var userInput by remember { mutableStateOf("") }

    // 1. Create the scroll state
    val scrollState = rememberScrollState()

    // 2. Auto-scroll to the bottom whenever the text updates
    LaunchedEffect(responseText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // The Conversation Display
        Text(
            text = responseText,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState) // 3. Attach the scroll state here!
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