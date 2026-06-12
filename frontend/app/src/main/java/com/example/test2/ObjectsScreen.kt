package com.example.test2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsScreen(
    viewModel: ObjectsViewModel = viewModel(),
    onObjectClick: (ObjectItem) -> Unit = {}
) {
    
    // 초기 로딩은 MainActivity의 LaunchedEffect에서 처리되거나 여기서 1회 수행
    LaunchedEffect(Unit) {
        viewModel.fetchLatestObjects()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("물건 현황", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.fetchLatestObjects() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 필터 칩 (서버에서 받아온 방 목록 기준)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                viewModel.roomList.forEach { room ->
                    FilterChip(
                        selected = viewModel.selectedRoom == room,
                        onClick = { viewModel.selectedRoom = room },
                        label = { Text(room) }
                    )
                }
            }

            if (viewModel.isLoading && viewModel.objectList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (viewModel.filteredList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("표시할 물건이 없습니다.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(viewModel.filteredList) { obj ->
                        ObjectItemCard(obj) {
                            onObjectClick(obj)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ObjectItemCard(obj: ObjectItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getIconForLabel(obj.label),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Surface(
                    color = if (obj.status == "정상") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = obj.status,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (obj.status == "정상") MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Column {
                Text(
                    text = obj.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = obj.roomName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatUpdatedAt(obj.updatedAt),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

fun getIconForLabel(label: String): ImageVector {
    return when (label.lowercase()) {
        "스마트폰", "phone", "cell phone" -> Icons.Default.Smartphone
        "지갑", "wallet" -> Icons.Default.Wallet
        "컵", "cup", "bottle" -> Icons.Default.LocalCafe
        "약통", "medicine", "pill" -> Icons.Default.Medication
        "리모컨", "remote" -> Icons.Default.SettingsRemote
        "차키", "key", "car key" -> Icons.Default.DirectionsCar
        "가방", "bag", "backpack" -> Icons.Default.Backpack
        "안경", "glasses" -> Icons.Default.Visibility
        else -> Icons.Default.Category
    }
}

fun formatUpdatedAt(timestamp: String): String {
    // "2026-06-09 11:40:52" -> "11:40" 또는 "방금 전" 등
    return try {
        timestamp.substring(11, 16)
    } catch (e: Exception) {
        timestamp
    }
}
