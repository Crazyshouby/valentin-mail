package com.valentinlarge.courrier

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Relève périodique du courrier en arrière-plan. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            Sync.check(applicationContext)
            Result.success()
        } catch (e: OfflineException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success()
        }
}

object Sync {
    private const val CHANNEL = "nouveaux-messages"
    private const val WORK = "releve-courrier"
    private const val GROUP = "courrier"
    const val EXTRA_MESSAGE = "message_id"

    /** Android ne garantit pas mieux qu'un passage toutes les quinze minutes environ. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Cherche les messages arrivés depuis la dernière relève et les signale. Renvoie leur nombre. */
    suspend fun check(context: Context): Int {
        val repo = Repo.get(context)
        val prefs = repo.prefs
        if (prefs.token == null) return 0

        val since = prefs.lastSeen
        if (since == 0L) {
            // Première relève : on prend seulement ses repères, sans notifier tout l'historique.
            val page = repo.api.list("inbox")
            withContext(Dispatchers.IO) { repo.db.upsert(page.messages) }
            prefs.lastSeen = page.messages.maxOfOrNull { it.date } ?: System.currentTimeMillis()
            return 0
        }

        val page = repo.api.list("inbox", after = since)
        if (page.messages.isEmpty()) return 0

        withContext(Dispatchers.IO) { repo.db.upsert(page.messages) }
        prefs.lastSeen = maxOf(since, page.messages.maxOf { it.date })
        val fresh = page.messages.filter { !it.outgoing && !it.isRead }
        fresh.sortedBy { it.date }.forEach { notify(context, it) }
        repo.prefetch(page.messages)
        return fresh.size
    }

    private fun notify(context: Context, item: MailItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nouveaux messages", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_MESSAGE, item.id)
        val pending = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF1D57B8.toInt())
            .setContentTitle(item.fromName.ifBlank { item.fromAddr }.ifBlank { "Expéditeur inconnu" })
            .setContentText(item.subject.ifBlank { "(sans objet)" })
            .setWhen(item.date)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentIntent(pending)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }
}
