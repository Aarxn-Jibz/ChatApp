package com.example.chatapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RoutingManager(private val context: Context, private val localEngine: LlmEngine) {

    fun getChatResponse(messages: List<ChatMessage>, isOnline: Boolean, apiKey: String): Flow<String> {
        return if (isOnline && apiKey.isNotBlank()) {
            getRemoteResponse(messages, apiKey)
        } else {
            val systemPrompt = "You are a helpful, conversational AI companion. Respond naturally and concisely. Do not use hashtags or sound like a social media post."
            val localPromptBuilder = StringBuilder()
            var isFirstUserMessage = true
            
            for (message in messages) {
                if (message.role == "User") {
                    localPromptBuilder.append("<start_of_turn>user\n")
                    if (isFirstUserMessage) {
                        localPromptBuilder.append(systemPrompt).append("\n\n")
                        isFirstUserMessage = false
                    }
                    localPromptBuilder.append(message.text).append("<end_of_turn>\n")
                } else if (message.role == "Bot") {
                    localPromptBuilder.append("<start_of_turn>model\n")
                    localPromptBuilder.append(message.text).append("<end_of_turn>\n")
                }
            }
            localPromptBuilder.append("<start_of_turn>model\n")
            
            localEngine.generateResponseStream(localPromptBuilder.toString())
        }
    }

    private fun getRemoteResponse(messages: List<ChatMessage>, apiKey: String): Flow<String> = flow {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val systemInstruction = "You are a helpful, conversational AI companion. Respond naturally and concisely. Do not use hashtags or sound like a social media post."
            val partsArray = JSONArray()
            
            for (message in messages) {
                val role = if (message.role == "User") "user" else "model"
                val partObj = JSONObject().apply { put("text", message.text) }
                val contentObj = JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(partObj))
                }
                partsArray.put(contentObj)
            }

            val jsonPayloadObj = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply { put("text", systemInstruction) }))
                })
                put("contents", partsArray)
            }
            val jsonPayload = jsonPayloadObj.toString()

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
                        getChatResponse(messages, false, "").collect { emit(it) }
                    }
                } catch (parseEx: Exception) {
                    Log.e("RoutingManager", "JSON parse failed: ${parseEx.message}. Falling back to local model.")
                    emit("[Notice: Cloud API parsing failed. Falling back to Local Model]\n\n")
                    getChatResponse(messages, false, "").collect { emit(it) }
                }
            } else {
                val errorBody = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                Log.e("RoutingManager", "Cloud HTTP $responseCode: $errorBody. Falling back to local model.")
                emit("[Notice: Cloud API Error (HTTP $responseCode). Falling back to Local Model]\n\n")
                getChatResponse(messages, false, "").collect { emit(it) }
            }
        } catch (e: Exception) {
            Log.e("RoutingManager", "Network error: ${e.message}. Falling back to local model.")
            emit("[Notice: Network connection failed. Falling back to Local Model]\n\n")
            getChatResponse(messages, false, "").collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)
}