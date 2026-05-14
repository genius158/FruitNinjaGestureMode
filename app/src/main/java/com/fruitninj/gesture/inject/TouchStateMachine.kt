package com.fruitninj.gesture.inject

import android.util.Log
import com.fruitninj.gesture.utils.EventMockLogger
import kotlin.math.abs

/**
 * 触摸事件状态机。
 *
 * 状态：
 *   UP    — 无进行中的触摸会话。
 *   MOVE  — 已按下，持续跟踪移动中。
 *
 * 核心行为约束：
 *   - UP + 有效坐标       → DOWN → MOVE
 *   - MOVE + 坐标变化 ≥ 2px 且间隔 ≥ 16ms → MOVE（保持 MOVE）
 *   - MOVE + 坐标变化 ≥ 2px 但间隔 < 16ms → 暂存最新点位，目标时间优先发送最新点位
 *   - MOVE + 超过 [idleTimeoutMs] 无真实 DOWN/MOVE 分发 → UP → UP
 *   - 生命周期结束 / 通道失效 → 补发 UP（尽力而为）→ UP
 *
 * @param injector          当前激活注入通道，可在运行时替换（factory 降级时调用 setInjector）。
 * @param idleTimeoutMs     无输入超时阈值，默认 2000ms，建议范围 500~5000ms。
 * @param clock             时钟抽象，生产代码使用 SystemClockWrapper，测试代码注入 FakeClock。
 * @param timeoutScheduler  空闲超时调度器抽象，生产代码使用 HandlerTimeoutScheduler，
 *                          测试代码注入 NoOpTimeoutScheduler（关闭自动超时）。
 * @param moveScheduler     MOVE 延迟调度器，用于限频窗口结束后补发最新 MOVE。
 */
