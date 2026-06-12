package com.example.test2

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.time.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(viewModel: VideoViewModel) {
    val context = LocalContext.current
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.fetchVideos() }

    // 필터 변경 시 자동 새로고침 (계속 불러와줘 반영)
    LaunchedEffect(viewModel.startDate, viewModel.startTime, viewModel.endDate, viewModel.endTime) {
        if (viewModel.isFilterActive) {
            viewModel.fetchVideos()
        }
    }

    LaunchedEffect(viewModel.currentVideoUrl) {
        viewModel.currentVideoUrl?.let { url ->
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.play()
        } ?: run {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    if (viewModel.isFullscreen) {
        LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        HideSystemUI(hide = true)
    } else {
        LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        HideSystemUI(hide = false)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!viewModel.isFullscreen) {
                val cameras = listOf("cam1", "cam2")
                SecondaryTabRow(
                    selectedTabIndex = cameras.indexOf(viewModel.selectedCamera),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    cameras.forEachIndexed { index, cam ->
                        Tab(
                            selected = viewModel.selectedCamera == cam,
                            onClick = { viewModel.onCameraSelected(cam) },
                            text = { Text("카메라 ${index + 1}") },
                        )
                    }
                }
            }

            Box(
                modifier = if (viewModel.isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(250.dp),
                contentAlignment = Alignment.Center
            ) {
                if (viewModel.isLiveMode) {
                    LiveStreamView(url = "${NetworkClient.VOD_URL}api/live/${viewModel.selectedCamera}")
                } else if (viewModel.currentVideoUrl != null) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.player = exoPlayer }
                    )
                } else {
                    Text("영상을 선택하거나 LIVE를 눌러주세요", color = Color.White)
                }
                
                IconButton(
                    onClick = { viewModel.isFullscreen = !viewModel.isFullscreen },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(if (viewModel.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }

            if (!viewModel.isFullscreen) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        // 기간 필터 영역
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = viewModel.startDate != null,
                                    onClick = { showStartPicker = true },
                                    label = { Text(formatPeriod(viewModel.startDate, viewModel.startTime, "시작 일시")) },
                                    leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                FilterChip(
                                    selected = viewModel.endDate != null,
                                    onClick = { showEndPicker = true },
                                    label = { Text(formatPeriod(viewModel.endDate, viewModel.endTime, "종료 일시")) },
                                    leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                                
                                IconButton(
                                    onClick = {
                                        viewModel.startDate = null; viewModel.startTime = null
                                        viewModel.endDate = null; viewModel.endTime = null
                                    },
                                    enabled = viewModel.startDate != null || viewModel.endDate != null
                                ) {
                                    Icon(
                                        Icons.Default.FilterListOff, 
                                        contentDescription = "초기화",
                                        tint = if (viewModel.startDate != null || viewModel.endDate != null) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                }
                            }
                        }

                        val pullRefreshState = rememberPullToRefreshState()
                        PullToRefreshBox(
                            isRefreshing = viewModel.isRefreshing,
                            onRefresh = { viewModel.fetchVideos(isRefresh = true) },
                            state = pullRefreshState,
                            modifier = Modifier.weight(1f)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Button(
                                        onClick = { viewModel.toggleLive() },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (viewModel.isLiveMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.LiveTv, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (viewModel.isLiveMode) "LIVE 중지" else "실시간 LIVE 보기", fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (!viewModel.isFilterActive) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("날짜를 선택하여 영상을 검색해보세요", color = Color.Gray)
                                        }
                                    }
                                } else if (viewModel.videoList.isEmpty() && !viewModel.isLoading) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                            Text("검색 결과가 없습니다", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    items(viewModel.videoList) { video ->
                                        VideoListItem(video) { viewModel.playVideo(video.filename) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStartPicker) {
        DateTimePickerDialog(
            initialDate = viewModel.startDate ?: LocalDate.now(),
            initialTime = viewModel.startTime ?: LocalTime.of(0, 0),
            onDismiss = { showStartPicker = false },
            onConfirm = { date, time ->
                viewModel.startDate = date
                viewModel.startTime = time
                showStartPicker = false
            }
        )
    }

    if (showEndPicker) {
        DateTimePickerDialog(
            initialDate = viewModel.endDate ?: LocalDate.now(),
            initialTime = viewModel.endTime ?: LocalTime.now(),
            onDismiss = { showEndPicker = false },
            onConfirm = { date, time ->
                viewModel.endDate = date
                viewModel.endTime = time
                showEndPicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initialDate: LocalDate,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalTime) -> Unit
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    var selectedHour by remember { mutableIntStateOf(initialTime.hour) }
    var selectedMinute by remember { mutableIntStateOf(initialTime.minute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val date = datePickerState.selectedDateMillis?.let { 
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: initialDate
                onConfirm(date, LocalTime.of(selectedHour, selectedMinute))
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DatePicker(state = datePickerState, showModeToggle = false)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberWheelPicker(range = 0..23, initialValue = selectedHour, onValueChange = { selectedHour = it })
                    Text(":", modifier = Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Bold)
                    NumberWheelPicker(range = 0..59, initialValue = selectedMinute, onValueChange = { selectedMinute = it })
                }
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    )
}

fun formatPeriod(date: LocalDate?, time: LocalTime?, placeholder: String): String {
    if (date == null || time == null) return placeholder
    return "${date.toString().substring(5)} ${String.format(Locale.US, "%02d:%02d", time.hour, time.minute)}"
}

@Composable
fun LiveStreamView(url: String, modifier: Modifier = Modifier) {
    key(url) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    webViewClient = WebViewClient()
                    settings.apply {
                        javaScriptEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                    loadUrl(url)
                }
            },
            modifier = modifier
        )
    }
}

@Composable
fun HideSystemUI(hide: Boolean) {
    val context = LocalContext.current
    SideEffect {
        val activity = context.findActivity() ?: return@SideEffect
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun LockScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = originalOrientation }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun VideoListItem(video: VideoItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(video.filename, style = MaterialTheme.typography.bodyLarge)
                val size = String.format(Locale.getDefault(), "%.2f MB", video.size.toDouble() / (1024 * 1024))
                Text(size, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
