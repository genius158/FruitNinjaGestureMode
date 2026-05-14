package com.fruitninj.gesture.inject

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.fruitninj.gesture.utils.EventMockLogger

/**
 * NOOP 注入通道 — 空实现，不产生任何真实触摸事件。
 *
 * 行为：
 *  - 打印日志说明当前处于降级状态。
 *  - 弹 Toast 提示用户开启无障碍服务（仅在弹出频率控制下，避免刷屏）。
 *  - 通过 Intent 跳转到系统无障碍设置页，引导用户手动开启。
 *
 * 注意：Toast 和 Intent 均在调用者所在线程触发，请确保在主线程调用。
 */
class NoopTouchInjector(private val context: Context) : TouchInjector {

    companion object {
        private const val TAG = "NoopTouchInjector"
        /** 引导 Toast 的最小间隔，避免频繁弹窗（ms）。 */
        private const val GUIDE_TOAST_INTERVAL_MS = 5000L
    }

    override val type: InjectorType = InjectorType.NOOP

    private var lastGuideToastTime: Long = 0L

    override fun isAvailable(): Boolean = true // NOOP 始终"可用"（不崩溃）

    override fun injectDown(x: Float, y: Float, downTime: Long) {
        Log.d(TAG, "NOOP injectDown x=$x y=$y")
        showGuideIfNeeded()
    }

    override fun injectMove(x: Float, y: Float, eventTime: Long, downTime: Long) {
        Log.d(TAG, "NOOP injectMove x=$x y=$y")
    }

    override fun injectUp(x: Float, y: Float, eventTime: Long, downTime: Long) {
        Log.d(TAG, "NOOP injectUp x=$x y=$y")
    }

    /**
     * 在通道首次失效时显示引导 UI，避免高频调用时刷屏。
     * 由 TouchInjectorFactory 在降级时主动调用一次。
     */
    fun showAccessibilityGuide() {
        showToast("请开启无障碍服务以启用触摸注入功能")
        navigateToAccessibilitySettings()
    }

    // ------------------------------------------------------------------

    private fun showGuideIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastGuideToastTime >= GUIDE_TOAST_INTERVAL_MS) {
            lastGuideToastTime = now
            showToast("注入通道不可用，请在无障碍设置中开启手势服务")
        }
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Toast failed: ${e.message}")
        }
    }

    private fun navigateToAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open accessibility settings: ${e.message}")
        }
    }
}

