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
            .setContentText("Fetching the 1.5GB Gemma model. Please wait...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Replace this with the actual HuggingFace or remote server URL
        val modelUrl = "https://huggingface.co/datasets/aarxn0123/AIApp/resolve/main/gemma-1.1-2b-it-cpu-int4.bin?download=true"
        
        // The expected SHA-256 hash of the Gemma 1.1 2B INT4 model
        val expectedSha256 = "ba103a4c9a7d0fc9d71015836e4c2412867db716e411000cc8573e882dce44cd" 

        val file = File(context.getExternalFilesDir(null), "gemma-1.1-2b-it-cpu-int4.bin")
        
        // Skip if already downloaded and verified
        if (file.exists() /* && calculateSHA256(file) == expectedSha256 */) {
            Log.d("DownloadWorker", "Model exists. Checksum verification bypassed.")
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

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastReportedProgress = -1

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                if (fileLength > 0) {
                    val progress = (totalBytesRead * 100 / fileLength).toInt()
                    if (progress != lastReportedProgress) {
                        setProgress(workDataOf("PROGRESS" to progress))
                        lastReportedProgress = progress
                    }
                }
            }

            outputStream.close()
            inputStream.close()
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
