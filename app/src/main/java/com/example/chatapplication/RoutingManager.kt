package com.example.chatapplication

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RoutingManager(private val context: Context, private val llmEngine: LlmEngine) {

    private var isNetworkAvailable = false

    init {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkAvailable = true
            }

            override fun onLost(network: Network) {
                isNetworkAvailable = false
            }
        })
        
        // Initial state check
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun isLocalModelReady(): Boolean {
        // Technically this should check the exact file, or if the MediaPipe engine is successfully loaded
        val file = File(context.getExternalFilesDir(null), "gemma-1.1-2b-it-cpu-int4.bin")
        return file.exists()
    }

    suspend fun getChatResponse(prompt: String): Flow<String> = flow {
        if (isLocalModelReady()) {
            Log.d("RoutingManager", "Routing to LOCAL LLM Engine.")
            
            // ⚠️ Format the prompt specifically for Gemma IT models locally
            val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
            
            llmEngine.generateResponseStream(formattedPrompt).collect {
                emit(it)
            }
        } else {
            if (!isNetworkAvailable) {
                emit("Error: Local model is still downloading and NO network connection is available for fallback.")
                return@flow
            }
            
            Log.d("RoutingManager", "Routing to REMOTE Cloud API Fallback...")
            
            // Wait up to strictly 3 seconds
            val response = fetchFromRemoteAPI(prompt)
            if (response != null) {
                emit(response)
            } else {
                emit("Error: Remote API timed out or failed to respond after 3 seconds.")
            }
        }
    }

    private suspend fun fetchFromRemoteAPI(prompt: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(3000L) { // STRICT 3-second timeout
            try {
                // Placeholder for an actual Remote API call
                val url = URL("https://example.com/api/chat?prompt=${prompt.replace(" ", "%20")}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                
                // If it connects, we simulate an API response.
                // In production, you'd parse JSON response here.
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "This is a fallback response from the Cloud API. The local model is currently downloading."
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("RoutingManager", "Network fallback call failed: ${e.message}")
                null
            }
        }
    }
}
