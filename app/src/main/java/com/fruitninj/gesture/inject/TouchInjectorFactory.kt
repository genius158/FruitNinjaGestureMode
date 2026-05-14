package com.fruitninj.gesture.inject

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fruitninj.gesture.utils.EventMockLogger
import java.util.WeakHashMap

/**
 * TouchInjector 工厂。
 *
 * 按优先级 SYSTEM > ACCESSIBILITY > NOOP 探测并返回最优注入通道。
 *
 * 运行时降级路径：
 *  - SYSTEM 通道失效 → 尝试 ACCESSIBILITY
 *  - ACCESSIBILITY 通道失效（连续 ≥3 次失败 / 服务断连超限）→ NOOP + 引导 UI
 *
 * 可接收 [TouchStateMachine] 引用，通道切换时同步更新状态机当前注入器。
 */
object TouchInjectorFactory {

    private const val TAG = "TouchInjectorFactory"
    private const val RECOVERY_RETRY_INTERVAL_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recoveryTasks = WeakHashMap<TouchStateMachine, Runnable>()

    /**
     * 创建并返回当前最优的 [TouchInjector]。
     *
     * @param context       Application Context
     * @param stateMachine  活跃的状态机（降级时同步替换其 injector）
     */
    fun create(context: Context, stateMachine: TouchStateMachine? = null): TouchInjector {
        val appContext = context.applicationContext
        val noop = NoopTouchInjector(appContext)

        // 1. 尝试系统通道
        val systemInjector = SystemTouchInjector(appContext)
        if (systemInjector.isAvailable()) {
            stateMachine?.let { stopPeriodicRecovery(it) }
            Log.i(TAG, "Selected channel: SYSTEM")
            return systemInjector
        }

        // 2. 尝试无障碍通道
        val a11yInjector = createAccessibilityInjector(
            context = appContext,
            stateMachine = stateMachine,
            noop = noop,
            source = "startup",
        )

        if (a11yInjector.isAvailable()) {
            stateMachine?.let { stopPeriodicRecovery(it) }
            Log.i(TAG, "Selected channel: ACCESSIBILITY")
            return a11yInjector
        }

        // 3. 降级到 NOOP
        Log.w(TAG, "No available channel, falling back to NOOP")
        EventMockLogger.logChannelFallback(
            from = InjectorType.SYSTEM,
            to = InjectorType.NOOP,
            reason = "No available injector at startup",
        )
        noop.showAccessibilityGuide()
        stateMachine?.let { startPeriodicRecoveryIfNeeded(appContext, it) }
        return noop
    }

    /**
     * 从无障碍服务设置页返回后，尝试将注入通道从 NOOP 恢复到 ACCESSIBILITY。
     *
     * 调用时机：Activity/Fragment onResume() 时，检测用户是否在系统设置中开启了无障碍服务。
     * 设计为幂等：若当前通道已是 SYSTEM 或 ACCESSIBILITY，则无操作直接返回，
     * 不会降低已有通道优先级。
     *
     * @param context      Application Context
     * @param stateMachine 需要尝试恢复注入通道的状态机
     */
    fun tryRecoverAccessibility(context: Context, stateMachine: TouchStateMachine) {
        // 仅在当前为 NOOP 时才尝试恢复，避免降级已有 SYSTEM/ACCESSIBILITY 通道
        if (stateMachine.activeInjectorType != InjectorType.NOOP) {
            stopPeriodicRecovery(stateMachine)
            Log.d(TAG, "tryRecoverAccessibility: current=${stateMachine.activeInjectorType}, skip")
            return
        }

        val appContext = context.applicationContext
        val recovered = probeBestAvailableInjector(appContext, stateMachine)
        if (recovered != null) {
            Log.i(TAG, "Settings return recovery: NOOP -> ${recovered.type}")
            EventMockLogger.logChannelFallback(
                from = InjectorType.NOOP,
                to = recovered.type,
                reason = "Settings return recovery",
            )
            stateMachine.setInjector(recovered)
            stopPeriodicRecovery(stateMachine)
        } else {
            Log.d(TAG, "tryRecoverAccessibility: no channel recovered, keep NOOP and start periodic check")
            startPeriodicRecoveryIfNeeded(appContext, stateMachine)
        }
    }

    @Synchronized
    fun startPeriodicRecoveryIfNeeded(context: Context, stateMachine: TouchStateMachine) {
        if (stateMachine.activeInjectorType != InjectorType.NOOP) {
            stopPeriodicRecovery(stateMachine)
            return
        }
        if (recoveryTasks.containsKey(stateMachine)) return

        val appContext = context.applicationContext
        val task = object : Runnable {
            override fun run() {
                if (stateMachine.activeInjectorType != InjectorType.NOOP) {
                    stopPeriodicRecovery(stateMachine)
                    return
                }

                val recovered = probeBestAvailableInjector(appContext, stateMachine)
                if (recovered != null) {
                    Log.i(TAG, "Periodic recovery success: NOOP -> ${recovered.type}")
                    EventMockLogger.logChannelFallback(
                        from = InjectorType.NOOP,
                        to = recovered.type,
                        reason = "periodic recovery",
                    )
                    stateMachine.setInjector(recovered)
                    stopPeriodicRecovery(stateMachine)
                    return
                }

                mainHandler.postDelayed(this, RECOVERY_RETRY_INTERVAL_MS)
            }
        }

        recoveryTasks[stateMachine] = task
        mainHandler.postDelayed(task, RECOVERY_RETRY_INTERVAL_MS)
        Log.i(TAG, "Started periodic recovery check every ${RECOVERY_RETRY_INTERVAL_MS}ms")
    }

    @Synchronized
    fun stopPeriodicRecovery(stateMachine: TouchStateMachine) {
        val task = recoveryTasks.remove(stateMachine) ?: return
        mainHandler.removeCallbacks(task)
        Log.d(TAG, "Stopped periodic recovery check")
    }

    private fun probeBestAvailableInjector(
        context: Context,
        stateMachine: TouchStateMachine,
    ): TouchInjector? {
        val system = SystemTouchInjector(context)
        if (system.isAvailable()) return system

        val noop = NoopTouchInjector(context)
        val a11y = createAccessibilityInjector(
            context = context,
            stateMachine = stateMachine,
            noop = noop,
            source = "recovery",
        )
        return if (a11y.isAvailable()) a11y else null
    }

    private fun createAccessibilityInjector(
        context: Context,
        stateMachine: TouchStateMachine?,
        noop: NoopTouchInjector,
        source: String,
    ): AccessibilityTouchInjector {
        val a11yInjector = AccessibilityTouchInjector(
            onChannelFailed = {
                Log.w(TAG, "Accessibility channel failed($source), falling back to NOOP")
                EventMockLogger.logChannelFallback(
                    from = InjectorType.ACCESSIBILITY,
                    to = InjectorType.NOOP,
                    reason = "$source onChannelFailed callback",
                )
                stateMachine?.setInjector(noop)
                noop.showAccessibilityGuide()
                stateMachine?.let { startPeriodicRecoveryIfNeeded(context, it) }
            },
        )

        GestureAccessibilityService.onExcessiveDisconnect = {
            Log.w(TAG, "GestureAccessibilityService excessive disconnect($source), falling back to NOOP")
            EventMockLogger.logChannelFallback(
                from = InjectorType.ACCESSIBILITY,
                to = InjectorType.NOOP,
                reason = "$source excessive disconnect",
            )
            stateMachine?.setInjector(noop)
            noop.showAccessibilityGuide()
            stateMachine?.let { startPeriodicRecoveryIfNeeded(context, it) }
        }

        return a11yInjector
    }
}

