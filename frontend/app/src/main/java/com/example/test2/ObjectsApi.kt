package com.example.test2

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

data class ObjectDetectionResponse(
    val result: String,
    val user_id: String,
    val device_states: Map<String, RoomState>
)

data class RoomState(
    val device_id: String,
    val updated_at: String,
    val detected_objects: List<DetectedObject>
)

data class DetectedObject(
    val label: String,
    val status: String,
    val bbox: String
)

interface ObjectsApiService {
    @GET("api/v1/edge/objects/latest")
    suspend fun getLatestObjects(
        @Query("user_id") userId: String
    ): Response<ObjectDetectionResponse>
}
