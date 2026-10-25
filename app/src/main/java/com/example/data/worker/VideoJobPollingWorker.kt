package com.example.data.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.data.local.OmniDatabase
import com.example.data.repository.OmniRepository
import com.example.data.security.TinkSecretVault
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * WorkManager CoroutineWorker that polls asynchronous video generation jobs across
 * OmniRoute, Luma Dream Machine, Runway, Replicate, fal.ai, and Google Veo 2.
 * Persists status updates to Room, downloads completed MP4 assets locally when possible,
 * and posts a system notification on completion.
 */
class VideoJobPollingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val jobId = inputData.getString(KEY_VIDEO_JOB_ID) ?: return androidx.work.ListenableWorker.Result.failure()
        val database = OmniDatabase.getInstance(applicationContext)
        val dao = database.omniDao()
        val vault = TinkSecretVault(applicationContext)
        val repository = OmniRepository(applicationContext, dao, vault)

        var currentJob = dao.getVideoJobById(jobId) ?: return androidx.work.ListenableWorker.Result.success()
        if (currentJob.status !in listOf("QUEUED", "PROCESSING")) {
            return androidx.work.ListenableWorker.Result.success()
        }

        // Poll up to 12 cycles (every 4 seconds) inside this worker invocation
        var cycles = 0
        while (cycles < 12 && !isStopped) {
            val updated = repository.pollVideoJobOnce(currentJob.id).getOrNull() ?: break
            currentJob = updated
            if (currentJob.status == "COMPLETED") {
                postVideoNotification(
                    title = "Video Generation Complete",
                    body = "${currentJob.providerDisplayName} finished rendering: \"${currentJob.prompt.take(56)}\"",
                    notificationId = currentJob.id.hashCode()
                )
                return androidx.work.ListenableWorker.Result.success()
            } else if (currentJob.status in listOf("FAILED", "CANCELED")) {
                postVideoNotification(
                    title = "Video Generation Failed",
                    body = currentJob.errorMessage ?: "Unable to complete video job on ${currentJob.providerDisplayName}.",
                    notificationId = currentJob.id.hashCode()
                )
                return androidx.work.ListenableWorker.Result.success()
            }
            cycles++
            if (cycles < 12) {
                delay(4000L)
            }
        }

        return if (currentJob.pollAttempts < 40 && currentJob.status in listOf("QUEUED", "PROCESSING")) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    private fun postVideoNotification(title: String, body: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Generation Jobs",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Completion notifications for async AI video generation jobs"
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        }
    }

    companion object {
        const val KEY_VIDEO_JOB_ID = "video_job_id"
        private const val CHANNEL_ID = "omniai_video_jobs"

        fun enqueueForJob(context: Context, jobId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<VideoJobPollingWorker>()
                .setInputData(workDataOf(KEY_VIDEO_JOB_ID to jobId))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag("video_poll_$jobId")
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "video_poll_$jobId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
        }

        fun cancelForJob(context: Context, jobId: String) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork("video_poll_$jobId")
            }
        }
    }
}
