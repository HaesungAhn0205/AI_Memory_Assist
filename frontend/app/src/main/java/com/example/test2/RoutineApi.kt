package com.example.test2

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

data class RoutineResponse(
    val id: String,
    val user_id: String,
    @SerializedName("alarm_time")
    val alarm_time: String, // "HH:mm:ss"
    @SerializedName("alarm_days")
    val alarm_days: String, // "월,화,금"
    @SerializedName("alarm_content")
    val alarm_content: String,
    val status: String, // "PENDING", "APPROVED", "REJECTED"
    val type: String? = null, // "window", "medicine", "normal" 등
    
    // 앱 내부적으로만 사용할 필드 (JSON 직렬화에서 제외하거나 서버에서 무시됨)
    @Transient
    var is_active: Boolean = true
)

data class RoutineCheckResponse(
    val result: String,
    val category: String,
    val user_id: String,
    @SerializedName("has_taken_medicine")
    val hasTakenMedicine: Boolean? = null,
    val windows: List<WindowStatus>? = null
)

data class WindowStatus(
    val device_name: String,
    val label: String,
    val status: String
)

data class RoutineRequest(
    val alarm_time: String,
    val alarm_days: List<String>,
    val alarm_content: String,
    val type: String = "normal"
)

data class RoutinePatchRequest(
    @SerializedName("routine_id")
    val routine_id: String,
    @SerializedName("alarm_time")
    val alarm_time: String? = null,
    @SerializedName("alarm_days")
    val alarm_days: List<String>? = null,
    @SerializedName("alarm_content")
    val alarm_content: String? = null,
    val status: String? = null,
    val type: String? = null
)

interface RoutineApiService {
    @GET("api/v1/routine")
    suspend fun getRoutines(
        @Query("user_id") userId: String
    ): Response<List<RoutineResponse>>

    @GET("api/v1/routine/check")
    suspend fun checkRoutine(
        @Query("category") category: String,
        @Query("user_id") userId: String
    ): Response<RoutineCheckResponse>

    @POST("api/v1/routine")
    suspend fun saveRoutine(
        @Body request: RoutineRequest
    ): Response<Unit>

    @PATCH("api/v1/routine")
    suspend fun patchRoutine(
        @Body request: RoutinePatchRequest
    ): Response<Unit>
}
