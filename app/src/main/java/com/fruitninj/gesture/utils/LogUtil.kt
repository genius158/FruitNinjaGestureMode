package com.fruitninj.gesture.utils

import android.util.Log

/**
 * 日志工具类，支持定义时间间隔内的日志过滤
 *
 * 功能：
 * - 定义日志打印的时间间隔
 * - 在指定时间间隔内过滤相同的日志（不重复打印）
 * - 超过时间间隔后，再次允许打印该日志
 */
object LogUtil {

    private const val DEFAULT_TAG = "LogUtil"
    private const val DEFAULT_INTERVAL_MS = 5000L // 默认5秒

    // 存储日志的最后打印时间戳和内容
    // Key: 日志标签和消息的组合哈希值
    // Value: 最后打印的时间戳
    private val logTimestamps = mutableMapOf<String, Long>()

    // 存储各个tag的时间间隔设置
    private val intervalMap = mutableMapOf<String, Long>()

    private val lock = Any()

    /**
     * 为指定的tag设置日志时间间隔
     *
     * @param tag 日志标签
     * @param intervalMs 时间间隔（毫秒）
     */
    fun setInterval(tag: String, intervalMs: Long) {
        synchronized(lock) {
            intervalMap[tag] = intervalMs
        }
    }

    /**
     * 获取指定tag的时间间隔，如果未设置则返回默认值
     *
     * @param tag 日志标签
     * @return 时间间隔（毫秒）
     */
    private fun getInterval(tag: String): Long {
        synchronized(lock) {
            return intervalMap[tag] ?: DEFAULT_INTERVAL_MS
        }
    }

    /**
     * 打印Debug级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun d(tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, msg,msg)) {
            if (tr != null) {
                Log.d(tag, msg, tr)
            } else {
                Log.d(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Info级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun i(tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, msg,msg)) {
            if (tr != null) {
                Log.i(tag, msg, tr)
            } else {
                Log.i(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Warning级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun w(tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, msg,msg)) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Error级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun e(tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, msg,msg)) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Debug级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun d(hash:String, tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, hash,msg)) {
            if (tr != null) {
                Log.d(tag, msg, tr)
            } else {
                Log.d(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Info级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun i(hash:String,tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, hash,msg)) {
            if (tr != null) {
                Log.i(tag, msg, tr)
            } else {
                Log.i(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Warning级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun w(hash:String,tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, hash,msg)) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 打印Error级别日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param tr 异常信息（可选）
     * @return 是否打印了日志
     */
    fun e(hash:String,tag: String, msg: String, tr: Throwable? = null): Boolean {
        return if (shouldLog(tag, hash,msg)) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
            true
        } else {
            false
        }
    }

    /**
     * 无过滤地打印日志（始终打印，不受时间间隔限制）
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @param level 日志级别 (d, i, w, e)
     */
    fun logWithoutFilter(tag: String, msg: String, level: String = "d") {
        when (level.lowercase()) {
            "d" -> Log.d(tag, msg)
            "i" -> Log.i(tag, msg)
            "w" -> Log.w(tag, msg)
            "e" -> Log.e(tag, msg)
            else -> Log.d(tag, msg)
        }
    }

    /**
     * 检查是否应该打印日志
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @return 是否应该打印
     */
    private fun shouldLog(tag: String, hash: String, msg: String): Boolean {
        synchronized(lock) {
            val currentTime = System.currentTimeMillis()
            val interval = getInterval(tag)

            val lastLogTime = logTimestamps[hash]

            // 如果没有记录过，或者超过了时间间隔，允许打印
            return if (lastLogTime == null || (currentTime - lastLogTime) >= interval) {
                logTimestamps[hash] = currentTime
                true
            } else {
                false
            }
        }
    }

    /**
     * 生成日志的键值（tag + msg的组合）
     *
     * @param tag 日志标签
     * @param msg 日志消息
     * @return 键值
     */
    private fun generateKey(tag: String, msg: String): String {
        return msg
    }

    /**
     * 清空所有日志记录
     */
    fun clearAll() {
        synchronized(lock) {
            logTimestamps.clear()
        }
    }

    /**
     * 清空指定tag的日志记录
     *
     * @param tag 日志标签
     */
    fun clearTag(tag: String) {
        synchronized(lock) {
            logTimestamps.entries.removeAll { it.key.startsWith(tag) }
        }
    }

    /**
     * 重置指定tag的时间间隔为默认值
     *
     * @param tag 日志标签
     */
    fun resetInterval(tag: String) {
        synchronized(lock) {
            intervalMap.remove(tag)
        }
    }

    /**
     * 获取当前记录的日志条数
     *
     * @return 日志条数
     */
    fun getLogCount(): Int {
        synchronized(lock) {
            return logTimestamps.size
        }
    }
}