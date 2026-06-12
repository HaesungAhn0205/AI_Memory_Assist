package com.example.test2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.test2.ui.theme.ARUTheme
import kotlinx.coroutines.launch

import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build

import android.provider.Settings
import android.net.Uri
import android.content.Intent

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight

/**
 * ARU (AI Remember You) 앱의 메인 액티비티입니다.
 * 앱의 진입점으로 권한 체크, 로그인 상태 관리 및 메인 UI 구성을 담당합니다.
 */
class MainActivity : ComponentActivity() {
    // 알림 권한 요청을 위한 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SharedPreferences 초기화
        AppPreferences.init(this)
        
        // 다른 앱 위에 그리기(오버레이) 권한 체크 - 알람 화면 표시를 위해 필요
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        
        // 안드로이드 13 이상을 위한 알림 권한 요청
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        enableEdgeToEdge()
        setContent {
            // 시스템 폰트 크기 설정에 관계없이 일관된 UI를 유지하기 위해 fontScale 고정
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 1f)
            ) {
                ARUTheme {
                    // 로그인 상태에 따른 화면 전환
                    var isLoggedIn by remember { mutableStateOf(AppPreferences.userId != null) }

                    if (isLoggedIn) {
                        ARUApp(onLogout = { isLoggedIn = false })
                    } else {
                        LoginScreen(onLoginSuccess = { isLoggedIn = true })
                    }
                }
            }
        }
    }
}

/**
 * 메인 앱 UI 구조를 정의하는 Composable입니다.
 * 하단 네비게이션과 각 기능별 스크린을 포함합니다.
 */
