package com.example.baering2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 앱 화면과 오버레이 위젯이 같이 쓰는 테마 (갤럭시 배경화면 색 + 다크 모드). */
@Composable
fun BaeringTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content,
    )
}

/**
 * 스위치 테스트 칸의 상단 현황: 마지막으로 바뀐 비트 · GPIO 값 · 현재 8비트 상태.
 * GPIO가 바뀌면 노랗게 깜빡인다. 오버레이 위젯도 같은 화면을 쓴다.
 */
@Composable
fun LastChangeBox(ui: BeaconManager.Ui, modifier: Modifier = Modifier) {
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(ui.flashTick) {
        if (ui.flashTick == 0) return@LaunchedEffect
        flashing = true
        delay(150)
        flashing = false
    }
    val bg by animateColorAsState(
        if (flashing) Color(0xFFFACC15) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(120),
        label = "flash",
    )
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("스위치를 눌러보세요", style = MaterialTheme.typography.bodySmall)
            Text(ui.lastLabel, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (ui.lastMeta.isNotEmpty()) Text(ui.lastMeta, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            ui.lastGpio?.let { g ->
                BitRow(g, Modifier.padding(top = 8.dp))
                Text(
                    "GPIO ${Beacon2Packet.hex2(g)}" + (ui.lastVcc?.let { "  ·  VCC ${Beacon2Packet.hex2(it)} ($it)" } ?: ""),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** GPIO 8비트 램프 (bit7 … bit0). 1 = 채움 */
@Composable
fun BitRow(gpio: Int, modifier: Modifier = Modifier, highlight: Int? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in 7 downTo 0) {
            val on = gpio shr b and 1 == 1
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(
                            when {
                                on -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (on) "1" else "0",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "b$b",
                    fontSize = 9.sp,
                    fontWeight = if (highlight == b) FontWeight.Bold else FontWeight.Normal,
                    color = if (highlight == b) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
