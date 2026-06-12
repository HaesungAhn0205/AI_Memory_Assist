package com.example.test2

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.test2.ui.theme.ARUTheme

/**
 * 지정된 시간에 루틴 알림을 화면에 크게 표시하는 액티비티입니다.
 * 소리와 진동을 함께 발생시켜 사용자가 알람을 인지하도록 돕습니다.
 */
class AlarmActivity : ComponentActivity() {
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 인텐트로부터 루틴 내용 수신
        val content = intent.getStringExtra("routine_content") ?: "루틴 알람"
        
        // 벨소리 및 진동 시작
        startAlarm()

        setContent {
            ARUTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 알람 아이콘 표시
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(140.dp)
                        ) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(32.dp)
                                    .fillMaxSize(),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        Text(
                            text = "루틴 알람",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        
                        // 루틴 할 일 내용 표시
                        Text(
                            text = content,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(80.dp))
                        
                        // 알람 끄기 버튼
                        Button(
                            onClick = { 
                                // 루틴 완료 처리 및 서비스/액티비티 종료
                                val routineId = intent.getStringExtra("routine_id")
                                routineId?.let { AppPreferences.markRoutineCompleted(it) }
                                stopService(Intent(this@AlarmActivity, AlarmService::class.java))
                                finish() 
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("알람 끄기", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    /**
     * 기본 알람음(또는 이스터에그 음원)과 진동을 무한 반복으로 재생합니다.
     */
    private fun startAlarm() {
        // SharedPreferences에서 직접 로드 (AppPreferences 싱글톤의 초기화 지연 방지)
        val sharedPrefs = getSharedPreferences("aru_prefs", Context.MODE_PRIVATE)
        val easterEggEnabled = sharedPrefs.getBoolean("easter_egg_enabled", false)

        if (easterEggEnabled) {
            // 이스터에그: est.mp3 재생 시도
            try {
                val resId = resources.getIdentifier("est", "raw", packageName)
                if (resId != 0) {
                    mediaPlayer = MediaPlayer.create(this, resId)
                    mediaPlayer?.isLooping = true
                    mediaPlayer?.start()
                } else {
                    playDefaultAlarm()
                }
            } catch (e: Exception) {
                playDefaultAlarm()
            }
        } else {
            playDefaultAlarm()
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 1000) // 1초 진동, 1초 대기 패턴
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0은 반복 시작 인덱스
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    /**
     * 액티비티 종료 시 벨소리와 진동을 정지합니다.
     */
    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
    }

    private fun playDefaultAlarm() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(applicationContext, uri)
        ringtone?.play()
    }
}
