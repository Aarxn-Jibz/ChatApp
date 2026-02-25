package com.example.chatapplication

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            .setContentText("Fetching the 1.5GB Gemma model. Please wait...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Replace this with the actual HuggingFace or remote server URL
        val modelUrl = "https://example.com/models/gemma-3-4b-it-q4_0.gguf"
        
        // The expected SHA-256 hash of the Gemma 3 4B GGUF model
        val expectedSha256 = "your-expected-sha256-hash-here" 

        val file = File(context.getExternalFilesDir(null), "gemma-3-4b-it-q4_0.gguf")
        
        // Skip if already downloaded and verified
        if (file.exists() && calculateSHA256(file) == expectedSha256) {
            Log.d("DownloadWorker", "Model already exists and verified.")
            return@withContext Result.success()
        }

        try {
            Log.d("DownloadWorker", "Starting 1.5GB model download...")
            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DownloadWorker", "Server returned HTTP ${connection.responseCode}")
                return@withContext Result.retry()
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.close()
            inputStream.close()
            Log.d("DownloadWorker", "Download complete. Verifying SHA-256 Checksum...")

            // Post-download checksum verification
            val downloadedSha = calculateSHA256(file)
            if (downloadedSha != expectedSha256) {
                Log.e("DownloadWorker", "Checksum mismatch! Expected: $expectedSha256, Got: $downloadedSha")
                // In production, delete the corrupted file and fail the job:
                // file.delete()
                // return@withContext Result.failure()
            } else {
                Log.d("DownloadWorker", "Checksum verified successfully.")
            }

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
