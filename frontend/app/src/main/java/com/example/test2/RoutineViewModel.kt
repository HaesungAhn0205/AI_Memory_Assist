package com.example.test2

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 루틴(일과) 데이터를 관리하고 알람을 스케줄링하는 ViewModel입니다.
 */
class RoutineViewModel(application: Application) : AndroidViewModel(application) {
    // 승인 대기 중인 루틴 리스트
    val pendingRoutines = mutableStateListOf<RoutineResponse>()
    
    // 오늘 할 일 루틴 리스트
    val todayToDoRoutines = mutableStateListOf<RoutineResponse>()
    // 오늘 완료된 루틴 리스트
    val todayCompletedRoutines = mutableStateListOf<RoutineResponse>()
    // 오늘이 아닌 다른 날의 루틴 리스트
    val otherDayRoutines = mutableStateListOf<RoutineResponse>()
    
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    init {
        // 앱 실행 시 오프라인 저장소에서 데이터 로드
        refreshLists()
        // 백그라운드 동기화 작업 시작
        startBackgroundSync()
    }

    /**
     * 현재 상태(완료 여부 등)를 반영하여 UI 리스트를 다시 분류합니다.
     */
    fun refreshLists() {
        val offline = AppPreferences.getOfflineRoutines()
        updateLists(offline)
    }

