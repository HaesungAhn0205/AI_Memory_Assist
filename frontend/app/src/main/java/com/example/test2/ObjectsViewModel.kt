package com.example.test2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class ObjectItem(
    val label: String,
    val roomName: String,
    val updatedAt: String,
    val status: String
)

class ObjectsViewModel : ViewModel() {
    var objectList = mutableStateListOf<ObjectItem>()
    var roomList = mutableStateListOf<String>()
    var selectedRoom by mutableStateOf("전체")
    
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    fun fetchLatestObjects() {
        val userId = AppPreferences.userId ?: return
        isLoading = true
        errorMessage = null
        
        viewModelScope.launch {
            try {
                val response = NetworkClient.objectsApiService.getLatestObjects(userId)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.result == "success") {
                        val flatList = mutableListOf<ObjectItem>()
                        val rooms = mutableSetOf("전체")
                        
                        body.device_states.forEach { (roomName, roomState) ->
                            rooms.add(roomName)
                            roomState.detected_objects.forEach { obj ->
                                flatList.add(
                                    ObjectItem(
                                        label = obj.label,
                                        roomName = roomName,
                                        updatedAt = roomState.updated_at,
                                        status = if (obj.status.isBlank() || obj.status == "0") "정상" else obj.status
                                    )
                                )
                            }
                        }
                        
                        objectList.clear()
                        objectList.addAll(flatList)
                        
                        roomList.clear()
                        roomList.addAll(rooms.sorted())
                    }
                } else {
                    errorMessage = "Error: ${response.code()}"
                }
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }
    
    val filteredList: List<ObjectItem>
        get() = if (selectedRoom == "전체") objectList else objectList.filter { it.roomName == selectedRoom }
}
