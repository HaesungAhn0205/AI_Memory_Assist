package com.example.test2

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

import androidx.lifecycle.compose.LifecycleResumeEffect

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RoutineScreen(viewModel: RoutineViewModel = viewModel()) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRoutine by remember { mutableStateOf<RoutineResponse?>(null) }

    // 화면이 다시 보일 때마다(예: 알람 종료 후 복귀) 데이터 새로고침
    LifecycleResumeEffect(Unit) {
        viewModel.fetchRoutines()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("오늘일과", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "루틴 추가")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. AI 추천 루틴
            if (viewModel.pendingRoutines.isNotEmpty()) {
                item {
                    Text(
                        "AI 추천 루틴",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(viewModel.pendingRoutines) { routine ->
                    PendingRoutineItem(
                        routine = routine,
                        onApprove = { viewModel.handlePending(routine, true) },
                        onReject = { viewModel.handlePending(routine, false) }
                    )
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            }

            // 1. 오늘 해야할 루틴
            if (viewModel.todayToDoRoutines.isNotEmpty()) {
                item {
                    Text("오늘 해야할 루틴", style = MaterialTheme.typography.titleMedium)
                }
                items(viewModel.todayToDoRoutines) { routine ->
                    RoutineAlarmItem(
                        routine = routine,
                        onToggle = { viewModel.toggleRoutineActive(routine, it) },
                        onLongClick = { editingRoutine = routine }
                    )
                }
            }

            // 2. 오늘 완료된 루틴
            if (viewModel.todayCompletedRoutines.isNotEmpty()) {
                item {
                    Text("오늘 완료된 루틴", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                }
                items(viewModel.todayCompletedRoutines) { routine ->
                    RoutineAlarmItem(
                        routine = routine,
                        isCompleted = true,
                        onToggle = { viewModel.toggleRoutineActive(routine, it) },
                        onLongClick = { editingRoutine = routine }
                    )
                }
            }

            // 3. 다른 요일에 루틴
            if (viewModel.otherDayRoutines.isNotEmpty()) {
                item {
                    Text("다른 요일의 루틴", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
                items(viewModel.otherDayRoutines) { routine ->
                    RoutineAlarmItem(
                        routine = routine,
                        onToggle = { viewModel.toggleRoutineActive(routine, it) },
                        onLongClick = { editingRoutine = routine }
                    )
                }
            }
            
            if (viewModel.todayToDoRoutines.isEmpty() && 
                viewModel.todayCompletedRoutines.isEmpty() && 
                viewModel.otherDayRoutines.isEmpty() && 
                !viewModel.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("등록된 루틴이 없습니다.", color = Color.Gray)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddRoutineDialog(
            onDismiss = { showAddDialog = false },
            onSave = { content, time, days, type ->
                viewModel.saveRoutine(content, time, days, type)
                showAddDialog = false
            }
        )
    }

    if (editingRoutine != null) {
        AddRoutineDialog(
            routine = editingRoutine,
            onDismiss = { editingRoutine = null },
            onSave = { content, time, days, type ->
                viewModel.updateRoutine(editingRoutine!!.id, content, time, days, type)
                editingRoutine = null
            }
        )
    }
}

@Composable
fun PendingRoutineItem(routine: RoutineResponse, onApprove: () -> Unit, onReject: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("AI 추천: ${routine.alarm_content}", fontWeight = FontWeight.Bold)
                Text("${routine.alarm_time} | ${routine.alarm_days}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onApprove) { Icon(Icons.Default.Check, contentDescription = "Accept", tint = Color(0xFF4CAF50)) }
            IconButton(onClick = onReject) { Icon(Icons.Default.Close, contentDescription = "Reject", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun RoutineAlarmItem(
    routine: RoutineResponse, 
    isCompleted: Boolean = false,
    onToggle: (Boolean) -> Unit, 
    onLongClick: () -> Unit
) {
    val allDays = listOf("일", "월", "화", "수", "목", "금", "토")
    val routineDays = routine.alarm_days.split(",")

    val contentColor = if (isCompleted) Color.Gray else if (routine.is_active) MaterialTheme.colorScheme.primary else Color.Gray
    val timeColor = if (isCompleted) Color.Gray else if (routine.is_active) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isCompleted) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else if (routine.is_active) {
                MaterialTheme.colorScheme.surfaceVariant 
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = routine.alarm_content, 
                        style = MaterialTheme.typography.titleMedium, 
                        color = contentColor,
                        textDecoration = if (isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = routine.alarm_time.take(5), 
                        fontSize = 32.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = timeColor
                    )
                }
                Switch(checked = routine.is_active, onCheckedChange = onToggle) 
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                allDays.forEach { day ->
                    val isSelected = routineDays.contains(day)
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(
                            if (isSelected && !isCompleted && routine.is_active) MaterialTheme.colorScheme.primary 
                            else if (isSelected) Color.Gray.copy(alpha = 0.3f) 
                            else Color.Transparent
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = day, 
                            style = MaterialTheme.typography.labelMedium, 
                            color = if (isSelected && !isCompleted && routine.is_active) MaterialTheme.colorScheme.onPrimary 
                                    else if (isSelected) Color.White 
                                    else Color.Gray, 
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRoutineDialog(routine: RoutineResponse? = null, onDismiss: () -> Unit, onSave: (String, String, List<String>, String) -> Unit) {
    var content by remember { mutableStateOf(routine?.alarm_content ?: "") }
    var selectedHour by remember { mutableIntStateOf(routine?.alarm_time?.take(2)?.toIntOrNull() ?: 8) }
    var selectedMin by remember { mutableIntStateOf(routine?.alarm_time?.substring(3, 5)?.toIntOrNull() ?: 0) }
    var selectedType by remember { mutableStateOf(routine?.type ?: "normal") }
    
    val daysOfWeek = listOf("월", "화", "수", "목", "금", "토", "일")
    val routineTypes = listOf("normal" to "일반", "medicine" to "약", "window" to "창문")
    val selectedDays = remember { 
        mutableStateListOf<String>().apply {
            routine?.alarm_days?.split(",")?.let { addAll(it.filter { it.isNotBlank() }) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(24.dp).fillMaxWidth(),
        content = {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(text = if (routine == null) "새 루틴 추가" else "루틴 수정", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("내용 (예: 약 먹기)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("카테고리", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        routineTypes.forEach { (code, label) ->
                            FilterChip(
                                selected = selectedType == code,
                                onClick = { selectedType = code },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        NumberWheelPicker(range = 0..23, initialValue = selectedHour, onValueChange = { selectedHour = it })
                        Text(":", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                        NumberWheelPicker(range = 0..59, initialValue = selectedMin, onValueChange = { selectedMin = it })
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("요일 설정", style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { selectedDays.clear(); selectedDays.addAll(daysOfWeek) }) { Text("전체", fontSize = 12.sp) }
                            TextButton(onClick = { selectedDays.clear() }) { Text("해제", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        daysOfWeek.forEach { day ->
                            val isSelected = selectedDays.contains(day)
                            FilterChip(
                                selected = isSelected,
                                onClick = { if (isSelected) selectedDays.remove(day) else selectedDays.add(day) },
                                label = { Text(day, fontSize = 10.sp) },
                                shape = CircleShape,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("취소") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onSave(content, String.format(Locale.US, "%02d:%02d:00", selectedHour, selectedMin), selectedDays.toList(), selectedType) }, enabled = content.isNotBlank() && selectedDays.isNotEmpty()) { Text("저장") }
                    }
                }
            }
        }
    )
}
