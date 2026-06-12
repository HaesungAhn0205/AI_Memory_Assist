package com.example.test2

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

data class ChatMessage(
    val role: String,
    var content: String,
    var images: Map<String, String>? = null, // key: location, value: imageBase64
    var intent: String? = null,
    var retrievedContext: String? = null,
    var currentContext: String? = null
)

// OpenAI 표준 Chat Completion Chunk 형식
data class ChatResponseChunk(
    val id: String,
    val choices: List<ChoiceChunk>
)

data class ChoiceChunk(
    val delta: DeltaChunk,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class DeltaChunk(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    @SerializedName("images_map")
    val imagesMap: Map<String, String>? = null,
    val intent: String? = null,
    @SerializedName("retrieved_context")
    val retrievedContext: String? = null,
    @SerializedName("current_context")
    val currentContext: String? = null
)

interface ChatApiService {
    @Streaming
    @GET("api/v1/chat/query")
    suspend fun queryQuestionStream(
        @Query("user_id") userId: String,
        @Query("user_question") question: String
    ): Response<ResponseBody>
}
