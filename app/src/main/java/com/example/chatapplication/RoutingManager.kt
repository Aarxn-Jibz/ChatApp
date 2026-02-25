package com.example.chatapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RoutingManager(private val context: Context, private val localEngine: LlmEngine) {

    fun getChatResponse(prompt: String, isOnline: Boolean, apiKey: String): Flow<String> {
        return if (isOnline && apiKey.isNotBlank()) {
            getRemoteResponse(prompt, apiKey)
        } else {
            localEngine.generateResponseStream(prompt)
        }
    }

    private fun getRemoteResponse(prompt: String, apiKey: String): Flow<String> = flow {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // FIX: JSONObject.quote() handles all special chars correctly
            val safePromptInner = JSONObject.quote(prompt).let { it.substring(1, it.length - 1) }
            val jsonPayload = """{"contents": [{"parts":[{"text": "$safePromptInner"}]}]}"""

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d("RoutingManager", "Cloud response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }

                // FIX: JSONObject parsing — handle candidates and safety ratings gracefully
                try {
                    val json = JSONObject(responseString)
                    
                    // Check if candidates array exists and has items
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val text = candidates
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        emit(text)
                    } 
                    // Fallback to checking for safety feedback if blocked
                    else if (json.has("promptFeedback")) {
                        val feedback = json.getJSONObject("promptFeedback")
                        val blockReason = feedback.optString("blockReason", "Unknown")
                        emit("[Blocked by Safety Filters: $blockReason]")
                    } else {
                        Log.e("RoutingManager", "Unexpected response format. Falling back to local model.")
                        emit("[Notice: Cloud API returned unexpected format. Falling back to Local Model]\n\n")
                        localEngine.generateResponseStream(prompt).collect { emit(it) }
                    }
                } catch (parseEx: Exception) {
                    Log.e("RoutingManager", "JSON parse failed: ${parseEx.message}. Falling back to local model.")
                    emit("[Notice: Cloud API parsing failed. Falling back to Local Model]\n\n")
                    localEngine.generateResponseStream(prompt).collect { emit(it) }
                }
            } else {
                val errorBody = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                Log.e("RoutingManager", "Cloud HTTP $responseCode: $errorBody. Falling back to local model.")
                emit("[Notice: Cloud API Error (HTTP $responseCode). Falling back to Local Model]\n\n")
                localEngine.generateResponseStream(prompt).collect { emit(it) }
            }
        } catch (e: Exception) {
            Log.e("RoutingManager", "Network error: ${e.message}. Falling back to local model.")
            emit("[Notice: Network connection failed. Falling back to Local Model]\n\n")
            localEngine.generateResponseStream(prompt).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)
}