package com.example.baering2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 배달링2 비콘 수신의 두뇌. 연결 없이 광고만 받아 VCC · GPIO를 해석하고, 상태를 [ui]로 내보낸다.
 * 배달링2는 약 66ms마다 같은 광고를 계속 보내므로, GPIO 값이 **바뀔 때만** 이벤트로 기록한다.
 * 앱 화면이 꺼져도 [BeaconService]가 살아 있는 동안 계속 동작한다. 모든 상태 변경은 메인 스레드에서 한다.
 */
@SuppressLint("MissingPermission")
object BeaconManager {

    const val TARGET_NAME = Beacon2Packet.TARGET_NAME

    /** 이 시간 동안 광고가 안 오면 "신호 끊김" */
    const val LOST_MS = 3_000L

    /** 화면 갱신 최소 간격 (광고가 초당 15번쯤 와서 매번 그리면 낭비). GPIO가 바뀌면 바로 갱신 */
    private const val PUBLISH_MS = 250L
    private const val VCC_LOG_MS = 5_000L

    /**
     * 신호를 인식한 뒤 이 시간이 지나면 비교 기준을 지운다 → 같은 버튼(같은 GPIO 값)을 다시 눌러도 새로 인식.
     * 같은 값이 계속 들어오면 이 간격마다 다시 인식된다.
     */
    const val RESET_MS = 300L

    data class Dev(
        val address: String,
        val name: String,
        val rssi: Int,
        val companyId: Int,
        val vcc: Int,
        val gpio: Int,
        val rawHex: String,
        val packets: Long,
        /** 받은 광고 사이 평균 간격(ms). 폰이 일부를 못 받으면 실제 광고 간격보다 길게 나온다 */
        val avgIntervalMs: Int?,
        /** SystemClock.elapsedRealtime() 기준 마지막 수신 시각 */
        val lastSeen: Long,
        val lost: Boolean,
        /** 비트(0~7)별 값이 바뀐 횟수 */
        val bitChanges: List<Int>,
        val lastChangedBit: Int?,
        /** GPIO 값별 인식 횟수 (같은 버튼 반복도 셈) */
        val valueCounts: Map<Int, Int> = emptyMap(),
        val lastValue: Int? = null,
    )

    data class EventLine(val time: String, val address: String, val text: String)

