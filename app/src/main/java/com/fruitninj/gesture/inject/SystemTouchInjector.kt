package com.fruitninj.gesture.inject

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.fruitninj.gesture.utils.EventMockLogger
import com.fruitninj.gesture.utils.InputManager
import com.fruitninj.gesture.utils.LogUtil

/**
 * 系统应用注入通道。
 *
 * 通过反射调用 InputManager.injectInputEvent 注入真实 MotionEvent。
 * 前提：持有 android.permission.INJECT_EVENTS 且系统允许（系统签名或系统应用）。
 *
 * isAvailable() 通过尝试探测方法是否可调用来判断是否可用；
 * 若首次调用成功则缓存可用状态，失败则返回 false（不再重试，交由工厂降级处理）。
 */
class SystemTouchInjector(private val context: Context) : TouchInjector {

    companion object {
        private const val TAG = "SystemTouchInjector"
        private const val POINTER_ID = 0
        private const val PERMISSION_INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    }

    override val type: InjectorType = InjectorType.SYSTEM

    /** 可用性缓存：null=未探测，true=可用，false=不可用。 */
    @Volatile
    private var availableCache: Boolean? = null

    override fun isAvailable(): Boolean {
        availableCache?.let { return it }

        if (!hasInjectEventsPermission()) {
            Log.w(TAG, "isAvailable probe skipped: missing $PERMISSION_INJECT_EVENTS")
            availableCache = false
            return false
        }

        // 探测：发送一个无实际效果的事件（采用取消动作减少副作用）
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val result = InputManager.injectInputEvent(
                context,
                event,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
            )
            event.recycle()
            availableCache = result
            Log.i(TAG, "isAvailable probe: $result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "isAvailable probe failed: ${e.message}")
            availableCache = false
            false
        }
    }

    private fun hasInjectEventsPermission(): Boolean {
        return context.packageManager.checkPermission(
            PERMISSION_INJECT_EVENTS,
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED
    }

    init {
        LogUtil.setInterval(TAG,1000)
    }

    override fun injectDown(x: Float, y: Float, downTime: Long) {
        Log.i(TAG, "injectDown: x=$x y=$y downTime=$downTime")
        inject(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
    }

    override fun injectMove(x: Float, y: Float, eventTime: Long, downTime: Long) {
        LogUtil.i("injectMove",TAG, "injectMove: x=$x y=$y eventTime=$eventTime downTime=$downTime")
        inject(MotionEvent.ACTION_MOVE, x, y, eventTime, downTime)
    }

    override fun injectUp(x: Float, y: Float, eventTime: Long, downTime: Long) {
        Log.i(TAG, "injectUp: x=$x y=$y eventTime=$eventTime downTime=$downTime")
        inject(MotionEvent.ACTION_UP, x, y, eventTime, downTime)
    }

    // ------------------------------------------------------------------

    private fun inject(action: Int, x: Float, y: Float, eventTime: Long, downTime: Long) {
        val event = buildMotionEvent(action, x, y, eventTime, downTime)
        val ok = InputManager.injectInputEvent(
            context,
            event,
            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
        )
        event.recycle()

        if (!ok) {
            val actionName = actionName(action)
            Log.e(TAG, "injectInputEvent failed: action=$actionName x=$x y=$y")
            EventMockLogger.logError(
                sessionId = -1,
                injectorType = type,
                errorMsg = "injectInputEvent returned false action=$actionName",
            )
        }
    }

    private fun buildMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1.0f
            size = 1.0f
        }
        return MotionEvent.obtain(
            /* downTime    */ downTime,
            /* eventTime   */ eventTime,
            /* action      */ action,
            /* pointerCount*/ 1,
            /* pointerProps */ arrayOf(properties),
            /* pointerCoords*/ arrayOf(coords),
            /* metaState   */ 0,
            /* buttonState */ 0,
            /* xPrecision  */ 1.0f,
            /* yPrecision  */ 1.0f,
            /* deviceId    */ 0,
            /* edgeFlags   */ 0,
            /* source      */ InputDevice.SOURCE_TOUCHSCREEN,
            /* flags       */ 0,
        )
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN   -> "DOWN"
        MotionEvent.ACTION_MOVE   -> "MOVE"
        MotionEvent.ACTION_UP     -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else                      -> "UNKNOWN($action)"
    }
}

