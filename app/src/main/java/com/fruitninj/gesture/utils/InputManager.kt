package com.fruitninj.gesture.utils

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 时间管理器
 */
object InputManager {
    const val INJECT_INPUT_EVENT_MODE_ASYNC: Int = 0
    const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT: Int = 1
    const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: Int = 2

    @get:Throws(NoSuchMethodException::class)
    private var injectInputEventMethod: Method? = null
        get() {
            if (field == null) {
                field = InputManager::class.java.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
            }
            return field
        }

    fun injectInputEvent(context: Context, inputEvent: InputEvent?, mode: Int): Boolean {
        try {
            val method =
                injectInputEventMethod!!
            val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager?
            return method.invoke(manager, inputEvent, mode) as Boolean
        } catch (e: InvocationTargetException) {
            return false
        } catch (e: IllegalAccessException) {
            return false
        } catch (e: NoSuchMethodException) {
            return false
        }
    }
}
