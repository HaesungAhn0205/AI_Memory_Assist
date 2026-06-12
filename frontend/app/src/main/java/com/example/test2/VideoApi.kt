package com.example.test2

import retrofit2.http.GET
import retrofit2.http.Path

// 데이터 모델
data class VideoListResponse(
    val status: String,
    val data: List<VideoItem>
)

data class VideoItem(
    val filename: String,
    val size: Long
)

// API 인터페이스
interface VideoApiService {
    @GET("api/videos/{cam_name}")
    suspend fun getVideoList(@Path("cam_name") camName: String): VideoListResponse
}
