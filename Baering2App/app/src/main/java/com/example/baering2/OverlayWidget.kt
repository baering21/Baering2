package com.example.baering2

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 다른 앱 위에 떠 있는 작은 위젯. 스위치 테스트 칸의 상단 현황([LastChangeBox])을 그대로 보여준다.
 * 앱을 백그라운드로 보내도 [BeaconService]가 살아 있으면 계속 갱신되므로 백그라운드 수신 확인용이다.
 * 끌어서 옮길 수 있고, 우측 상단 ✕로 닫는다.
 */
object OverlayWidget {

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing

    /** 권한 설정 화면에서 돌아오면 위젯을 띄우기 위한 표시 */
    var pendingShow = false

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (view != null || !canShow(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)
        val density = app.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }

        val lifecycleOwner = OverlayOwner().also { it.start() }
        val composeView = ComposeView(app).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                BaeringTheme {
                    val ui by BeaconManager.ui.collectAsStateWithLifecycle()
                    Box(
                        Modifier
                            .width(260.dp)
                            .shadow(10.dp, RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    params.x += drag.x.toInt()
                                    params.y += drag.y.toInt()
                                    view?.let { wm.updateViewLayout(it, params) }
                                }
                            }
                    ) {
                        LastChangeBox(ui)
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                .clickable { hide() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        wm.addView(composeView, params)
        view = composeView
        owner = lifecycleOwner
        _showing.value = true
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        owner?.stop()
        view = null
        owner = null
        _showing.value = false
    }

    /** ComposeView를 액티비티 밖(WindowManager)에 붙이려면 생명주기와 상태 저장 소유자가 필요하다. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
