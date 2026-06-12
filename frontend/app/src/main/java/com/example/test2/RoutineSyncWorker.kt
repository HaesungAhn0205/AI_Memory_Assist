package com.example.test2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class RoutineSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppPreferences.init(applicationContext)
        val userId = AppPreferences.userId ?: return Result.success()

        try {
            val response = NetworkClient.routineApiService.getRoutines(userId)
            if (response.isSuccessful) {
                val routines = response.body() ?: emptyList()
                val pendingOnes = routines.filter { it.status == "PENDING" }
                
                pendingOnes.forEach { routine ->
                    showPendingNotification(applicationContext, routine)
                }
                AppPreferences.saveRoutines(routines)
            }
        } catch (e: Exception) {
            // 에러 발생 시에도 재예약을 위해 리턴함
        } finally {
            // 다음 3분 뒤 작업을 위해 스스로를 다시 예약
            reschedule()
        }

        return Result.success()
    }

    private fun reschedule() {
        val syncRequest = androidx.work.OneTimeWorkRequestBuilder<RoutineSyncWorker>()
            .setInitialDelay(3, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "RoutineSyncSingle",
            androidx.work.ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    private fun showPendingNotification(context: Context, routine: RoutineResponse) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "pending_routine_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "AI 추천 루틴", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val approveIntent = Intent(context, RoutineReceiver::class.java).apply {
            action = "com.example.test2.ACTION_APPROVE"
            putExtra("routine_id", routine.id)
        }
        val approvePendingIntent = PendingIntent.getBroadcast(
            context, routine.id.hashCode() + 1, approveIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(context, RoutineReceiver::class.java).apply {
            action = "com.example.test2.ACTION_REJECT"
            putExtra("routine_id", routine.id)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context, routine.id.hashCode() + 2, rejectIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("새로운 루틴 추천")
            .setContentText(routine.alarm_content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_chat, "수락", approvePendingIntent) // 아이콘은 적절히 변경 가능
            .addAction(R.drawable.ic_favorite, "거절", rejectPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(routine.id.hashCode(), notification)
    }
}
