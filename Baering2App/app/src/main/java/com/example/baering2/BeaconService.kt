package com.example.baering2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 포그라운드 서비스(connectedDevice). 앱 화면을 닫거나 화면이 꺼져도
 * [BeaconManager]가 비콘 스캔을 계속하도록 프로세스를 살려둔다.
 */
class BeaconService : Service() {

    companion object {
        private const val CHANNEL_ID = "beacon"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.example.baering2.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BeaconService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "비콘 수신", NotificationManager.IMPORTANCE_LOW).apply {
                description = "배달링2 비콘 수신 상태"
            }
        )
        startForeground(NOTIFICATION_ID, buildNotification("시작 중…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch {
            BeaconManager.ui
                .map { u ->
                    val live = u.devices.count { !it.lost }
                    when {
                        !u.scanning -> "스캔 꺼짐"
                        live == 0 -> "${BeaconManager.TARGET_NAME} 찾는 중"
                        else -> "${live}대 수신 중" + (u.lastGpio?.let { " · GPIO ${Beacon2Packet.hex2(it)}" } ?: "") +
                            " · 인식 ${u.totalRecognitions}회"
                    }
                }
                .distinctUntilChanged()
                .collect { nm.notify(NOTIFICATION_ID, buildNotification(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BeaconManager.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        BeaconManager.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("배달링2")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .build()
    }
}
