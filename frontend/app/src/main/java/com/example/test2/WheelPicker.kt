package com.example.test2

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun NumberWheelPicker(range: IntRange, initialValue: Int, onValueChange: (Int) -> Unit) {
    val items = range.toList()
    val size = items.size
    val virtualCount = 10000 
    val startIndex = (virtualCount / 2) - ((virtualCount / 2) % size) + items.indexOf(initialValue)
    
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val haptic = LocalHapticFeedback.current
    
    val currentIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    
    LaunchedEffect(currentIndex) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onValueChange(items[currentIndex % size])
    }

    Box(modifier = Modifier.width(70.dp).height(120.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(8.dp)))
        
        LazyColumn(
            state = listState,
            flingBehavior = snapFlingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(virtualCount) { index ->
                val value = items[index % size]
                Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    val isSelected = currentIndex == index
                    Text(
                        text = String.format(Locale.US, "%02d", value),
                        fontSize = if (isSelected) 24.sp else 18.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