class TouchStateMachine(
    @Volatile private var injector: TouchInjector,
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val clock: Clock = SystemClockWrapper,
    private val timeoutScheduler: TimeoutScheduler = HandlerTimeoutScheduler(),
) {
    private var moveScheduler: TimeoutScheduler = HandlerTimeoutScheduler()

    constructor(
        injector: TouchInjector,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        clock: Clock = SystemClockWrapper,
        timeoutScheduler: TimeoutScheduler = HandlerTimeoutScheduler(),
        moveScheduler: TimeoutScheduler,
    ) : this(
        injector = injector,
        idleTimeoutMs = idleTimeoutMs,
        clock = clock,
        timeoutScheduler = timeoutScheduler,
    ) {
        this.moveScheduler = moveScheduler
    }

    companion object {
        private const val TAG = "TouchStateMachine"
        const val DEFAULT_IDLE_TIMEOUT_MS = 1200L
        private const val TAP_EVENT_GAP_MS = 24L

        /** MOVE 去抖阈值（像素）。 */
        const val DEBOUNCE_PX = 40f

        /** MOVE 最大发送频率限制（毫秒），对应 60Hz。 */
        const val MIN_MOVE_INTERVAL_MS = 16L
    }

    // ----------------------------- 状态枚举 -----------------------------

    enum class State { DOWN, MOVE, UP }

    // ----------------------------- 内部状态 -----------------------------

    @Volatile
    var currentState: State = State.UP
        private set

    /**
     * 当前激活注入通道的类型（只读），用于工厂恢复逻辑判断是否需要切换通道。
     * 不暴露 injector 本身，仅暴露类型以保持最小接口。
     */
    val activeInjectorType: InjectorType
        get() = injector.type

    /** 当前 Session 的按下时间戳（downTime），Session 结束后清零。 */
    private var sessionDownTime: Long = 0L

    /** 上次发送 MOVE 的时间戳，用于 60Hz 限频。 */
    private var lastMoveEventTime: Long = 0L

    /** 上次发送 MOVE 的坐标，用于 2px 去抖。 */
    private var lastMoveX: Float = 0f
    private var lastMoveY: Float = 0f

    /** 最后一次收到有效坐标的坐标，用于超时 UP 时获取最终位置。 */
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    /** 因限频被延迟发送的最新 MOVE 坐标。 */
    private var hasPendingMove: Boolean = false
    private var pendingMoveX: Float = 0f
    private var pendingMoveY: Float = 0f
    private var pendingMoveDueTime: Long = 0L

    /** 会话 ID，每次 DOWN 时自增，用于日志追踪。 */
    private var sessionId: Int = 0

    /** 是否允许向注入通道分发触摸事件。 */
    @Volatile
    private var dispatchEnabled: Boolean = true
    // ----------------------------- 公开 API -----------------------------

    /**
     * 收到有效坐标（由 OverlayView 坐标回调驱动）。
     */
    fun onPoint(x: Float, y: Float) {
        if (!dispatchEnabled) return
        if (currentState == State.UP) {
            performDown(x, y)
        } else if (currentState == State.MOVE) {
            maybePerformMove(x, y)
        }
    }
    fun onPoint(x: Float, y: Float, width: Int, height: Int) {
        if (!dispatchEnabled) return
        if (currentState == State.UP) {
            val offset = 80F
            var fx = Math.min(x, width - offset)
            fx = Math.max(fx, offset)
            var fy =  Math.min(y, height - offset)
            fy = Math.max(fy,offset)

            performDown(fx ,fy)
        } else if (currentState == State.MOVE) {
            maybePerformMove(x, y)
        }
    }

    /**
     * 无有效坐标（手势消失或无法定位）。
     * 状态机不立即发 UP，而是等超时触发。
     * 若业务层需要立即结束 Session，调用 release()。
     */
    fun onNoPoint() {
        if (!dispatchEnabled) return
        // 不主动发 UP，依赖超时调度
    }

    /**
     * 主动触发一次点击（DOWN -> UP）。
     * 若当前存在进行中的拖拽会话，会先补发 UP 收敛后再执行点击。
     */
    fun performTap(x: Float, y: Float): Boolean {
        if (!dispatchEnabled) return false

        timeoutScheduler.cancel()
        if (currentState == State.MOVE) {
            performUp(reason = "TAP_PREEMPT")
        }

        val downTime = clock.uptimeMillis()
        val upTime = downTime + TAP_EVENT_GAP_MS
        sessionId++
        sessionDownTime = downTime
        lastX = x
        lastY = y
        lastMoveX = x
        lastMoveY = y
        lastMoveEventTime = 0L

        EventMockLogger.logEvent(
            sessionId = sessionId,
            injectorType = injector.type,
            eventType = "DOWN",
            x = x,
            y = y,
            eventTime = downTime,
            downTime = downTime,
            extra = "reason=TAP",
        )
        safeInject { injector.injectDown(x, y, downTime) }

        EventMockLogger.logEvent(
            sessionId = sessionId,
            injectorType = injector.type,
            eventType = "UP",
            x = x,
            y = y,
            eventTime = upTime,
            downTime = downTime,
            extra = "reason=TAP",
        )
        safeInject { injector.injectUp(x, y, upTime, downTime) }

        currentState = State.UP
        sessionDownTime = 0L
        return true
    }

    /**
     * 生命周期结束（浮窗 hide / Fragment onDestroyView）时调用。
     * 若处于 TRACKING 状态，补发 UP 后回到 IDLE。
     */
    fun release() {
        timeoutScheduler.cancel()
        clearPendingMove()
        if (currentState != State.UP) {
            performUp(reason = "RELEASE")
        }
    }

    /**
     * 运行时替换注入通道（如工厂降级时调用）。
     */
    fun setInjector(newInjector: TouchInjector) {
        injector = newInjector
    }

    /**
     * 开关触摸事件分发。关闭时会主动收敛当前会话并取消超时调度。
     */
    fun setDispatchEnabled(enabled: Boolean) {
        if (dispatchEnabled == enabled) return
        dispatchEnabled = enabled

        if (!enabled) {
            timeoutScheduler.cancel()
            clearPendingMove()
            if (currentState != State.UP) {
                performUp(reason = "DISPATCH_DISABLED")
            }
        }
    }

    fun isDispatchEnabled(): Boolean = dispatchEnabled

    // ----------------------------- 内部实现 -----------------------------

    private fun performDown(x: Float = lastX, y: Float = lastY) {
        clearPendingMove()
        val now = clock.uptimeMillis()
        sessionId++
        sessionDownTime = now
        lastMoveEventTime = 0L
        lastMoveX = x
        lastMoveY = y
        lastX = x
        lastY = y
        currentState = State.MOVE

        EventMockLogger.logEvent(
            sessionId = sessionId,
            injectorType = injector.type,
            eventType = "DOWN",
            x = x,
            y = y,
            eventTime = now,
            downTime = now,
        )

        safeInject { injector.injectDown(x, y, now) }
        refreshIdleTimeout()
    }

    private fun maybePerformMove(x: Float, y: Float) {
        val now = clock.uptimeMillis()
        lastX = x
        lastY = y

        // 2px 去抖：坐标变化 < DEBOUNCE_PX 时跳过
        val dx = abs(x - lastMoveX)
        val dy = abs(y - lastMoveY)
        if (dx < DEBOUNCE_PX && dy < DEBOUNCE_PX) {
            clearPendingMove()
            return
        }

        // 60Hz 限频：两次 MOVE 间隔 < 16ms 时延迟发送最新点位
        if (lastMoveEventTime > 0 && (now - lastMoveEventTime) < MIN_MOVE_INTERVAL_MS) {
            enqueuePendingMove(
                x = x,
                y = y,
                dueTime = lastMoveEventTime + MIN_MOVE_INTERVAL_MS,
                now = now,
            )
            return
        }

        clearPendingMove()
        dispatchMove(x, y, now)
    }

    private fun performUp(reason: String) {
        clearPendingMove()
        val now = clock.uptimeMillis()
        val x = lastX
        val y = lastY
        currentState = State.UP

        EventMockLogger.logEvent(
            sessionId = sessionId,
            injectorType = injector.type,
            eventType = "UP",
            x = x,
            y = y,
            eventTime = now,
            downTime = sessionDownTime,
            extra = "reason=$reason",
        )

        safeInject { injector.injectUp(x, y, now, sessionDownTime) }
        sessionDownTime = 0L
    }

    private fun enqueuePendingMove(x: Float, y: Float, dueTime: Long, now: Long) {
        hasPendingMove = true
        pendingMoveX = x
        pendingMoveY = y
        pendingMoveDueTime = dueTime

        val delayMs = (dueTime - now).coerceAtLeast(0L)
        moveScheduler.schedule(delayMs) {
            flushPendingMove()
        }
    }

    private fun flushPendingMove() {
        if (!hasPendingMove) return
        if (!dispatchEnabled || currentState != State.MOVE) {
            clearPendingMove()
            return
        }

        val x = pendingMoveX
        val y = pendingMoveY
        val dueTime = pendingMoveDueTime
        hasPendingMove = false
        pendingMoveDueTime = 0L

        val now = clock.uptimeMillis()
        val eventTime = if (now >= dueTime) now else dueTime
        dispatchMove(x, y, eventTime)
    }

    private fun clearPendingMove() {
        moveScheduler.cancel()
        hasPendingMove = false
        pendingMoveDueTime = 0L
    }

    private fun dispatchMove(x: Float, y: Float, eventTime: Long) {
        lastMoveX = x
        lastMoveY = y
        lastMoveEventTime = eventTime
        lastX = x
        lastY = y

        if (currentState != State.UP) {
            safeInject { injector.injectMove(x, y, eventTime, sessionDownTime) }
            refreshIdleTimeout()
        }
    }

    /** 仅在真实 DOWN/MOVE 分发后刷新空闲计时，避免抖动点位持续续命。 */
    private fun refreshIdleTimeout() {
        if (!dispatchEnabled || currentState != State.MOVE) return
        timeoutScheduler.schedule(idleTimeoutMs) {
            if (currentState == State.MOVE) {
                Log.d(TAG, "Idle timeout reached, sending UP")
                performUp(reason = "IDLE_TIMEOUT(${idleTimeoutMs}ms)")
            }
        }
    }

    /** 捕获注入时的所有异常，失败后尝试将状态收敛到 IDLE。 */
    private fun safeInject(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Inject failed: ${e.message}", e)
            EventMockLogger.logError(
                sessionId = sessionId,
                injectorType = injector.type,
                errorMsg = e.message ?: "unknown",
            )
            // 注入失败时回收状态
            if (currentState == State.MOVE) {
                clearPendingMove()
                currentState = State.UP
                sessionDownTime = 0L
            }
        }
    }

    // ----------------------------- 时钟抽象（可测试性） -----------------------------

    /** 时钟接口，用于在单元测试中注入 FakeClock。 */
    interface Clock {
        fun uptimeMillis(): Long
    }

    /** 生产环境时钟实现，使用 SystemClock.uptimeMillis()。 */
    object SystemClockWrapper : Clock {
        override fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()
    }

    // ----------------------------- 超时调度器抽象（可测试性） -----------------------------

    /**
     * 超时调度器接口。
     * 生产环境使用 Android Handler；单元测试中使用空实现（手动触发 release）。
     */
    interface TimeoutScheduler {
        /**
         * 调度一个延迟回调；每次调用前取消上一次已排队的回调。
         */
        fun schedule(delayMs: Long, action: () -> Unit)

        /** 取消已排队的回调。 */
        fun cancel()
    }

    /**
     * 生产环境超时调度器：使用主线程 Handler.postDelayed。
     * 仅在 Android 运行时初始化，JVM 单元测试中不会被实例化。
     */
    class HandlerTimeoutScheduler : TimeoutScheduler {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingAction: Runnable? = null

        override fun schedule(delayMs: Long, action: () -> Unit) {
            pendingAction?.let { handler.removeCallbacks(it) }
            val runnable = Runnable { action() }
            pendingAction = runnable
            handler.postDelayed(runnable, delayMs)
        }

        override fun cancel() {
            pendingAction?.let { handler.removeCallbacks(it) }
            pendingAction = null
        }
    }

    /**
     * 单元测试用空实现：不自动触发超时，由测试显式调用 release() 模拟超时。
     */
    class NoOpTimeoutScheduler : TimeoutScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit) { /* no-op */
        }

        override fun cancel() { /* no-op */
        }
    }
}