    /**
     * 현재 요일을 한국어(월, 화...)로 반환합니다.
     */
    private fun getTodayKorean(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "일"
            Calendar.MONDAY -> "월"
            Calendar.TUESDAY -> "화"
            Calendar.WEDNESDAY -> "수"
            Calendar.THURSDAY -> "목"
            Calendar.FRIDAY -> "금"
            Calendar.SATURDAY -> "토"
            else -> ""
        }
    }

    /**
     * 서버나 로컬에서 받은 전체 루틴 리스트를 상태(PENDING, APPROVED)와 
     * 요일, 시간에 따라 적절한 UI 리스트로 분류합니다.
     */
    private fun updateLists(routines: List<RoutineResponse>) {
        pendingRoutines.clear()
        todayToDoRoutines.clear()
        todayCompletedRoutines.clear()
        otherDayRoutines.clear()
        
        val today = getTodayKorean()
        val now = java.time.LocalTime.now()

        routines.forEach { routine ->
            if (routine.status == "PENDING") {
                pendingRoutines.add(routine)
            } else if (routine.status == "APPROVED") {
                val isToday = routine.alarm_days.split(",").contains(today)
                val isCompletedManually = AppPreferences.isRoutineCompleted(routine.id)
                
                // 루틴 시간이 현재 시간보다 지났는지 확인
                val isPastTime = try {
                    val routineTime = java.time.LocalTime.parse(routine.alarm_time)
                    now.isAfter(routineTime)
                } catch (e: Exception) {
                    false
                }

                if (isToday) {
                    // 이미 완료했거나 시간이 지났으면 완료 목록으로, 아니면 할 일 목록으로 분류
                    if (isCompletedManually || isPastTime) {
                        todayCompletedRoutines.add(routine)
                    } else {
                        todayToDoRoutines.add(routine)
                        // 활성화된 루틴만 알람 예약
                        if (routine.is_active) {
                            AlarmScheduler.scheduleRoutineAlarm(getApplication(), routine)
                        } else {
                            AlarmScheduler.cancelAlarm(getApplication(), routine.id)
                        }
                    }
                } else {
                    otherDayRoutines.add(routine)
                    // 다른 날 루틴도 활성화 상태면 알람 예약 (AlarmScheduler가 날짜 계산)
                    if (routine.is_active) {
                        AlarmScheduler.scheduleRoutineAlarm(getApplication(), routine)
                    } else {
                        AlarmScheduler.cancelAlarm(getApplication(), routine.id)
                    }
                }
            }
        }
    }

    /**
     * 서버로부터 최신 루틴 목록을 가져옵니다.
     */
    fun fetchRoutines() {
        val userId = AppPreferences.userId ?: return
        isLoading = true
        errorMessage = null
        
        viewModelScope.launch {
            try {
                val response = NetworkClient.routineApiService.getRoutines(userId)
                if (response.isSuccessful) {
                    val serverRoutines = response.body() ?: emptyList()
                    // 서버 데이터를 로컬에 캐시하고 UI 갱신
                    AppPreferences.saveRoutines(serverRoutines)
                    val mergedRoutines = AppPreferences.getOfflineRoutines()
                    updateLists(mergedRoutines)
                } else {
                    errorMessage = "Failed to load: ${response.code()}"
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 새로운 루틴을 서버에 저장합니다.
     */
    fun saveRoutine(content: String, time: String, days: List<String>, type: String = "normal") {
        viewModelScope.launch {
            try {
                val request = RoutineRequest(
                    alarm_time = time,
                    alarm_days = days,
                    alarm_content = content,
                    type = type
                )
                val response = NetworkClient.routineApiService.saveRoutine(request)
                if (response.isSuccessful) {
                    fetchRoutines() // 저장 후 목록 새로고침
                }
            } catch (e: Exception) {
                errorMessage = "Save Error: ${e.message}"
            }
        }
    }

    /**
     * 대기 중인 루틴을 승인하거나 거절합니다.
     */
    fun handlePending(routine: RoutineResponse, approve: Boolean) {
        val newStatus = if (approve) "APPROVED" else "REJECTED"
        viewModelScope.launch {
            try {
                val response = NetworkClient.routineApiService.patchRoutine(
                    RoutinePatchRequest(routine_id = routine.id, status = newStatus)
                )
                if (response.isSuccessful) {
                    fetchRoutines()
                }
            } catch (e: Exception) {
                errorMessage = "Update Error: ${e.message}"
            }
        }
    }

    /**
     * 루틴의 활성화 상태를 켜거나 끕니다.
     */
    fun toggleRoutineActive(routine: RoutineResponse, active: Boolean) {
        AppPreferences.setRoutineActive(routine.id, active)
        
        // UI 리스트에서 상태 즉시 업데이트
        updateLocalRoutineStatus(routine.id, active)

        // 알람 즉시 설정 또는 취소
        if (active) {
            AlarmScheduler.scheduleRoutineAlarm(getApplication(), routine.copy(is_active = true))
        } else {
            AlarmScheduler.cancelAlarm(getApplication(), routine.id)
        }
        
        // 변경된 상태를 로컬 저장소에 반영
        val all = todayToDoRoutines + todayCompletedRoutines + otherDayRoutines + pendingRoutines
        AppPreferences.saveRoutines(all)
    }

    /**
     * 로컬 메모리 리스트에 있는 특정 루틴의 활성화 상태를 동기화합니다.
     */
    private fun updateLocalRoutineStatus(id: String, active: Boolean) {
        fun updateInList(list: MutableList<RoutineResponse>) {
            val idx = list.indexOfFirst { it.id == id }
            if (idx != -1) {
                list[idx] = list[idx].copy(is_active = active)
            }
        }
        updateInList(todayToDoRoutines)
        updateInList(todayCompletedRoutines)
        updateInList(otherDayRoutines)
    }

    /**
     * 기존 루틴의 내용을 수정합니다.
     */
    fun updateRoutine(id: String, content: String, time: String, days: List<String>, type: String? = null) {
        // 루틴 수정 시 완료 기록을 삭제하여 다시 울릴 수 있게 함
        AppPreferences.clearRoutineCompletion(id)
        
        viewModelScope.launch {
            try {
                val response = NetworkClient.routineApiService.patchRoutine(
                    RoutinePatchRequest(
                        routine_id = id,
                        alarm_content = content,
                        alarm_time = time,
                        alarm_days = days,
                        type = type
                    )
                )
                if (response.isSuccessful) {
                    fetchRoutines()
                } else {
                    // 서버 실패 시에도 로컬 리스트는 갱신하여 상태 반영
                    refreshLists()
                }
            } catch (e: Exception) {
                errorMessage = "Update Error: ${e.message}"
                refreshLists()
            }
        }
    }

    /**
     * WorkManager를 사용하여 주기적으로 서버와 루틴 데이터를 동기화합니다.
     */
    private fun startBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = androidx.work.OneTimeWorkRequestBuilder<RoutineSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(3, TimeUnit.MINUTES)
            .addTag("RoutineSyncSingle")
            .build()

        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "RoutineSyncSingle",
            androidx.work.ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}
