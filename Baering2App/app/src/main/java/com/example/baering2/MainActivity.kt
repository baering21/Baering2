package com.example.baering2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BaeringTheme { Baering2App(onRestart = ::restartApp) }
        }
    }

    /** 앱 프로세스를 완전히 종료하고 처음부터 다시 실행한다. */
    private fun restartApp() {
        BeaconManager.shutdown()
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }
}

private val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN)

private fun hasBtPermissions(ctx: Context) =
    BT_PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun isBatteryExempt(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Baering2App(onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val ui by BeaconManager.ui.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasBtPermissions(ctx)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBtPermissions(ctx)
        if (granted) BeaconService.start(ctx)
    }
    val askPermissions = {
        val perms = if (Build.VERSION.SDK_INT >= 33) BT_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS else BT_PERMISSIONS
        permLauncher.launch(perms)
    }

    LaunchedEffect(Unit) { if (granted) BeaconService.start(ctx) else askPermissions() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasBtPermissions(ctx)
        batteryExempt = isBatteryExempt(ctx)
        if (granted) BeaconService.start(ctx)
        if (OverlayWidget.pendingShow) {
            OverlayWidget.pendingShow = false
            if (OverlayWidget.canShow(ctx)) OverlayWidget.show(ctx)
        }
    }

    // "몇 초 전" 표시용 1초 시계
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("배달링2 v${BuildConfigVersion.name(ctx)}") }) }) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!granted) item {
                NoticeCard("블루투스 권한이 필요해요", "배달링2 신호를 받으려면 '근처 기기' 권한을 허용해 주세요.", "권한 허용", askPermissions)
            }
            if (granted && !ui.btEnabled) item {
                NoticeCard("블루투스가 꺼져 있어요", "켜면 자동으로 배달링2 신호를 찾습니다.", "블루투스 켜기") {
                    ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
            if (granted && !batteryExempt) item {
                NoticeCard(
                    "배터리 최적화 예외를 켜주세요",
                    "갤럭시는 백그라운드 앱의 블루투스 스캔을 멈출 수 있어요. 예외로 두면 화면이 꺼져도 신호를 계속 받습니다. " +
                        "(설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에도 추가하면 더 안전해요)",
                    "예외 허용",
                ) {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
            item { ScanCard(ui, onRestart) }
            item { SwitchTestCard(ui) }
            for (d in ui.devices) item(key = d.address) { DeviceCard(d, now) }
            item { EventLogCard(ui) }
            item {
                LogCard(ui, onShare = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, BeaconManager.shareText())
                    }, "로그 공유"))
                })
            }
        }
    }
}

/** 앱 버전 이름 (build.gradle의 versionName) */
private object BuildConfigVersion {
    fun name(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}

// ───────────────────────── 카드들 ─────────────────────────

@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun ScanCard(ui: BeaconManager.Ui, onRestart: () -> Unit) {
    val live = ui.devices.count { !it.lost }
    Section("비콘 수신") {
        Text(
            when {
                !ui.scanning -> "스캔 꺼짐"
                live == 0 -> "찾는 중…"
                else -> "${live}대 수신 중"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (live > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("BLE 스캔 (상시)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "이름 '${BeaconManager.TARGET_NAME}' · 제조사 데이터 [VCC][GPIO]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.scanEnabled, onCheckedChange = BeaconManager::setScanEnabled)
        }
        Text(
            "배달링2는 연결 없이 광고를 계속 보내요. 스위치(GPIO) 값이 바뀌는 순간을 기록합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = BeaconManager::clearDevices, modifier = Modifier.weight(1f)) { Text("목록 비우기") }
            OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("앱 재실행") }
        }
    }
}

@Composable
private fun SwitchTestCard(ui: BeaconManager.Ui) {
    val ctx = LocalContext.current
    val widgetOn by OverlayWidget.showing.collectAsStateWithLifecycle()
    Section("스위치 테스트", action = { TextButton(onClick = BeaconManager::clearStats) { Text("초기화") } }) {
        LastChangeBox(ui)
        Text(
            "신호 인식 ${ui.totalRecognitions}회 · 비트 변화 ${ui.totalChanges}회 (인식 후 ${BeaconManager.RESET_MS}ms 뒤 초기화 → 같은 버튼도 다시 인식)",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                when {
                    widgetOn -> OverlayWidget.hide()
                    OverlayWidget.canShow(ctx) -> OverlayWidget.show(ctx)
                    else -> {
                        // '다른 앱 위에 표시' 권한 화면 → 돌아오면 ON_RESUME에서 위젯을 띄운다
                        OverlayWidget.pendingShow = true
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (widgetOn) "위젯 닫기" else "위젯 활성화") }
        Text(
            "위젯은 다른 앱 위에 떠서 위 현황을 그대로 보여줘요. 홈 화면으로 나가도 신호가 들어오는지 확인할 수 있어요. 끌어서 옮길 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceCard(d: BeaconManager.Dev, now: Long) {
    val ago = ((now - d.lastSeen) / 1000).coerceAtLeast(0)
    Section("${d.name}  ${d.address}") {
        Text(
            if (d.lost) "신호 끊김 · ${ago}초 전" else "수신 중 · ${d.rssi}dBm",
            fontWeight = FontWeight.Bold,
            color = if (d.lost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            "받은 광고 ${d.packets}개 · 평균 간격 ${d.avgIntervalMs?.let { "${it}ms" } ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("VCC", style = MaterialTheme.typography.labelMedium)
                Text(Beacon2Packet.hex2(d.vcc), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("= ${d.vcc} (원시값)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text("GPIO", style = MaterialTheme.typography.labelMedium)
                Text(Beacon2Packet.hex2(d.gpio), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(Beacon2Packet.bits(d.gpio), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        BitRow(d.gpio, highlight = d.lastChangedBit)
        Spacer(Modifier.height(8.dp))
        // 비트별 변화 횟수 (스위치를 눌렀다 떼면 2번 바뀜)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in 7 downTo 0) {
                val last = d.lastChangedBit == b
                Column(
                    Modifier
                        .weight(1f)
                        .background(
                            if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("b$b", style = MaterialTheme.typography.labelSmall)
                    Text("${d.bitChanges[b]}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("비트별 변화 횟수 (누름+뗌 = 2회)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("값별 인식 횟수", style = MaterialTheme.typography.labelMedium)
        if (d.valueCounts.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
        for ((v, n) in d.valueCounts.entries.sortedBy { it.key }) {
            val last = d.lastValue == v
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${Beacon2Packet.hex2(v)}  ${Beacon2Packet.bits(v)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("${n}회", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("회사 ID 0x%04X".format(d.companyId), style = MaterialTheme.typography.bodySmall)
        Text("Raw ${d.rawHex}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun EventLogCard(ui: BeaconManager.Ui) {
    Section("변화 기록 (최신순)") {
        if (ui.events.isEmpty()) Text("아직 기록이 없습니다", style = MaterialTheme.typography.bodySmall)
        for (e in ui.events.take(80)) {
            Text("${e.time}  ${e.address}  ${e.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LogCard(ui: BeaconManager.Ui, onShare: () -> Unit) {
    Section("상세 로그 (최신순)", action = {
        Row {
            TextButton(onClick = onShare) { Text("공유") }
            TextButton(onClick = BeaconManager::clearLogs) { Text("지우기") }
        }
    }) {
        for (line in ui.logs.take(100)) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}
