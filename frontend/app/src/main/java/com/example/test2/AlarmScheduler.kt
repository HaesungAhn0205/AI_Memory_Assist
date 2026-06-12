package com.example.test2

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.*

object AlarmScheduler {
    fun scheduleRoutineAlarm(context: Context, routine: RoutineResponse) {
        if (!routine.is_active) {
            cancelAlarm(context, routine.id)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            action = "com.example.test2.ALARM_TRIGGER"
            putExtra("routine_id", routine.id)
            putExtra("routine_content", routine.alarm_content)
            putExtra("routine_type", routine.type)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            routine.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "HH:mm:ss" 파싱
        val parts = routine.alarm_time.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        val second = if (parts.size > 2) parts[2].toInt() else 0

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        // 정확한 알람 예약 (매일 반복되도록 하려면 추가 로직 필요하지만 일단 일회성으로 구현 후 재예약 방식 권장)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    }

    fun cancelAlarm(context: Context, routineId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RoutineReceiver::class.java).apply {
            action = "com.example.test2.ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            routineId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }
}
