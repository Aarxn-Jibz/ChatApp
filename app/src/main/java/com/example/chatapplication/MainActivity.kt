package com.example.chatapplication

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var llmEngine: LlmEngine
    private lateinit var routingManager: RoutingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        llmEngine = LlmEngine(this)
        routingManager = RoutingManager(this, llmEngine)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D0D0D),
                    surface = Color(0xFF1A1A1A),
                    primary = Color(0xFF4CAF50)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    AppNavigation(llmEngine, routingManager)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(llmEngine: LlmEngine, routingManager: RoutingManager) {
    var isModelLoaded by remember { mutableStateOf(false) }
    var skipToCloud by remember { mutableStateOf(false) }

    if (!isModelLoaded && !skipToCloud) {
        ModelStartScreen(
            onLocalModelLoaded = { isModelLoaded = true },
            onCloudOnly = { skipToCloud = true },
            llmEngine = llmEngine
        )
    } else {
        ChatScreen(routingManager = routingManager, llmEngine = llmEngine, initialOnlineMode = skipToCloud)
    }
}

// ── Startup Screen ────────────────────────────────────────────────────────────
@Composable
fun ModelStartScreen(
    llmEngine: LlmEngine,
    onLocalModelLoaded: () -> Unit,
    onCloudOnly: () -> Unit
) {
    val context = LocalContext.current
    var downloadProgress by remember { mutableStateOf(-1) }
    var isInitializing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<ModelDownloadWorker>().build()
        workManager.enqueueUniqueWork("ModelDownload", androidx.work.ExistingWorkPolicy.KEEP, workRequest)
        
        workManager.getWorkInfosForUniqueWorkFlow("ModelDownload").collect { workInfos ->
            val workInfo = workInfos.firstOrNull()
            if (workInfo != null) {
                if (workInfo.state == androidx.work.WorkInfo.State.RUNNING) {
                    downloadProgress = workInfo.progress.getInt("PROGRESS", 0)
                } else if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED && !isInitializing) {
                    isInitializing = true
                    llmEngine.initialize()
                    onLocalModelLoaded()
                } else if (workInfo.state == androidx.work.WorkInfo.State.FAILED) {
                    // Fallback to cloud if download fails
                    onCloudOnly()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Chat Application", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Preparing models...", color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(48.dp))

        if (isInitializing) {
            CircularProgressIndicator(color = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading model into memory…", color = Color.Gray, fontSize = 13.sp)
        } else if (downloadProgress >= 0) {
            CircularProgressIndicator(progress = downloadProgress / 100f, color = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Downloading model... $downloadProgress%", color = Color.Gray, fontSize = 13.sp)
        } else {
            CircularProgressIndicator(color = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Checking local model...", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

// ── Chat Screen ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    routingManager: RoutingManager,
    llmEngine: LlmEngine,
    initialOnlineMode: Boolean,
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val savedApiKey by settingsRepository.apiKeyFlow.collectAsState(initial = "")
    
    var isOnlineMode by remember { mutableStateOf(initialOnlineMode) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val messages by viewModel.uiState.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    var userInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(savedApiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Gemini API Key", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Get a free key at aistudio.google.com", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tempKey.isNotBlank()) {
                        coroutineScope.launch {
                            settingsRepository.saveApiKey(tempKey.trim())
                            isOnlineMode = true
                        }
                        showApiKeyDialog = false
                    } else {
                        coroutineScope.launch { snackbarHostState.showSnackbar("API key cannot be empty") }
                    }
                }) { Text("Save & Go Online", color = Color(0xFF4CAF50)) }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat Application", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        val modeLabel = when {
                            isOnlineMode -> "Cloud · Gemini 2.5 Flash"
                            llmEngine.isLoaded() -> "Local · Gemma"
                            else -> "No model loaded"
                        }
                        Text(modeLabel, color = Color.Gray, fontSize = 11.sp)
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Local", color = if (!isOnlineMode) Color(0xFF4CAF50) else Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isOnlineMode,
                            onCheckedChange = { checked ->
                                if (checked && savedApiKey.isBlank()) {
                                    showApiKeyDialog = true
                                } else {
                                    isOnlineMode = checked
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF4CAF50),
                                checkedTrackColor = Color(0xFF1B2E1B),
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color(0xFF1A1A1A)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Online", color = if (isOnlineMode) Color(0xFF4CAF50) else Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D0D))
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(message.text))
                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // Input bar
            Surface(color = Color(0xFF1A1A1A), tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message…", color = Color.DarkGray) },
                        enabled = !isGenerating,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF2A2A2A),
                            cursorColor = Color.White,
                            disabledTextColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // Send button
                    Button(
                        onClick = {
                            val trimmed = userInput.trim()
                            if (trimmed.isNotBlank() && !isGenerating) {
                                if (isOnlineMode && savedApiKey.isBlank()) {
                                    showApiKeyDialog = true
                                } else {
                                    viewModel.sendPrompt(trimmed, routingManager, isOnlineMode, savedApiKey)
                                    userInput = ""
                                }
                            }
                        },
                        enabled = !isGenerating && userInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            disabledContainerColor = Color(0xFF2A2A2A)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Gray, strokeWidth = 2.dp)
                        } else {
                            Text("Send", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Message Bubble ────────────────────────────────────────────────────────────
@Composable
fun MessageBubble(message: ChatMessage, onCopy: () -> Unit) {
    val isBot = message.role == "Bot"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isBot) Arrangement.Start else Arrangement.End
    ) {
        if (isBot) {
            Column(modifier = Modifier.fillMaxWidth(0.85f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .background(if (message.isError) Color(0xFF3B1A1A) else Color(0xFF1E1E1E))
                        .padding(12.dp)
                ) {
                    if (message.isThinking) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(3) {
                                Box(modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Gray))
                            }
                        }
                    } else {
                        Text(
                            text = message.text,
                            color = if (message.isError) Color(0xFFFF6B6B) else Color.White,
                            fontSize = 15.sp, lineHeight = 22.sp
                        )
                    }
                }
                if (!message.isThinking) {
                    Row(modifier = Modifier.padding(top = 4.dp, start = 4.dp)) {
                        TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("📋 Copy", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(Color(0xFF1B3A2B))
                    .padding(12.dp)
                    .fillMaxWidth(0.82f)
            ) {
                Text(message.text, color = Color(0xFFE8F5E9), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Start)
            }
        }
    }
}