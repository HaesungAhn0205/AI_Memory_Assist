package com.example.test2

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppPreferences {
    private const val PREFS_NAME = "aru_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_SHOW_CONTEXT = "show_chat_context"
    private const val KEY_ROUTINES_JSON = "routines_json"
    private const val KEY_INACTIVE_ROUTINES = "inactive_routines"
    private const val KEY_COMPLETED_ROUTINES = "completed_routines"
    private const val KEY_SERVER_HOST = "server_host"
    private const val KEY_EASTER_EGG = "easter_egg_enabled"

    private lateinit var prefs: SharedPreferences
    private val gson = com.google.gson.Gson()

    var userId by mutableStateOf<String?>(null)
        private set
    var userName by mutableStateOf<String?>(null)
        private set
    var serverHost by mutableStateOf("http://10.10.16.15")
        private set
    var isEasterEggEnabled by mutableStateOf(false)
        private set
    var showChatContext by mutableStateOf(true)

    val serverLogs = mutableStateListOf<LogEntry>()

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String,
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        userId = prefs.getString(KEY_USER_ID, null)
        userName = prefs.getString(KEY_USER_NAME, null)
        serverHost = prefs.getString(KEY_SERVER_HOST, "http://10.10.16.15") ?: "http://10.10.16.15"
        isEasterEggEnabled = prefs.getBoolean(KEY_EASTER_EGG, false)
        showChatContext = prefs.getBoolean(KEY_SHOW_CONTEXT, true)
    }

    fun saveServerHost(host: String) {
        serverHost = host
        prefs.edit().putString(KEY_SERVER_HOST, host).apply()
    }

    fun saveLoginInfo(id: String, name: String) {
        userId = id
        userName = name
        prefs.edit().apply {
            putString(KEY_USER_ID, id)
            putString(KEY_USER_NAME, name)
            apply()
        }
    }

    fun logout() {
        userId = null
        userName = null
        prefs.edit().clear().apply()
    }

    fun updateShowChatContext(show: Boolean) {
        showChatContext = show
        prefs.edit().putBoolean(KEY_SHOW_CONTEXT, show).apply()
    }

    fun toggleEasterEgg() {
        isEasterEggEnabled = !isEasterEggEnabled
        prefs.edit().putBoolean(KEY_EASTER_EGG, isEasterEggEnabled).apply()
        addLog("EGG", "Easter Egg: ${if (isEasterEggEnabled) "ON" else "OFF"}")
    }

    fun saveRoutines(routines: List<RoutineResponse>) {
        val json = gson.toJson(routines)
        prefs.edit().putString(KEY_ROUTINES_JSON, json).apply()
    }

    fun getOfflineRoutines(): List<RoutineResponse> {
        val json = prefs.getString(KEY_ROUTINES_JSON, null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<RoutineResponse>>() {}.type
        val routines: List<RoutineResponse> = gson.fromJson(json, type)
        
        // 로컬 활성화 상태 복구
        val inactiveIds = prefs.getStringSet(KEY_INACTIVE_ROUTINES, emptySet()) ?: emptySet()
        routines.forEach { it.is_active = !inactiveIds.contains(it.id) }
        
        return routines
    }

    fun setRoutineActive(id: String, active: Boolean) {
        val inactiveIds = (prefs.getStringSet(KEY_INACTIVE_ROUTINES, emptySet()) ?: emptySet()).toMutableSet()
        if (active) {
            inactiveIds.remove(id)
        } else {
            inactiveIds.add(id)
        }
        prefs.edit().putStringSet(KEY_INACTIVE_ROUTINES, inactiveIds).apply()
    }

    private fun getTodayDate(): String {
        return java.time.LocalDate.now().toString()
    }

    fun markRoutineCompleted(id: String) {
        val today = getTodayDate()
        val completedIds = (prefs.getStringSet(KEY_COMPLETED_ROUTINES, emptySet()) ?: emptySet()).toMutableSet()
        completedIds.add("${id}_$today")
        prefs.edit().putStringSet(KEY_COMPLETED_ROUTINES, completedIds).apply()
    }

    /**
     * 루틴의 완료 상태를 제거합니다. (수정 시 다시 울리게 하기 위함)
     */
    fun clearRoutineCompletion(id: String) {
        val today = getTodayDate()
        val completedIds = (prefs.getStringSet(KEY_COMPLETED_ROUTINES, emptySet()) ?: emptySet()).toMutableSet()
        if (completedIds.remove("${id}_$today")) {
            prefs.edit().putStringSet(KEY_COMPLETED_ROUTINES, completedIds).apply()
        }
    }

    fun isRoutineCompleted(id: String): Boolean {
        val today = getTodayDate()
        val completedIds = prefs.getStringSet(KEY_COMPLETED_ROUTINES, emptySet()) ?: emptySet()
        return completedIds.contains("${id}_$today")
    }

    fun clearCompletedRoutines() {
        // 오래된 완료 데이터 정리 (선택 사항)
        val today = getTodayDate()
        val completedIds = (prefs.getStringSet(KEY_COMPLETED_ROUTINES, emptySet()) ?: emptySet()).toMutableSet()
        val onlyToday = completedIds.filter { it.endsWith("_$today") }.toSet()
        prefs.edit().putStringSet(KEY_COMPLETED_ROUTINES, onlyToday).apply()
    }

    fun addLog(tag: String, message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        serverLogs.add(0, LogEntry(timestamp, tag, message))
        if (serverLogs.size > 100) {
            serverLogs.removeAt(serverLogs.size - 1)
        }
    }
}
