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
            // Use the SSE streaming endpoint so tokens are emitted as they arrive
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=$apiKey")
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

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayloadObj.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d("RoutingManager", "Cloud SSE response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read SSE stream line-by-line and emit each text chunk immediately
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (!trimmed.startsWith("data: ")) continue

                        val jsonStr = trimmed.removePrefix("data: ")
                        if (jsonStr == "[DONE]") break

                        try {
                            val json = JSONObject(jsonStr)
                            val candidates = json.optJSONArray("candidates") ?: continue
                            if (candidates.length() == 0) continue

                            val candidate = candidates.getJSONObject(0)

                            // Emit text chunk from this SSE event
                            val content = candidate.optJSONObject("content")
                            if (content != null) {
                                val parts = content.optJSONArray("parts")
                                if (parts != null && parts.length() > 0) {
                                    val text = parts.getJSONObject(0).optString("text", "")
                                    if (text.isNotEmpty()) emit(text)
                                }
                            }

                            // Stop if the model signalled it finished
                            val finishReason = candidate.optString("finishReason", "")
                            if (finishReason == "STOP" || finishReason == "MAX_TOKENS" ||
                                finishReason == "SAFETY" || finishReason == "RECITATION") break

                        } catch (parseEx: Exception) {
                            Log.w("RoutingManager", "Failed to parse SSE chunk: ${parseEx.message}")
                        }
                    }
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
