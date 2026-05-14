package com.fruitninj.gesture.inject

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.fruitninj.gesture.utils.EventMockLogger

/**
 * 无障碍服务骨架，用于三方应用场景的触摸注入。
 *
 * 使用方式：
 *  1. 用户在系统「无障碍设置」中手动开启本服务。
 *  2. 服务连接后，[instance] 自动赋值；断开时清空。
 *  3. [AccessibilityTouchInjector] 通过 [instance] 检查活跃性并调用 [dispatchGesture]。
 *
 * 生命周期设计：
 *  - [onServiceConnected]  → 注册到 [instance]，重置断连计数。
 *  - [onUnbind]            → 清空 [instance]，累计断连次数；连续 ≥3 次触发 NOOP 降级告警。
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureA11yService"

        /** 连续断连触发 NOOP 降级的阈值。 */
        private const val MAX_DISCONNECT_BEFORE_NOOP = 3

        /**
         * 服务单例引用（主线程读写，非主线程仅读）。
         * null 表示服务未连接或已断开。
         */
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** 累计断连次数（含本次），用于触发 NOOP 降级。 */
        @Volatile
        var disconnectCount: Int = 0
            private set

        /** 通知观察者服务已断连且需要降级到 NOOP 的回调（由工厂注册）。 */
        var onExcessiveDisconnect: (() -> Unit)? = null
    }

    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        instance = this
        disconnectCount = 0
        Log.i(TAG, "Service connected")
        EventMockLogger.logChannelFallback(
            from = InjectorType.NOOP,
            to = InjectorType.ACCESSIBILITY,
            reason = "AccessibilityService connected",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本服务仅用于手势注入，不处理无障碍事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        disconnectCount++
        Log.w(TAG, "Service unbound, disconnectCount=$disconnectCount")

        if (disconnectCount >= MAX_DISCONNECT_BEFORE_NOOP) {
            Log.e(TAG, "Excessive disconnects ($disconnectCount), triggering NOOP fallback")
            EventMockLogger.logChannelFallback(
                from = InjectorType.ACCESSIBILITY,
                to = InjectorType.NOOP,
                reason = "Service disconnected $disconnectCount times",
            )
            onExcessiveDisconnect?.invoke()
        }
        return super.onUnbind(intent)
    }
}

