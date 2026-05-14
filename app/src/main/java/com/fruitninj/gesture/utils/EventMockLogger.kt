package com.fruitninj.gesture.utils

import android.util.Log
import com.fruitninj.gesture.inject.InjectorType

/**
 * 结构化日志工具，统一记录触摸事件注入全链路可观测信息。
 *
 * 日志 Tag：EventMock（可通过 Logcat 过滤 "EventMock" 追踪单次 Session 完整链路）。
 *
 * 输出格式示例：
 *   I/EventMock: session=42 injector=ACCESSIBILITY event=DOWN x=540 y=1210 t=123456789 dt=123456789
 *   D/EventMock: session=42 injector=ACCESSIBILITY event=MOVE x=548 y=1215 t=123456850 dt=123456789
 *   I/EventMock: session=42 injector=ACCESSIBILITY event=UP x=548 y=1215 t=123458900 dt=123456789 reason=IDLE_TIMEOUT(2000ms)
 */
object EventMockLogger {

    const val LOG_TAG = "EventMock"

    /**
     * 记录注入事件（DOWN/MOVE/UP）。
     *
     * @param sessionId    当前触摸会话 ID
     * @param injectorType 注入通道类型
     * @param eventType    事件类型字符串（"DOWN" / "MOVE" / "UP"）
     * @param x            屏幕像素 x 坐标
     * @param y            屏幕像素 y 坐标
     * @param eventTime    事件时间戳（SystemClock.uptimeMillis()）
     * @param downTime     本次 Session 的按下时间戳
     * @param extra        可选附加信息（如失败原因）
     */
    fun logEvent(
        sessionId: Int,
        injectorType: InjectorType,
        eventType: String,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long,
        extra: String? = null,
    ) {
        val msg = buildString {
            append("session=$sessionId")
            append(" injector=${injectorType.name}")
            append(" event=$eventType")
            append(" x=${x.toInt()}")
            append(" y=${y.toInt()}")
            append(" t=$eventTime")
            append(" dt=$downTime")
            if (!extra.isNullOrBlank()) append(" $extra")
        }
        when (eventType) {
            "MOVE" -> Log.d(LOG_TAG, msg)
            else   -> Log.i(LOG_TAG, msg)
        }
    }

    /**
     * 记录注入失败信息。
     *
     * @param sessionId    当前触摸会话 ID
     * @param injectorType 注入通道类型
     * @param errorMsg     错误摘要
     * @param failCode     可选错误码
     */
    fun logError(
        sessionId: Int,
        injectorType: InjectorType,
        errorMsg: String,
        failCode: Int = -1,
    ) {
        Log.e(
            LOG_TAG,
            "session=$sessionId injector=${injectorType.name} ERROR failCode=$failCode msg=$errorMsg",
        )
    }

    /**
     * 记录通道降级事件。
     *
     * @param from 降级前通道类型
     * @param to   降级后通道类型
     * @param reason 降级原因
     */
    fun logChannelFallback(from: InjectorType, to: InjectorType, reason: String) {
        Log.w(LOG_TAG, "CHANNEL_FALLBACK $from -> $to reason=$reason")
    }
}

