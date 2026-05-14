package com.fruitninj.gesture.inject

/**
 * TouchInjector 接口 — 抽象触摸事件注入通道。
 *
 * 实现方包括：SystemTouchInjector、AccessibilityTouchInjector、NoopTouchInjector。
 * 由 TouchInjectorFactory 按运行环境选择最优通道。
 */
interface TouchInjector {

    /** 通道类型标识。 */
    val type: InjectorType

    /**
     * 检查当前通道是否可用。
     * 在注入前始终应先调用此方法确认可用性。
     */
    fun isAvailable(): Boolean

    /**
     * 注入 ACTION_DOWN 事件（按下）。
     *
     * @param x         屏幕像素 x 坐标
     * @param y         屏幕像素 y 坐标
     * @param downTime  本次 Session 的按下时间戳（SystemClock.uptimeMillis()）
     */
    fun injectDown(x: Float, y: Float, downTime: Long)

    /**
     * 注入 ACTION_MOVE 事件（移动）。
     *
     * @param x         屏幕像素 x 坐标
     * @param y         屏幕像素 y 坐标
     * @param eventTime 事件时间戳（SystemClock.uptimeMillis()）
     * @param downTime  本次 Session 的按下时间戳（保持不变）
     */
    fun injectMove(x: Float, y: Float, eventTime: Long, downTime: Long)

    /**
     * 注入 ACTION_UP 事件（抬起）。
     *
     * @param x         屏幕像素 x 坐标
     * @param y         屏幕像素 y 坐标
     * @param eventTime 事件时间戳（SystemClock.uptimeMillis()）
     * @param downTime  本次 Session 的按下时间戳（保持不变）
     */
    fun injectUp(x: Float, y: Float, eventTime: Long, downTime: Long)
}

/**
 * 注入通道类型枚举，按优先级排列：SYSTEM > ACCESSIBILITY > NOOP。
 */
enum class InjectorType {
    /** 系统应用通道：InputManager.injectInputEvent（需系统签名权限）。 */
    SYSTEM,

    /** 无障碍服务通道：AccessibilityService.dispatchGesture（需用户开启无障碍服务）。 */
    ACCESSIBILITY,

    /** 空实现通道：日志记录 + Toast 引导，不产生真实触摸事件。 */
    NOOP,
}

