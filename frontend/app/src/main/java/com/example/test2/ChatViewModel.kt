package com.example.test2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 채팅 화면의 비즈니스 로직과 데이터를 관리하는 ViewModel입니다.
 * 사용자의 질문을 서버로 전송하고 스트리밍 방식으로 응답을 받아 처리합니다.
 */
class ChatViewModel : ViewModel() {
    // 채팅 메시지 목록 (Compose에서 관찰 가능하도록 mutableStateListOf 사용)
    val messages = mutableStateListOf<ChatMessage>()
    // 서버 응답 대기 상태
    var isLoading by mutableStateOf(false)
    // 디버깅을 위한 마지막 수신 JSON 데이터
    var lastRawJson by mutableStateOf("")
    
    // 사용자가 입력 중인 텍스트
    var inputText by mutableStateOf("")
    
    private val gson = Gson()

    /**
     * 사용자의 메시지를 서버에 전송하고 SSE(Server-Sent Events) 스트림으로 응답을 받습니다.
     */
    fun sendMessage(text: String, onComplete: (String) -> Unit = {}) {
        if (text.isBlank()) return
        val userId = AppPreferences.userId ?: "unknown"

        // 사용자 메시지를 목록에 즉시 추가
        messages.add(ChatMessage("user", text))
        isLoading = true
        AppPreferences.addLog("CHAT", "User: $text")
        
        viewModelScope.launch {
            try {
                // Retrofit을 사용하여 스트리밍 API 호출
                val response = NetworkClient.chatApiService.queryQuestionStream(userId, text)
                AppPreferences.addLog("CHAT", "Request sent, Status: ${response.code()}")
                
                if (response.isSuccessful) {
                    val inputStream = response.body()?.byteStream()
                    val reader = inputStream?.bufferedReader()
                    
                    var botMsg: ChatMessage? = null
                    var accumulatedAnswer = ""

                    // IO 스레드에서 스트림 읽기 작업 수행
                    withContext(Dispatchers.IO) {
                        var line = reader?.readLine()
                        while (line != null) {
                            // "data: "로 시작하는 라인 파싱
                            if (line.startsWith("data: ")) {
                                val jsonStr = line.substring(6).trim()
                                lastRawJson = jsonStr
                                
                                if (jsonStr != "[DONE]") {
                                    try {
                                        // JSON 청크를 객체로 변환
                                        val chunk = gson.fromJson(jsonStr, ChatResponseChunk::class.java)
                                        val delta = chunk.choices.firstOrNull()?.delta
                                        
                                        if (delta != null) {
                                            // UI 업데이트를 위해 메인 스레드로 전환
                                            withContext(Dispatchers.Main) {
                                                val newImages = delta.imagesMap
                                                val newText = delta.content
                                                val newIntent = delta.intent
                                                val newRetrievedContext = delta.retrievedContext
                                                val newCurrentContext = delta.currentContext
                                                
                                                if ((newImages != null || newText != null || newIntent != null || newRetrievedContext != null || newCurrentContext != null)) {
                                                    if (isLoading) isLoading = false
                                                    
                                                    if (botMsg == null) {
                                                        // 첫 번째 청크 수신 시 새로운 메시지 객체 생성 및 추가
                                                        accumulatedAnswer = newText ?: ""
                                                        botMsg = ChatMessage(
                                                            role = "assistant",
                                                            content = accumulatedAnswer,
                                                            images = newImages,
                                                            intent = newIntent,
                                                            retrievedContext = newRetrievedContext,
                                                            currentContext = newCurrentContext
                                                        )
                                                        messages.add(botMsg!!)
                                                    } else {
                                                        // 이후 청크 수신 시 기존 메시지 내용 업데이트
                                                        val index = messages.indexOf(botMsg)
                                                        if (index != -1) {
                                                            var updated = botMsg!!
                                                            if (newImages != null) {
                                                                val combinedImages = (updated.images ?: emptyMap()).toMutableMap()
                                                                combinedImages.putAll(newImages)
                                                                updated = updated.copy(images = combinedImages)
                                                            }
                                                            newText?.let {
                                                                accumulatedAnswer += it
                                                                updated = updated.copy(content = accumulatedAnswer)
                                                            }
                                                            newIntent?.let {
                                                                updated = updated.copy(intent = it)
                                                            }
                                                            newRetrievedContext?.let {
                                                                updated = updated.copy(retrievedContext = it)
                                                            }
                                                            newCurrentContext?.let {
                                                                updated = updated.copy(currentContext = it)
                                                            }

                                                            if (updated !== botMsg) {
                                                                messages[index] = updated
                                                                botMsg = updated
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // 파싱 에러 무시
                                    }
                                }
                            }
                            line = reader?.readLine()
                        }
                    }
                    onComplete(accumulatedAnswer)
                } else {
                    messages.add(ChatMessage("system", "Error: ${response.code()}"))
                }
            } catch (e: Exception) {
                messages.add(ChatMessage("system", "Exception: ${e.message}"))
            } finally {
                isLoading = false
            }
        }
    }
}
