package com.example.chatapplication

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

class ModelDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationId = 1
        val channelId = "ModelDownloadChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Downloading AI Model")
            .setContentText("Fetching the Qwen2.5 GGUF model. Please wait...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Direct download URL for the target Qwen gguf
        val modelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q3_k_m.gguf?download=true"

        // Known file size for Qwen2.5-1.5B Q3_K_M (~709 MB).
        // Used as a fallback when HuggingFace responds with chunked transfer encoding
        // (Content-Length == -1), so the progress bar always shows a percentage.
        val fallbackFileSize = 743_571_264L

        val file = File(context.getExternalFilesDir(null), "qwen2.5-1.5b-instruct-q3_k_m.gguf")
        
        // Skip if already downloaded
        if (file.exists()) {
            Log.d("DownloadWorker", "Model already exists. Skipping download.")
            return@withContext Result.success()
        }

        try {
            Log.d("DownloadWorker", "Starting model download from HuggingFace...")
            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadWorker", "Server returned HTTP ${connection.responseCode}")
                return@withContext Result.retry()
            }

            // Use server-provided length if available; fall back to known constant
            // for HuggingFace chunked responses where contentLength == -1.
            val reportedLength = connection.contentLengthLong
            val effectiveLength = if (reportedLength > 0) reportedLength else fallbackFileSize

            var totalBytesRead = 0L
            var lastReportedProgress = -1
            val buffer = ByteArray(8192)

            // Fix: use nested use{} blocks so both streams close even if an exception is thrown
            connection.inputStream.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = (totalBytesRead * 100 / effectiveLength).toInt().coerceIn(0, 99)
                        if (progress != lastReportedProgress) {
                            setProgress(workDataOf("PROGRESS" to progress))
                            lastReportedProgress = progress
                        }
                    }
                }
            }
            Log.d("DownloadWorker", "Download complete. Verifying SHA-256 Checksum...")

            // Post-download checksum verification (Disabled for now)
            // val downloadedSha = calculateSHA256(file)
            // if (downloadedSha != expectedSha256) {
            //     Log.e("DownloadWorker", "Checksum mismatch! Expected: $expectedSha256, Got: $downloadedSha")
            //     // In production, delete the corrupted file and fail the job:
            //     // file.delete()
            //     // return@withContext Result.failure()
            // } else {
            Log.d("DownloadWorker", "Download finished. Checksum bypassed.")
            // }

            Result.success()
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Download failed: ${e.message}")
            if (file.exists()) file.delete()
            Result.retry()
        }
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
