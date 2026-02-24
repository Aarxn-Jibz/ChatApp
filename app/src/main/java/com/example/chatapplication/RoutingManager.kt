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
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // FIX: JSONObject.quote() handles all special chars correctly
            val safePromptInner = JSONObject.quote(prompt).let { it.substring(1, it.length - 1) }
            val jsonPayload = """{"contents": [{"parts":[{"text": "$safePromptInner"}]}]}"""

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d("RoutingManager", "Cloud response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }

                // FIX: JSONObject parsing — reliable regardless of quotes/newlines
                try {
                    val json = JSONObject(responseString)
                    val text = json
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    emit(text)
                } catch (parseEx: Exception) {
                    Log.e("RoutingManager", "JSON parse failed: ${parseEx.message}")
                    emit("[Error parsing cloud response: ${parseEx.message}]")
                }
            } else {
                val errorBody = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                Log.e("RoutingManager", "Cloud HTTP $responseCode: $errorBody")
                emit("[Cloud Error: HTTP $responseCode] — Check your API key.")
            }
        } catch (e: Exception) {
            Log.e("RoutingManager", "Network error: ${e.message}")
            emit("[Network failed: ${e.message}]")
        }
    }.flowOn(Dispatchers.IO)
}