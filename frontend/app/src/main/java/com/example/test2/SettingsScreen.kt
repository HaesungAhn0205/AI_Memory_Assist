package com.example.test2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    var urlText by remember { mutableStateOf(AppPreferences.serverHost) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "애플리케이션 설정",
                style = MaterialTheme.typography.headlineSmall
            )

            // 1. 서버 주소 설정
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("네트워크 설정", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("서버 주소 (IP/Domain)") },
                        placeholder = { Text("http://10.10.16.15") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("API는 8000번, VOD/LIVE는 8001번 포트가 자동 할당됩니다.") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                NetworkClient.updateBaseUrl(urlText)
                                scope.launch { snackbarHostState.showSnackbar("서버 주소가 적용되었습니다.") }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("저장")
                        }
                        OutlinedButton(
                            onClick = {
                                val defaultUrl = "http://10.10.16.15:8000/"
                                urlText = defaultUrl
                                NetworkClient.updateBaseUrl(defaultUrl)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("초기화")
                        }
                    }
                }
            }

            // 2. UI 설정
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("채팅 화면 설정", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("의도 및 메모리 표시")
                        Switch(
                            checked = AppPreferences.showChatContext,
                            onCheckedChange = { AppPreferences.updateShowChatContext(it) }
                        )
                    }
                    
                    // 이스터에그 스위치
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("실험적 기능 (알람음 변경)")
                        Switch(
                            checked = AppPreferences.isEasterEggEnabled,
                            onCheckedChange = { AppPreferences.toggleEasterEgg() }
                        )
                    }
                }
            }

            // 4. 계정 설정
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("계정 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("사용자: ${AppPreferences.userName ?: "알 수 없음"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            AppPreferences.logout()
                            onLogout()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("로그아웃")
                    }
                }
            }

            // 3. 로그 확인
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("서버 통신 로그", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { AppPreferences.serverLogs.clear() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(AppPreferences.serverLogs) { log ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = log.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                    Text(
                                        text = "[${log.tag}]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = log.message,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
