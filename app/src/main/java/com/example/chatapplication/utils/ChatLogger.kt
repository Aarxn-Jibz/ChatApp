package com.example.chatapplication.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.chatapplication.ChatMessage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatLogger {

    private const val LOG_FOLDER_NAME = "ChatLogs"

    /**
     * Appends a single message to today's log file.
     * The file will be named e.g., "chat_log_2026-03-09.txt"
     */
    fun logMessage(context: Context, message: ChatMessage) {
        // We don't want to log "thinking" states or the welcome message
        if (message.isThinking || message.text == "Type a message below to start chatting!" || message.text == "Chat cleared. Start a new conversation!") return

        val logFolder = File(context.filesDir, LOG_FOLDER_NAME)
        if (!logFolder.exists()) {
            logFolder.mkdirs()
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fileName = "chat_log_${dateFormat.format(Date())}.txt"
        val logFile = File(logFolder, fileName)

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())

        // Format: [18:35:05] [User]: Hello there!
        // Format: [18:35:10] [Bot] (Local): I am doing well!
        val sourceStr = if (message.source != null) " (${message.source})" else ""
        val logEntry = "[$timestamp] [${message.role}]$sourceStr: ${message.text}\n"

        try {
            FileOutputStream(logFile, true).use { output ->
                output.write(logEntry.toByteArray())
            }
            Log.d("ChatLogger", "Successfully wrote to ${logFile.absolutePath}")
            Log.d("ChatLogger", "Wrote: $logEntry")
        } catch (e: Exception) {
            Log.e("ChatLogger", "Error writing to log file: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Exports today's log file to a user-selected location (URI).
     */
    fun exportTodayLog(context: Context, targetUri: Uri): Boolean {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fileName = "chat_log_${dateFormat.format(Date())}.txt"
        val logFolder = File(context.filesDir, LOG_FOLDER_NAME)
        val sourceFile = File(logFolder, fileName)

        if (!sourceFile.exists()) {
            Log.e("ChatLogger", "Cannot export: Today's log file doesn't exist.")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("ChatLogger", "Successfully exported log to $targetUri")
            true
        } catch (e: Exception) {
            Log.e("ChatLogger", "Failed to export log file", e)
            false
        }
    }
}
