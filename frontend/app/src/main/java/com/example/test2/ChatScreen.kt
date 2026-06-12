package com.example.test2

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun ChatScreen(viewModel: ChatViewModel, onTimeLinkClick: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    
    // 폰트 크기 조절을 위한 상태 (기본 1.0f)
    var fontScale by remember { mutableFloatStateOf(1.0f) }
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        fontScale = (fontScale * zoomChange).coerceIn(0.7f, 3.0f)
    }
    
    var isVoiceMode by rememberSaveable { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    var isListening by remember { mutableStateOf(false) }
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
        }
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsSpeaking by remember { mutableStateOf(false) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    // Scroll to bottom when new message arrives
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    LaunchedEffect(isTtsSpeaking) {
        if (isTtsSpeaking) {
            while (isTtsSpeaking) {
                audioLevel = (0.2f + (Math.random().toFloat() * 0.8f))
                kotlinx.coroutines.delay(100)
            }
            audioLevel = 0f
        }
    }

    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isTtsSpeaking = true }
                    override fun onDone(utteranceId: String?) {
                        isTtsSpeaking = false
                        if (isVoiceMode) {
                            coroutineScope.launch {
                                isListening = true
                                try { speechRecognizer.startListening(recognizerIntent) } catch (_: Exception) {}
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, TextToSpeech.ERROR)"))
                    override fun onError(utteranceId: String?) {
                        isTtsSpeaking = false
                        isListening = false
                    }
                })
            }
        }
    }

    fun convertNumberToKorean(numStr: String): String {
        val num = numStr.toIntOrNull() ?: return numStr
        if (num == 0) return "영"
        
        val units = listOf("", "십", "백", "천", "만")
        val digits = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        
        var result = ""
        var n = num
        var unitIdx = 0
        
        while (n > 0) {
            val d = n % 10
            if (d > 0) {
                val digitStr = if (d == 1 && unitIdx > 0 && unitIdx < 4) "" else digits[d]
                result = digitStr + units[unitIdx] + result
            }
            n /= 10
            unitIdx++
        }
        return result
    }

    fun cleanTextForSpeech(text: String): String {
        // 1. [time/]...[/time] 태그 파싱 및 읽기 좋은 텍스트로 변환
        val timeRegex = Regex("\\[time/\\]\\s*([^\\s\\[]+)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s*\\[/time\\]")
        var cleaned = text.replace(timeRegex) { match ->
            val location = match.groupValues[1]
            val date = match.groupValues[2]
            val time = match.groupValues[3]
            
            val year = date.substring(2, 4)
            val month = date.substring(5, 7).toInt().toString()
            val day = date.substring(8, 10).toInt().toString()
            val hour = time.substring(0, 2).toInt().toString()
            
            "${year}년 ${month}월 ${day}일 ${hour}시 $location"
        }

        // 2. 온전하지 않거나 불필요한 남은 태그만 제거
        cleaned = cleaned.replace("[time/]", "").replace("[/time]", "")

        // 3. 기존 특수문자 및 날짜 한글화 클리닝
        cleaned = cleaned.replace(Regex("[*#_~`>]"), "")
        cleaned = cleaned.replace(Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})"), "$1년 $2월 $3일")
        cleaned = cleaned.replace(Regex("(\\d{4})년")) { matchResult ->
            val year = matchResult.groupValues[1]
            "${convertNumberToKorean(year)}년"
        }
        cleaned = cleaned.replace(Regex("(\\d{1,2})월")) { matchResult ->
            val monthInKorean = when(val month = matchResult.groupValues[1]) {
                "6" -> "유" 
                "10" -> "시"
                else -> convertNumberToKorean(month)
            }
            "${monthInKorean}월"
        }
        cleaned = cleaned.replace(Regex("(\\d{1,2})일")) { matchResult ->
            val day = matchResult.groupValues[1]
            "${convertNumberToKorean(day)}일"
        }
        return cleaned.trim()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isListening = true
            speechRecognizer.startListening(recognizerIntent)
        }
    }

    fun onSendMessage(text: String, fromVoice: Boolean = false) {
        if (text.isNotBlank()) {
            val currentVoiceMode = isVoiceMode || fromVoice
            viewModel.inputText = ""
            focusManager.clearFocus()
            viewModel.sendMessage(text) { accumulatedAnswer ->
                if (currentVoiceMode) {
                    val cleanResponse = cleanTextForSpeech(accumulatedAnswer)
                    tts?.speak(cleanResponse, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { 
                isListening = false 
                audioLevel = 0f
            }
            override fun onError(error: Int) { 
                isListening = false 
                audioLevel = 0f
            }
            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                data?.firstOrNull()?.let { spokenText ->
                    onSendMessage(spokenText, fromVoice = true)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.destroy()
            tts?.stop()
            tts?.shutdown()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (isVoiceMode) {
            DynamicVoiceUI(
                isListening = isListening,
                isSpeaking = isTtsSpeaking,
                audioLevel = audioLevel,
                isLoading = viewModel.isLoading,
                onMicClick = {
                    if (!viewModel.isLoading && !isTtsSpeaking) {
                        if (isListening) speechRecognizer.stopListening()
                        else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onExit = { 
                    isVoiceMode = false 
                    tts?.stop()
                    speechRecognizer.stopListening()
                }
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "ARU",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { isVoiceMode = true }) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice Mode", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // Chat Messages
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .transformable(state = transformableState),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(viewModel.messages) { message ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + expandVertically()
                        ) {
                            MessageItem(
                                message = message,
                                fontScale = fontScale,
                                onImageClick = { selectedImageBase64 = it },
                                onTimeLinkClick = onTimeLinkClick
                            )
                        }
                    }
                    
                    if (viewModel.isLoading) {
                        item {
                            TypingIndicator()
                        }
                    }
                }

                // Input Area
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .imePadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = viewModel.inputText,
                            onValueChange = { viewModel.inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("무엇이든 물어보세요...") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            enabled = !viewModel.isLoading,
                            leadingIcon = {
                                IconButton(
                                    onClick = {
                                        if (isListening) speechRecognizer.stopListening()
                                        else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                ) {
                                    Icon(
                                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = "Voice Input",
                                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        FilledIconButton(
                            onClick = { onSendMessage(viewModel.inputText) },
                            enabled = !viewModel.isLoading && viewModel.inputText.isNotBlank(),
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }

    if (selectedImageBase64 != null) {
        ImageDetailDialog(
            imageBase64 = selectedImageBase64!!,
            onDismiss = { selectedImageBase64 = null }
        )
    }
}

@Composable
fun parseTimeTags(text: String, isUser: Boolean): AnnotatedString {
    // 유연한 정규식: [time/] 뒤에 오는 장소와 날짜/시간만 캡처. 불완전한 태그는 무시됨.
    val regex = Regex("\\[time/\\]\\s*([^\\s\\[]+)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s*\\[/time\\]")
    val matches = regex.findAll(text)
    
    return buildAnnotatedString {
        var lastIndex = 0
        matches.forEach { match ->
            append(text.substring(lastIndex, match.range.first))
            
            val location = match.groupValues[1]
            val date = match.groupValues[2]
            val time = match.groupValues[3]
            
            // 날짜 및 시간 포맷팅
            val year = date.substring(2, 4)
            val month = date.substring(5, 7).toInt().toString()
            val day = date.substring(8, 10).toInt().toString()
            val hour = time.substring(0, 2).toInt().toString()
            
            val displayLink = "${year}년 ${month}월 ${day}일 ${hour}시 $location"
            
            pushStringAnnotation("TIME_LINK", "$location|$date $time")
            withStyle(style = SpanStyle(
                color = if (isUser) Color.Cyan else Color.Blue,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Bold
            )) {
                append(displayLink)
            }
            pop()
            
            lastIndex = match.range.last + 1
        }
        append(text.substring(lastIndex))
    }
}

@Composable
fun MessageItem(
    message: ChatMessage,
    fontScale: Float = 1.0f,
    onImageClick: (String) -> Unit = {},
    onTimeLinkClick: (String, String) -> Unit = { _, _ -> }
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    
    // ... 기존 코드와 동일
    
    // Don't show empty assistant bubbles while loading
    if (!isUser && !isSystem && message.content.isBlank() && message.images.isNullOrEmpty() && message.intent == null) {
        return
    }

    if (isSystem) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                tonalElevation = if (isUser) 0.dp else 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    // Context Information (Retrieved Memory & Current Observation)
                    if (!isUser && AppPreferences.showChatContext && (message.retrievedContext != null || message.currentContext != null)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (message.intent != null) {
                                    Text(
                                        text = "Intent: ${message.intent}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                if (message.retrievedContext != null) {
                                    Text(
                                        text = "Memory:\n${message.retrievedContext}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (message.currentContext != null) Spacer(modifier = Modifier.height(4.dp))
                                }
                                if (message.currentContext != null) {
                                    Text(
                                        text = "Observation:\n${message.currentContext}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (message.content.isNotBlank()) {
                        val annotatedText = parseTimeTags(message.content, isUser)
                        
                        ClickableText(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                lineHeight = (22 * fontScale).sp,
                                fontSize = (16 * fontScale).sp,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            onClick = { offset ->
                                annotatedText.getStringAnnotations("TIME_LINK", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        val parts = annotation.item.split("|")
                                        if (parts.size == 2) {
                                            onTimeLinkClick(parts[0], parts[1])
                                        }
                                    }
                            }
                        )
                    }
                    
                    message.images?.forEach { (location, base64) ->
                        val imageBitmap = remember(base64) {
                            try {
                                val cleanBase64 = if (base64.contains(",")) base64.substringAfter(",") else base64
                                val decodedString = Base64.decode(cleanBase64, Base64.DEFAULT)
                                if (decodedString.isNotEmpty()) {
                                    BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)?.asImageBitmap()
                                } else null
                            } catch (_: Exception) {
                                null
                            }
                        }

                        if (imageBitmap != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column {
                                Text(
                                    text = "[$location]",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "Detection Result at $location",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 250.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageClick(base64) },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageDetailDialog(
    imageBase64: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(imageBase64) {
        try {
            val cleanBase64 = if (imageBase64.contains(",")) imageBase64.substringAfter(",") else imageBase64
            val decodedString = Base64.decode(cleanBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
        } catch (_: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Detail Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onDismiss() },
                        contentScale = ContentScale.Fit
                    )
                }

                // Top Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Row {
                        IconButton(
                            onClick = {
                                if (bitmap != null) {
                                    saveImageToGallery(context, bitmap)
                                }
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = {
                                if (bitmap != null) {
                                    shareImage(context, bitmap)
                                }
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

fun saveImageToGallery(context: android.content.Context, bitmap: Bitmap) {
    val filename = "ChatBot_${System.currentTimeMillis()}.jpg"
    var fos: OutputStream? = null
    try {
        context.contentResolver?.also { resolver ->
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
        }
        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            Toast.makeText(context, "Image saved to Gallery", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareImage(context: android.content.Context, bitmap: Bitmap) {
    try {
        val filename = "SharedImage_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Image"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share image: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                repeat(3) { i ->
                    val infiniteTransition = rememberInfiniteTransition()
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = i * 200),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

@Composable
fun DynamicVoiceUI(
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    isLoading: Boolean,
    onMicClick: () -> Unit,
    onExit: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_anim")
    
    val loadingPulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loading_pulse"
    )

    val barCount = 20
    val barAnimValues = List(barCount) { index ->
        val timeNoise by infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(400 + (index % 5) * 150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "noise_$index"
        )
        val randomFactor = remember { 0.8f + (Math.random().toFloat() * 1.5f) }
        val isActive = isListening || isSpeaking
        val targetHeight = if (isActive) (audioLevel * timeNoise * randomFactor).coerceIn(0.1f, 2.5f) else 0.05f
        
        animateFloatAsState(
            targetValue = targetHeight,
            animationSpec = spring(dampingRatio = 0.4f, stiffness = 100f),
            label = "bar_$index"
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        IconButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Exit Voice Mode",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = when {
                    isSpeaking -> "Gemma Speaking..."
                    isListening -> "Listening..."
                    isLoading -> "Thinking..."
                    else -> "Voice Mode"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
                color = primaryColor.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(80.dp))
            
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(320.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val innerRadius = 85.dp.toPx()
                    val baseBarLength = 60.dp.toPx()
                    val strokeWidth = 10.dp.toPx()

                    for (i in 0 until barCount) {
                        val angle = (i.toFloat() / barCount) * 2 * Math.PI.toFloat()
                        val barHeight = barAnimValues[i].value * baseBarLength
                        val startX = center.x + cos(angle.toDouble()).toFloat() * innerRadius
                        val startY = center.y + sin(angle.toDouble()).toFloat() * innerRadius
                        val endX = center.x + cos(angle.toDouble()).toFloat() * (innerRadius + barHeight)
                        val endY = center.y + sin(angle.toDouble()).toFloat() * (innerRadius + barHeight)

                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(primaryColor, primaryColor.copy(alpha = 0.2f))
                            ),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = when {
                        isLoading -> MaterialTheme.colorScheme.tertiaryContainer
                        isListening -> MaterialTheme.colorScheme.errorContainer
                        isSpeaking -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier
                        .size(130.dp)
                        .scale(if (isLoading) loadingPulseScale else 1f)
                        .clickable(enabled = !isSpeaking) { onMicClick() },
                    shadowElevation = 8.dp
                ) {
                    Icon(
                        when {
                            isSpeaking -> Icons.AutoMirrored.Filled.VolumeUp
                            isListening -> Icons.Default.Stop
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Mic",
                        modifier = Modifier.padding(36.dp).fillMaxSize(),
                        tint = when {
                            isLoading -> MaterialTheme.colorScheme.tertiary
                            isListening -> MaterialTheme.colorScheme.error
                            else -> primaryColor
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
