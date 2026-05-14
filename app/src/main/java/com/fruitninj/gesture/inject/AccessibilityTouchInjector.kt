package com.fruitninj.gesture.inject

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.fruitninj.gesture.utils.EventMockLogger

/**
 * 无障碍服务注入通道。
 *
 * 通过 [GestureAccessibilityService.dispatchGesture] 注入触摸手势，
 * 适用于三方应用（非系统签名）场景。
 *
 * 可用性条件：
 *   1. [GestureAccessibilityService.instance] 不为 null（服务已连接）。
 *   2. Android 版本 ≥ N（API 24），dispatchGesture 最低要求。
 *
 * 降级逻辑：
 *   连续注入失败 ≥ [MAX_CONSECUTIVE_FAILURES] 次，通知工厂降级到 NOOP。
 *
 * @param onChannelFailed 连续失败达到阈值时由工厂注册的回调。
 */
class AccessibilityTouchInjector(
    private val onChannelFailed: () -> Unit = {},
) : TouchInjector {

    companion object {
        private const val TAG = "A11yTouchInjector"
        /** 触发降级的连续失败次数阈值。 */
        const val MAX_CONSECUTIVE_FAILURES = 3
        /** 单次手势最短持续时间（毫秒），dispatchGesture 要求 ≥ 1ms。 */
        private const val MIN_STROKE_DURATION_MS = 1L
        /** MOVE 续接段持续时间，保持与状态机 60Hz 节奏接近。 */
        private const val MOVE_STROKE_DURATION_MS = 16L
    }

    override val type: InjectorType = InjectorType.ACCESSIBILITY

    /** 当前连续失败次数。 */
    private var consecutiveFailures = 0

    /** 当前是否存在正在进行中的无障碍手势段。 */
    private var gestureInFlight = false

    /** 当前活跃的可续接 stroke；null 表示无进行中的按压会话。 */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** 最近一次已成功续接到系统中的坐标。 */
    private var committedX: Float = 0f
    private var committedY: Float = 0f

    /** 限频窗口内缓存的最新 MOVE。 */
    private var hasPendingMove = false
    private var pendingMoveX: Float = 0f
    private var pendingMoveY: Float = 0f

    /** 若前一段未完成时收到 UP，则在完成后优先发送结束段。 */
    private var pendingUp: PendingUp? = null

    private data class PendingUp(val x: Float, val y: Float)

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return GestureAccessibilityService.instance != null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun injectDown(x: Float, y: Float, downTime: Long) {
        synchronized(this) {
            if (activeStroke != null || gestureInFlight) {
                Log.w(TAG, "injectDown received while a gesture session is still active, resetting session")
                clearGestureSession()
            }
            committedX = x
            committedY = y
            clearPendingSignals()
            dispatchDownInternal(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun injectMove(x: Float, y: Float, eventTime: Long, downTime: Long) {
        synchronized(this) {
            if (activeStroke == null) {
                handleFailure("injectMove without active stroke")
                return
            }
            if (gestureInFlight) {
                hasPendingMove = true
                pendingMoveX = x
                pendingMoveY = y
                return
            }
            dispatchMoveInternal(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun injectUp(x: Float, y: Float, eventTime: Long, downTime: Long) {
        synchronized(this) {
            if (activeStroke == null) {
                handleFailure("injectUp without active stroke")
                return
            }
            if (gestureInFlight) {
                hasPendingMove = false
                pendingUp = PendingUp(x, y)
                return
            }
            dispatchUpInternal(x, y)
        }
    }

    // ------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchDownInternal(x: Float, y: Float) {
        val path = createPath(x, y, x, y)
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            MIN_STROKE_DURATION_MS,
            true,
        )
        dispatchStroke(
            stroke = stroke,
            reason = "DOWN",
            onCompleted = {
                activeStroke = stroke
                committedX = x
                committedY = y
                flushPendingSignalIfNeeded()
            },
            clearSessionOnCancel = true,
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchMoveInternal(x: Float, y: Float) {
        val baseStroke = activeStroke ?: run {
            handleFailure("dispatchMoveInternal without active stroke")
            return
        }
        val path = createPath(committedX, committedY, x, y)
        val stroke = baseStroke.continueStroke(
            path,
            0,
            MOVE_STROKE_DURATION_MS,
            true,
        )
        dispatchStroke(
            stroke = stroke,
            reason = "MOVE",
            onCompleted = {
                activeStroke = stroke
                committedX = x
                committedY = y
                flushPendingSignalIfNeeded()
            },
            clearSessionOnCancel = true,
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchUpInternal(x: Float, y: Float) {
        val baseStroke = activeStroke ?: run {
            handleFailure("dispatchUpInternal without active stroke")
            return
        }
        val path = createPath(committedX, committedY, x, y)
        val stroke = baseStroke.continueStroke(
            path,
            0,
            MIN_STROKE_DURATION_MS,
            false,
        )
        dispatchStroke(
            stroke = stroke,
            reason = "UP",
            onCompleted = {
                committedX = x
                committedY = y
                clearGestureSession()
            },
            clearSessionOnCancel = true,
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchStroke(
        stroke: GestureDescription.StrokeDescription,
        reason: String,
        onCompleted: () -> Unit,
        clearSessionOnCancel: Boolean,
    ) {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            handleFailure("service instance is null")
            return
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        gestureInFlight = true

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    synchronized(this@AccessibilityTouchInjector) {
                        gestureInFlight = false
                        consecutiveFailures = 0
                        onCompleted()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    synchronized(this@AccessibilityTouchInjector) {
                        gestureInFlight = false
                        Log.w(TAG, "Gesture cancelled: $reason")
                        if (clearSessionOnCancel) {
                            clearGestureSession()
                        }
                        handleFailure("$reason gesture cancelled")
                    }
                }
            },
            null,
        )

        if (!dispatched) {
            gestureInFlight = false
            if (clearSessionOnCancel) {
                clearGestureSession()
            }
            handleFailure("$reason dispatchGesture returned false")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun flushPendingSignalIfNeeded() {
        pendingUp?.let { up ->
            pendingUp = null
            dispatchUpInternal(up.x, up.y)
            return
        }
        if (hasPendingMove) {
            val x = pendingMoveX
            val y = pendingMoveY
            hasPendingMove = false
            dispatchMoveInternal(x, y)
        }
    }

    private fun clearPendingSignals() {
        hasPendingMove = false
        pendingUp = null
    }

    private fun clearGestureSession() {
        gestureInFlight = false
        activeStroke = null
        clearPendingSignals()
    }

    private fun createPath(startX: Float, startY: Float, endX: Float, endY: Float): Path {
        return Path().apply {
            moveTo(startX, startY)
            if (startX != endX || startY != endY) {
                lineTo(endX, endY)
            }
        }
    }

    private fun handleFailure(reason: String) {
        consecutiveFailures++
        Log.e(TAG, "Inject failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): $reason")
        EventMockLogger.logError(
            sessionId = -1,
            injectorType = type,
            errorMsg = reason,
            failCode = consecutiveFailures,
        )
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Consecutive failures reached threshold, triggering channel fallback")
            EventMockLogger.logChannelFallback(
                from = InjectorType.ACCESSIBILITY,
                to = InjectorType.NOOP,
                reason = "consecutiveFailures=$consecutiveFailures reason=$reason",
            )
            onChannelFailed()
        }
    }
}



