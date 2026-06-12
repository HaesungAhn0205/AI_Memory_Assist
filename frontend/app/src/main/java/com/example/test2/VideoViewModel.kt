package com.example.test2

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * VOD(녹화 영상) 목록 조회 및 비디오 플레이어 상태를 관리하는 ViewModel입니다.
 */
class VideoViewModel : ViewModel() {
    // 서버로부터 받은 전체 비디오 목록
    private var _videoList = mutableStateListOf<VideoItem>()
    
    // 현재 설정된 필터(날짜/시간)에 따라 필터링된 비디오 목록 반환
    val videoList: List<VideoItem> get() {
        return _videoList.filter { item ->
            val itemDateTime = parseFilename(item.filename) ?: return@filter false
            
            val start = if (startDate != null && startTime != null) {
                java.time.LocalDateTime.of(startDate, startTime)
            } else null
            
            val end = if (endDate != null && endTime != null) {
                java.time.LocalDateTime.of(endDate, endTime)
            } else null
            
            val afterStart = start?.let { itemDateTime.isAfter(it) || itemDateTime.isEqual(it) } ?: true
            val beforeEnd = end?.let { itemDateTime.isBefore(it) || itemDateTime.isEqual(it) } ?: true
            
            afterStart && beforeEnd
        }
    }

    /**
     * 파일명(예: 2024-03-20_14-30-00.mp4)을 LocalDateTime 객체로 파싱합니다.
     */
    private fun parseFilename(filename: String): java.time.LocalDateTime? {
        return try {
            val cleanName = filename.substringBefore(".")
            val parts = cleanName.split("_")
            val date = java.time.LocalDate.parse(parts[0])
            val timeParts = parts[1].split("-")
            val time = java.time.LocalTime.of(timeParts[0].toInt(), timeParts[1].toInt())
            java.time.LocalDateTime.of(date, time)
        } catch (e: Exception) {
            null
        }
    }

    var isLoading by mutableStateOf(value = false)
    var isRefreshing by mutableStateOf(false)
    
    // 검색 기간 필터 상태
    var startDate by mutableStateOf<java.time.LocalDate?>(null)
    var startTime by mutableStateOf<java.time.LocalTime?>(null)
    var endDate by mutableStateOf<java.time.LocalDate?>(null)
    var endTime by mutableStateOf<java.time.LocalTime?>(null)

    // 필터 적용 여부
    val isFilterActive: Boolean
        get() = startDate != null || startTime != null || endDate != null || endTime != null
    
    // 현재 재생 중인 영상의 URL
    var currentVideoUrl by mutableStateOf<String?>(null)
    // 라이브 스트리밍 모드 여부
    var isLiveMode by mutableStateOf(false) 
    
    // 현재 선택된 카메라 (cam1, cam2 등)
    var selectedCamera by mutableStateOf("cam1")
    
    // 영상 전체 화면 모드 여부
    var isFullscreen by mutableStateOf(false)

    /**
     * 서버에서 녹화된 비디오 파일 목록을 가져옵니다.
     */
    fun fetchVideos(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) isRefreshing = true else isLoading = true
            try {
                val response = NetworkClient.videoApiService.getVideoList(selectedCamera)
                AppPreferences.addLog("VOD", "Fetch List ($selectedCamera), Status: ${response.status}")
                if (response.status == "success") {
                    _videoList.clear()
                    _videoList.addAll(response.data)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isRefresh) isRefreshing = false else isLoading = false
            }
        }
    }

    /**
     * 특정 파일을 재생하기 위한 URL을 생성하고 재생 모드로 전환합니다.
     */
    fun playVideo(filename: String) {
        isLiveMode = false
        // VOD 서버(8001 포트) URL 생성
        val url = "${NetworkClient.VOD_URL}api/video/$selectedCamera/$filename"
        currentVideoUrl = url
        AppPreferences.addLog("VOD", "Playing: $filename (8001)")
    }
    
    /**
     * 라이브 모드를 켜거나 끕니다.
     */
    fun toggleLive() {
        if (isLiveMode) {
            isLiveMode = false
            AppPreferences.addLog("LIVE", "Live Mode OFF")
        } else {
            isLiveMode = true
            currentVideoUrl = null // VOD 재생 중지
            AppPreferences.addLog("LIVE", "Live Mode ON: $selectedCamera")
        }
    }

    /**
     * 카메라를 변경하고 해당 카메라의 라이브 또는 목록을 새로고침합니다.
     */
    fun onCameraSelected(camName: String) {
        if (selectedCamera != camName) {
            selectedCamera = camName
            // 카메라 전환 시 기존 상태 초기화 및 라이브 우선 표시
            currentVideoUrl = null
            isLiveMode = true
            _videoList.clear()
            AppPreferences.addLog("CAM", "Selected: $camName")
            fetchVideos()
        }
    }
}
