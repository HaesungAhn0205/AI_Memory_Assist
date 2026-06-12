package com.example.test2

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class AlarmService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val content = intent?.getStringExtra("routine_content") ?: "루틴 알람"
        val routineId = intent?.getStringExtra("routine_id") ?: "unknown"

        createNotificationChannel()
        
        // 화면이 켜져 있을 때 오버레이 띄우기
        showFloatingAlarm(routineId, content)

        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra("routine_content", content)
            putExtra("routine_id", routineId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, routineId.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "alarm_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ARU 알람 작동 중")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
        startRinging()

        return START_NOT_STICKY
    }

    private fun showFloatingAlarm(routineId: String, content: String) {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = 80
                // 양옆 마진을 주기 위해 가로 크기 조정
                width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            }

            overlayView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // ARU 컨셉에 맞는 부드러운 배경색 (PrimaryContainer 스타일)
                setBackgroundResource(R.drawable.bg_floating_alarm)
                setPadding(80, 64, 80, 64)
                elevation = 30f
                gravity = Gravity.CENTER_VERTICAL
            }

            // 아이콘 추가
            val iconView = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                layoutParams = LinearLayout.LayoutParams(160, 160)
                setPadding(0, 0, 48, 0)
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = "ARU 루틴 알람"
                setTextColor(android.graphics.Color.parseColor("#1976D2")) // Primary 컬러 계열
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            val contentTv = TextView(this).apply {
                text = content
                setTextColor(android.graphics.Color.BLACK)
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
            }

            textLayout.addView(titleTv)
            textLayout.addView(contentTv)

            val stopBtn = Button(this).apply {
                text = "끄기"
                setBackgroundResource(R.drawable.btn_alarm_stop) // 버튼 스타일 적용
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(280, 140).apply {
                    setMargins(24, 0, 0, 0)
                }
                setOnClickListener {
                    AppPreferences.markRoutineCompleted(routineId)
                    stopSelf()
                }
            }

            overlayView?.addView(iconView)
            overlayView?.addView(textLayout)
            overlayView?.addView(stopBtn)

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            AppPreferences.addLog("ALARM", "Overlay failed: ${e.message}")
        }
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone?.play()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        
        val pattern = longArrayOf(0, 1000, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel("alarm_channel", "ARU Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        ringtone?.stop()
        vibrator?.cancel()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