@PreviewScreenSizes
@Composable
fun ARUApp(onLogout: () -> Unit = {}) {
    val destinations = AppDestinations.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val coroutineScope = rememberCoroutineScope()
    
    // 각 기능별 ViewModel 인스턴스 생성
    val chatViewModel: ChatViewModel = viewModel()
    val videoViewModel: VideoViewModel = viewModel()
    val objectsViewModel: ObjectsViewModel = viewModel()
    val routineViewModel: RoutineViewModel = viewModel()

    // 루틴 추가 다이얼로그 표시 상태
    var showAddRoutineDialogFromHome by remember { mutableStateOf(false) }

    // 페이지(탭) 변경 감지하여 해당 화면의 데이터 새로고침
    LaunchedEffect(pagerState.currentPage) {
        when (destinations[pagerState.currentPage]) {
            AppDestinations.OBJECTS -> objectsViewModel.fetchLatestObjects()
            AppDestinations.ROUTINE -> routineViewModel.fetchRoutines()
            else -> {}
        }
    }

    // 전체 화면 모드(영상 재생 등) 여부에 따라 네비게이션 바 표시 여부 결정
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val navSuiteType = if (videoViewModel.isFullscreen) {
        NavigationSuiteType.None 
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }

    // 적응형 레이아웃을 지원하는 네비게이션 스캐폴드
    NavigationSuiteScaffold(
        layoutType = navSuiteType,
        navigationSuiteItems = {
            if (!videoViewModel.isFullscreen) {
                destinations.forEach { destination ->
                    item(
                        icon = { 
                            Icon(
                                imageVector = destination.icon, 
                                contentDescription = destination.label,
                            ) 
                        },
                        label = { Text(destination.label) },
                        selected = pagerState.currentPage == destination.ordinal,
                        onClick = {
                            coroutineScope.launch {
                                // 탭 클릭 시 해당 페이지로 애니메이션 이동
                                pagerState.animateScrollToPage(destination.ordinal)
                            }
                        }
                    )
                }
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            // 영상 전체 화면 시 시스템 패딩 제거
            val finalPadding = if (videoViewModel.isFullscreen) PaddingValues(0.dp) else innerPadding
            
            Box(modifier = Modifier.padding(finalPadding)) {
                // 좌우 스와이프 가능한 탭 레이아웃
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 3,
                    userScrollEnabled = !videoViewModel.isFullscreen 
                ) { page ->
                    when (destinations[page]) {
                        AppDestinations.HOME -> Greeting(
                            onChatAction = { query ->
                                chatViewModel.sendMessage(query)
                                chatViewModel.inputText = ""
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.CHAT.ordinal)
                                }
                            },
                            onVideoAction = { cam, date ->
                                videoViewModel.onCameraSelected(cam)
                                videoViewModel.startDate = date
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.VOD.ordinal)
                                    kotlinx.coroutines.delay(600)
                                    videoViewModel.fetchVideos()
                                }
                            },
                            onRoutineAction = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.ROUTINE.ordinal)
                                    // 루틴 탭으로 이동 후 다이얼로그 표시 (일시적 딜레이)
                                    kotlinx.coroutines.delay(300)
                                    showAddRoutineDialogFromHome = true
                                }
                            }
                        )
                        AppDestinations.CHAT -> ChatScreen(
                            viewModel = chatViewModel,
                            onTimeLinkClick = { location, timeStr ->
                                // 채팅창 내 시간 링크 클릭 시 해당 시점의 VOD로 이동하는 로직
                                val camName = if (location.contains("거실")) "cam1" else "cam2"
                                videoViewModel.onCameraSelected(camName)
                                
                                try {
                                    val parts = timeStr.split(" ")
                                    if (parts.size == 2) {
                                        val date = java.time.LocalDate.parse(parts[0])
                                        val time = java.time.LocalTime.parse(parts[1])
                                        videoViewModel.startDate = date
                                        videoViewModel.startTime = time
                                        videoViewModel.endDate = date
                                        videoViewModel.endTime = time.plusMinutes(1)
                                    }
                                } catch (e: Exception) {
                                    videoViewModel.startDate = null
                                }
                                
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.VOD.ordinal)
                                    kotlinx.coroutines.delay(600)
                                    val filteredList = videoViewModel.videoList
                                    if (filteredList.isNotEmpty()) {
                                        videoViewModel.playVideo(filteredList.first().filename)
                                    }
                                }
                            }
                        )
                        AppDestinations.ROUTINE -> RoutineScreen(viewModel = routineViewModel)
                        AppDestinations.VOD -> VideoScreen(viewModel = videoViewModel)
                        AppDestinations.OBJECTS -> ObjectsScreen(
                            viewModel = objectsViewModel,
                            onObjectClick = { obj ->
                                // 물건 클릭 시 해당 물건의 상태를 묻는 채팅을 자동 전송
                                val query = if (obj.label.contains("창문")) {
                                    val naturalStatus = when(obj.status) {
                                        "열림" -> "열려"
                                        "닫힘" -> "닫혀"
                                        else -> obj.status
                                    }
                                    "${obj.roomName} 창문이 ${naturalStatus} 있어?"
                                } else {
                                    "${obj.label} 현재 어디있어?"
                                }
                                chatViewModel.sendMessage(query)
                                chatViewModel.inputText = ""
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(AppDestinations.CHAT.ordinal)
                                }
                            }
                        )
                        AppDestinations.SETTINGS -> SettingsScreen(onLogout = onLogout)
                    }
                }

                // 홈 화면 튜토리얼을 통해 호출된 루틴 추가 다이얼로그
                if (showAddRoutineDialogFromHome) {
                    AddRoutineDialog(
                        onDismiss = { showAddRoutineDialogFromHome = false },
                        onSave = { content, time, days, type ->
                            routineViewModel.saveRoutine(content, time, days, type)
                            showAddRoutineDialogFromHome = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 앱의 주요 목적지(탭) 정의
 */
enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("사용방법", Icons.AutoMirrored.Filled.HelpOutline),
    ROUTINE("오늘일과", Icons.AutoMirrored.Filled.Assignment),
    CHAT("기억찾기", Icons.Default.AutoAwesome),
    OBJECTS("물건찾기", Icons.Default.LocationOn),
    VOD("영상보기", Icons.Default.PlayCircle),
    SETTINGS("환경설정", Icons.Default.Settings),
}

@Composable
fun Greeting(
    onChatAction: (String) -> Unit,
    onVideoAction: (String, java.time.LocalDate) -> Unit,
    onRoutineAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column {
            Text(
                text = "반가워요, ARU 입니다! 👋",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "AI, Remembers You: 당신의 소중한 기억을 돕습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        HorizontalDivider()

        // 튜토리얼 섹션 1: 챗봇 사용법
        TutorialCard(
            title = "🔍 기억 찾기 (채팅)",
            description = "물건의 위치나 과거의 사건이 궁금할 때 AI에게 물어보세요. 질문을 누르면 바로 물어볼 수 있어요.",
            actions = listOf(
                "내 지갑 어디 있어?" to { onChatAction("내 지갑 어디 있어?") },
                "어제 약을 언제 먹었었지?" to { onChatAction("어제 약을 언제 먹었었지?") }
            )
        )

        // 튜토리얼 섹션 2: 영상 확인법
        TutorialCard(
            title = "📹 영상 확인 (VOD)",
            description = "특정 시간대의 상황을 직접 영상으로 확인하고 싶을 때 사용하세요.",
            actions = listOf(
                "오늘 오전 영상 보기" to { onVideoAction("cam1", java.time.LocalDate.now()) }
            )
        )

        // 튜토리얼 섹션 3: 루틴 관리
        TutorialCard(
            title = "⏰ 루틴 알람",
            description = "매일 반복되는 일과를 등록하면 시간에 맞춰 큰 소리로 알려드려요. '오늘일과' 탭에서 새 루틴을 추가해보세요.",
            actions = listOf(
                "새 루틴 추가하기" to onRoutineAction
            )
        )
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TutorialCard(
    title: String,
    description: String,
    actions: List<Pair<String, () -> Unit>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    actions.forEach { (label, onClick) ->
                        Button(
                            onClick = onClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
