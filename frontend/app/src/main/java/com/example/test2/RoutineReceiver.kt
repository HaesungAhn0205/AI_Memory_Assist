package com.example.test2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppPreferences.init(context) // 공통 초기화
        val action = intent.action
        val routineId = intent.getStringExtra("routine_id") ?: return
        val routineContent = intent.getStringExtra("routine_content") ?: "루틴 알람"
        val routineType = intent.getStringExtra("routine_type")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val routines = AppPreferences.getOfflineRoutines()
                routines.filter { it.status == "APPROVED" }.forEach {
                    AlarmScheduler.scheduleRoutineAlarm(context, it)
                }
            }
            "com.example.test2.ALARM_TRIGGER" -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        var shouldAlarm = true
                        val userId = AppPreferences.userId ?: ""
                        
                        if (routineType == "medicine") {
                            val response = NetworkClient.routineApiService.checkRoutine("medicine", userId)
                            if (response.isSuccessful && response.body()?.hasTakenMedicine == true) {
                                shouldAlarm = false
                                AppPreferences.markRoutineCompleted(routineId)
                                AppPreferences.addLog("ALARM", "Skipped: Medicine already taken")
                            }
                        } else if (routineType == "window") {
                            val response = NetworkClient.routineApiService.checkRoutine("window", userId)
                            if (response.isSuccessful) {
                                val allClosed = response.body()?.windows?.all { it.status == "닫힘" } ?: true
                                if (allClosed) {
                                    shouldAlarm = false
                                    AppPreferences.markRoutineCompleted(routineId)
                                    AppPreferences.addLog("ALARM", "Skipped: All windows closed")
                                }
                            }
                        }
                        
                        if (shouldAlarm) {
                            withContext(Dispatchers.Main) {
                                startAlarmService(context, routineId, routineContent)
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            startAlarmService(context, routineId, routineContent)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            "com.example.test2.ACTION_STOP_ALARM" -> {
                context.stopService(Intent(context, AlarmService::class.java))
            }
            "com.example.test2.ACTION_APPROVE" -> {
                handleRoutineAction(context, routineId, "APPROVED")
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(routineId.hashCode())
            }
            "com.example.test2.ACTION_REJECT" -> {
                handleRoutineAction(context, routineId, "REJECTED")
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(routineId.hashCode())
            }
        }
    }

    private fun startAlarmService(context: Context, id: String, content: String) {
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra("routine_id", id)
            putExtra("routine_content", content)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun handleRoutineAction(context: Context, routineId: String, status: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NetworkClient.routineApiService.patchRoutine(
                    RoutinePatchRequest(routine_id = routineId, status = status)
                )
                AppPreferences.addLog("ROUTINE", "Action $status for $routineId")
            } catch (e: Exception) {
                AppPreferences.addLog("ROUTINE", "Error updating status: ${e.message}")
            }
        }
    }

    private fun showNotification(context: Context, title: String, message: String, channelId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ARU Routine", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun cancelNotification(context: Context, id: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(id)
    }
}