    data class Ui(
        val started: Boolean = false,
        val btEnabled: Boolean = true,
        val scanEnabled: Boolean = true,
        val scanning: Boolean = false,
        val devices: List<Dev> = emptyList(),
        val lastLabel: String = "—",
        val lastMeta: String = "",
        val lastGpio: Int? = null,
        val lastVcc: Int? = null,
        val flashTick: Int = 0,
        val totalChanges: Long = 0,
        /** 신호 인식 횟수 (값이 바뀌었거나, 0.3초 초기화 뒤 같은 값이 다시 들어온 경우) */
        val totalRecognitions: Long = 0,
        val events: List<EventLine> = emptyList(),
        val logs: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val adapter: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** 기기(MAC)별 수신 상태 */
    private class Track(var dev: Dev, var lastNanos: Long, var emaMs: Double?, var lastVccLog: Long, var lastPublish: Long) {
        /** 새 신호인지 비교할 기준 GPIO 값. null = 초기화됨 → 다음 광고를 새로 인식 */
        var baseline: Int? = null
        /** 기준값 초기화 전, 마지막으로 인식한 값 (재인식 때 무엇이 바뀌었는지 표시용) */
        var prevValue: Int? = null
        var resetTask: Runnable? = null
    }

    private val tracks = LinkedHashMap<String, Track>()
    private var scanning = false

    // ───────────────────────── 시작 · 종료 ─────────────────────────

    /** [BeaconService]가 시작될 때 호출. 여러 번 불러도 안전하다. */
    fun start(context: Context) {
        if (!_ui.value.started) {
            app = context.applicationContext
            prefs = app.getSharedPreferences("baering2", Context.MODE_PRIVATE)
            app.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            _ui.update { it.copy(started = true, scanEnabled = prefs.getBoolean("scan", true)) }
            log("배달링2 앱 시작")
            handler.postDelayed(lostChecker, 1000)
        }
        onBluetoothReady()
    }

    /** 알림의 '종료' — 스캔을 멈춘다. */
    fun shutdown() {
        if (!_ui.value.started) return
        stopScan()
        log("백그라운드 수신 종료")
    }

    private fun onBluetoothReady() {
        val enabled = adapter?.isEnabled == true
        _ui.update { it.copy(btEnabled = enabled) }
        if (!enabled) {
            log("블루투스가 꺼져 있습니다")
            return
        }
        if (_ui.value.scanEnabled) startScan()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { log("블루투스 켜짐"); onBluetoothReady() }
                BluetoothAdapter.STATE_OFF -> {
                    log("블루투스 꺼짐")
                    scanning = false
                    _ui.update { it.copy(btEnabled = false, scanning = false) }
                }
            }
        }
    }

    // ───────────────────────── 스캔 ─────────────────────────

    fun setScanEnabled(on: Boolean) {
        prefs.edit().putBoolean("scan", on).apply()
        _ui.update { it.copy(scanEnabled = on) }
        if (on) startScan() else stopScan()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onScanResultMain(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                log("스캔 실패 (코드 $errorCode) — 스캔 스위치를 껐다 켜주세요")
                scanning = false
                _ui.update { it.copy(scanning = false) }
            }
        }
    }

    private fun startScan() {
        if (scanning || !_ui.value.started) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        // 필터를 걸어야 화면이 꺼져도 스캔이 유지되고 오래 켜둬도 안드로이드가 제한하지 않는다
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(TARGET_NAME).build(),
            ScanFilter.Builder().setManufacturerData(Beacon2Packet.COMPANY_ID, ByteArray(0)).build(),
        )
        // 같은 광고가 계속 오므로 모든 광고를 바로 받는다 (스위치 변화를 빨리 잡기 위해 저지연)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            log("스캔 시작 실패: ${e.message}")
            return
        }
        scanning = true
        _ui.update { it.copy(scanning = true) }
        log("$TARGET_NAME 상시 스캔 ON")
    }

    private fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        _ui.update { it.copy(scanning = false) }
        log("스캔 OFF")
    }

    // ───────────────────────── 비콘 해석 ─────────────────────────

    private fun onScanResultMain(result: ScanResult) {
        val record = result.scanRecord ?: return
        val name = record.deviceName
        val isTarget = name.equals(TARGET_NAME, ignoreCase = true)
        // 회사 ID 0x0000 우선, 없으면 (이름이 맞을 때) 처음 나오는 제조사 데이터
        var cid = Beacon2Packet.COMPANY_ID
        var data = record.getManufacturerSpecificData(cid)
        if (data == null && isTarget) {
            val all = record.manufacturerSpecificData
            if (all != null && all.size() > 0) { cid = all.keyAt(0); data = all.valueAt(0) }
        }
        // 이름 없이 회사 ID 0x0000만 맞는 다른 기기(드묾)는 길이가 정확히 2일 때만 받는다
        if (!isTarget && (data == null || data.size != Beacon2Packet.SIZE)) return

        val address = result.device.address
        val pkt = Beacon2Packet.parse(cid, data)
        val now = SystemClock.elapsedRealtime()
        val tr = tracks[address]

        if (pkt == null) {
            if (tr == null || now - tr.lastVccLog > VCC_LOG_MS) {
                log("$address: 해석할 수 없는 광고 — 제조사 데이터 ${Beacon2Packet.hexBytes(data)} / 원본 ${Beacon2Packet.hexBytes(record.bytes?.trimAd())}")
                tr?.lastVccLog = now
            }
            return
        }
        if (data!!.size != Beacon2Packet.SIZE && tr == null) {
            log("$address: 제조사 데이터 길이가 ${data.size}바이트 (예상 2) — 앞 2바이트만 사용")
        }

        val track = if (tr != null) {
            // 수신 간격(지수 평균). 신호가 끊겼다 돌아온 경우는 평균에 넣지 않는다
            val gapMs = (result.timestampNanos - tr.lastNanos) / 1_000_000.0
            tr.lastNanos = result.timestampNanos
            if (tr.dev.lost) addEvent(address, "신호 다시 잡힘  (${"%.1f".format(gapMs / 1000)}초 끊김)  GPIO ${pkt.gpioHex}")
            else if (gapMs in 1.0..LOST_MS.toDouble()) tr.emaMs = tr.emaMs?.let { it * 0.9 + gapMs * 0.1 } ?: gapMs
            tr
        } else {
            val dev = Dev(
                address, name ?: TARGET_NAME, result.rssi, cid, pkt.vcc, pkt.gpio,
                Beacon2Packet.hexBytes(record.bytes?.trimAd()), 0, null, now, false, List(8) { 0 }, null,
            )
            log("$address: 발견 · 회사 ID 0x%04X · 원본 ${dev.rawHex} · VCC ${pkt.vccHex}=${pkt.vcc}".format(cid))
            Track(dev, result.timestampNanos, null, now, 0).also { tracks[address] = it }
        }

        val old = track.dev
        var counts = old.bitChanges
        var lastBit = old.lastChangedBit
        var valueCounts = old.valueCounts
        // 기준값과 다르거나, 0.3초 초기화로 기준값이 비어 있으면 새 신호로 인식 (같은 버튼 반복도 인식)
        val recognized = track.baseline != pkt.gpio
        if (recognized) {
            val prev = track.baseline ?: track.prevValue
            val changed = if (prev == null) 0 else prev xor pkt.gpio
            val parts = ArrayList<String>()
            if (changed != 0) {
                counts = counts.toMutableList()
                for (b in 0..7) if (changed shr b and 1 == 1) {
                    counts[b]++
                    lastBit = b
                    parts += "bit$b ${prev!! shr b and 1}→${pkt.gpio shr b and 1}"
                }
            }
            val n = (valueCounts[pkt.gpio] ?: 0) + 1
            valueCounts = valueCounts + (pkt.gpio to n)
            val kind = when {
                prev == null -> "첫 인식"
                changed == 0 -> "같은 신호 재인식"
                else -> parts.joinToString(", ")
            }
            addEvent(address, "GPIO ${pkt.gpioHex} (${Beacon2Packet.bits(pkt.gpio)}) #$n  $kind")
            _ui.update {
                it.copy(
                    lastLabel = "GPIO ${pkt.gpioHex}",
                    lastMeta = "$kind · ${n}번째 · ${address.takeLast(5)} · ${timeFmt.format(Date()).take(8)}",
                    flashTick = it.flashTick + 1,
                    totalChanges = it.totalChanges + Integer.bitCount(changed),
                    totalRecognitions = it.totalRecognitions + 1,
                )
            }
            track.baseline = pkt.gpio
            track.prevValue = pkt.gpio
            // 0.3초 뒤 기준값 초기화 → 그 뒤 같은 값이 들어와도 새로 인식
            track.resetTask?.let(handler::removeCallbacks)
            val reset = Runnable { track.baseline = null; track.resetTask = null }
            track.resetTask = reset
            handler.postDelayed(reset, RESET_MS)
        }
        if (old.vcc != pkt.vcc && now - track.lastVccLog > VCC_LOG_MS) {
            track.lastVccLog = now
            log("$address: VCC ${Beacon2Packet.hex2(old.vcc)}→${pkt.vccHex} (${old.vcc}→${pkt.vcc})")
        }

        track.dev = old.copy(
            name = name ?: old.name,
            rssi = result.rssi,
            companyId = cid,
            vcc = pkt.vcc,
            gpio = pkt.gpio,
            rawHex = Beacon2Packet.hexBytes(record.bytes?.trimAd()),
            packets = old.packets + 1,
            avgIntervalMs = track.emaMs?.toInt(),
            lastSeen = now,
            lost = false,
            bitChanges = counts,
            lastChangedBit = lastBit,
            valueCounts = valueCounts,
            lastValue = if (recognized) pkt.gpio else old.lastValue,
        )
        _ui.update { it.copy(lastGpio = pkt.gpio, lastVcc = pkt.vcc) }
        if (recognized || old.lost || now - track.lastPublish >= PUBLISH_MS) {
            track.lastPublish = now
            publish()
        }
    }

    /** 1초마다 오래 안 들어온 기기를 "신호 끊김"으로 표시 */
    private val lostChecker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            var any = false
            for ((addr, tr) in tracks) {
                if (!tr.dev.lost && now - tr.dev.lastSeen > LOST_MS) {
                    tr.dev = tr.dev.copy(lost = true)
                    addEvent(addr, "신호 끊김  (마지막 GPIO ${Beacon2Packet.hex2(tr.dev.gpio)})")
                    any = true
                }
            }
            if (any) publish()
            handler.postDelayed(this, 1000)
        }
    }

    private fun publish() {
        _ui.update { it.copy(devices = tracks.values.map { t -> t.dev }) }
    }

    /** 스캔 레코드 원본(31바이트, 뒤는 0으로 채워짐)에서 실제 AD 구조까지만 자른다. */
    private fun ByteArray.trimAd(): ByteArray {
        var i = 0
        while (i < size) {
            val len = this[i].toInt() and 0xFF
            if (len == 0) break
            i += len + 1
        }
        return copyOf(i.coerceAtMost(size))
    }

    // ───────────────────────── 초기화 · 기록 ─────────────────────────

    /** '초기화' — 비트별 변화 횟수와 이벤트 기록을 지운다 (발견된 기기는 유지). */
    fun clearStats() {
        for (tr in tracks.values) {
            tr.dev = tr.dev.copy(bitChanges = List(8) { 0 }, lastChangedBit = null, packets = 0, valueCounts = emptyMap(), lastValue = null)
        }
        _ui.update { it.copy(events = emptyList(), totalChanges = 0, totalRecognitions = 0, lastLabel = "—", lastMeta = "") }
        publish()
    }

    /** 발견된 기기 목록을 비운다 (지금도 광고 중이면 다시 나타남). */
    fun clearDevices() {
        for (tr in tracks.values) tr.resetTask?.let(handler::removeCallbacks)
        tracks.clear()
        publish()
    }

    fun clearLogs() {
        _ui.update { it.copy(logs = emptyList()) }
    }

    fun shareText(): String = buildString {
        val u = _ui.value
        appendLine("배달링2 수신 로그 (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())})")
        for (d in u.devices) {
            appendLine("· ${d.name} ${d.address} ${d.rssi}dBm  VCC ${Beacon2Packet.hex2(d.vcc)}=${d.vcc}  GPIO ${Beacon2Packet.hex2(d.gpio)} (${Beacon2Packet.bits(d.gpio)})  수신 ${d.packets}  평균 간격 ${d.avgIntervalMs ?: "-"}ms")
            appendLine("  비트별 변화 " + (7 downTo 0).joinToString(" ") { "b$it=${d.bitChanges[it]}" })
            appendLine("  값별 인식 " + d.valueCounts.entries.sortedBy { it.key }.joinToString(" ") { "${Beacon2Packet.hex2(it.key)}=${it.value}" })
            appendLine("  원본 ${d.rawHex}")
        }
        appendLine()
        appendLine("[변화 기록]")
        for (e in u.events) appendLine("${e.time}  ${e.address}  ${e.text}")
        appendLine()
        appendLine("[상세 로그]")
        for (l in u.logs) appendLine(l)
    }

    private fun addEvent(address: String, text: String) {
        val line = EventLine(timeFmt.format(Date()), address.takeLast(5), text)
        _ui.update { it.copy(events = (listOf(line) + it.events).take(300)) }
    }

    fun log(msg: String) {
        Log.i("Baering2", msg)
        val line = "${timeFmt.format(Date())}  $msg"
        _ui.update { it.copy(logs = (listOf(line) + it.logs).take(300)) }
    }
}
