/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fruitninj.gesture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.fruitninj.gesture.fragment.PermissionsFragment
import com.fruitninj.gesture.inject.NoopTouchInjector
import com.fruitninj.gesture.inject.TouchInjectorFactory
import com.fruitninj.gesture.inject.TouchStateMachine
import com.fruitninj.gesturemode.R
import com.fruitninj.gesturemode.databinding.GestureCameraBinding

class FloatingCameraWindow(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: MainViewModel = MainViewModel(),
) {
    companion object {
        private const val TAG = "FloatingCameraWindow"
        private const val INVALID_ROTATION = -1
        private const val CONTROL_EXIT_HIDE_DELAY_MS = 2_000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var containerView: FrameLayout? = null
    private var cameraGestureController: CameraGestureController? = null
    private var previewView: PreviewView? = null
    private var binding: GestureCameraBinding? = null
    private val overlayCropWindow: CropWindow = CropWindow()
    /** 用户独立调整过的映射框，用于跨 View 生命周期持久化恢复。 */
    private val overlayMappingWindow: MappingWindow = MappingWindow()
    private var isWindowAttached: Boolean = false

    /** 是否展示浮窗 UI。 */
    private var isWindowVisible: Boolean = true

    /** 浮窗移除后是否继续分发映射触摸事件，默认开启。 */
    private var dispatchWhenWindowHidden: Boolean = true

    /** 手动控制事件模拟开关，默认开启。 */
    private var isEventSimulationEnabled: Boolean = true

    /** 触摸事件状态机，在 show() 时初始化，hide() 时释放。 */
    private var touchStateMachine: TouchStateMachine? = null

    private val delayedHideRunnable = Runnable {
        Log.i(TAG, "hide floating window after control exit delay=${CONTROL_EXIT_HIDE_DELAY_MS}ms")
        hide()
    }

    /**
     * 切换浮窗 UI 显示状态。
     */
    fun setWindowVisible(visible: Boolean) {
        isWindowVisible = visible
        if (visible) {
            show()
        } else {
            hide()
        }
    }

    private fun storeOverlayWindows() {
        OverlayWindowStore.saveCropWindow(context, overlayCropWindow)
        OverlayWindowStore.saveMappingWindow(context, overlayMappingWindow)
    }

    private fun restoreOverlayWindows() {
        OverlayWindowStore.loadCropWindow(context)?.let {
            overlayCropWindow.centerXNorm = it.centerXNorm
            overlayCropWindow.centerYNorm = it.centerYNorm
            overlayCropWindow.widthNorm = it.widthNorm
            overlayCropWindow.heightNorm = it.heightNorm
        }
        OverlayWindowStore.loadMappingWindow(context)?.let {
            overlayMappingWindow.centerXNorm = it.centerXNorm
            overlayMappingWindow.centerYNorm = it.centerYNorm
            overlayMappingWindow.widthNorm = it.widthNorm
            overlayMappingWindow.heightNorm = it.heightNorm
        }
    }


    /**
     * 显示全屏浮窗
     */
    @SuppressLint("InflateParams")
    fun show() {
        restoreOverlayWindows()
        cancelDelayedHideOnControlExit()
        ensureContainerView()
        ensureTouchStateMachine()
        initializeCameraControllerIfNeeded()

        if (isWindowVisible) {
            attachWindowIfNeeded()
        }

        updateControlsUi()
        updatePreviewToggleButtonText()
        updateEventSimulationToggleButtonText()
        syncTouchDispatchState()
    }

    /**
     * 隐藏全屏浮窗
     */
    fun hide() {
        storeOverlayWindows()
        cancelDelayedHideOnControlExit()
        isWindowVisible = false
        detachWindowIfNeeded()
        syncTouchDispatchState()
    }

    /**
     * 彻底停止引擎并释放所有资源（Service 销毁时调用）。
     */
    fun stop() {
        cancelDelayedHideOnControlExit()
        cameraGestureController?.onPause()
        cameraGestureController?.onDestroy()

        // 释放状态机：补发 UP（若处于 TRACKING）并回到 IDLE
        touchStateMachine?.let { TouchInjectorFactory.stopPeriodicRecovery(it) }
        touchStateMachine?.release()
        touchStateMachine = null

        detachWindowIfNeeded()
        containerView = null
        binding = null
        previewView = null
        cameraGestureController = null
    }

    /**
     * 检查浮窗是否正在显示
     */
    fun isShowing(): Boolean = isWindowAttached

    fun onResume() {
        if (cameraGestureController == null) return
        if (!PermissionsFragment.hasPermissions(context)) {
            Toast.makeText(context, "Camera permission is required", Toast.LENGTH_SHORT).show()
            stop()
            return
        }

        if (isWindowVisible) {
            attachWindowIfNeeded()
            updatePreviewView()
            updatePreviewToggleButtonText()
            updateEventSimulationToggleButtonText()
            updateControlsUi()
        }

        cameraGestureController?.onResume()

        // 从无障碍设置页返回后尝试恢复 ACCESSIBILITY 通道（PRD §7.4 设置回跳恢复）
        touchStateMachine?.let { sm ->
            TouchInjectorFactory.tryRecoverAccessibility(context, sm)
        }
        syncTouchDispatchState()
    }

    fun onPause() {
        cameraGestureController?.onPause()
    }

    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") newConfig: Configuration) {
        if (cameraGestureController == null) return
        cameraGestureController?.onConfigurationChanged()
    }

    /**
     * 初始化相机控制器
     */
    @SuppressLint("MissingPermission")
    private fun initializeCameraControllerIfNeeded() {
        if (cameraGestureController != null) return
        ensureContainerView()

        if (!PermissionsFragment.hasPermissions(context)) {
            Toast.makeText(context, "Camera permission is required", Toast.LENGTH_SHORT).show()
            stop()
            return
        }

        // Setup preview view
        updatePreviewView()
        setupPreviewToggleButton()
        setupEventSimulationToggleButton()
        binding?.overlay?.post {
            binding?.overlay?.attachOverlayRect(overlayCropWindow,overlayMappingWindow)
        }

        // Setup camera gesture controller
        cameraGestureController = CameraGestureController(
            appContext = context.applicationContext,
            lifecycleOwner = lifecycleOwner,
            viewModel = viewModel,
            previewViewProvider = { if (cameraGestureController?.isCameraPreviewEnabled == true) previewView else null },
            displayRotationProvider = { getSurfaceRotation() },
            listener = object : CameraGestureController.Listener {
                override fun onResults(
                    resultBundle: GestureRecognizerHelper.ResultBundle,
                    gestureResult: GestureRecognizerResult?,
                ) {
                    if (gestureResult != null) {
                        binding?.overlay?.setResults(
                            gestureResult,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            RunningMode.LIVE_STREAM,
                            fullImageWidth = resultBundle.fullImageWidth,
                            fullImageHeight = resultBundle.fullImageHeight,
                            cropOffsetX = resultBundle.cropOffsetX,
                            cropOffsetY = resultBundle.cropOffsetY,
                        )
                        // Pass the optimizer's current crop window for debug visualisation.
                        binding?.overlay?.invalidate()
                    }
                }

                override fun onControlModeChanged(enabled: Boolean) {
                    binding?.overlay?.setControlMode(enabled)
                    if (enabled) {
                        cancelDelayedHideOnControlExit()
                        setWindowVisible(true)
                    } else {
                        scheduleDelayedHideOnControlExit()
                    }
                }

                override fun onError(error: String, errorCode: Int) {
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }
            },
        )

        binding?.overlay?.setOnMappedPointPercentListener { pointPrecent ->
            if (!isTouchDispatchAvailable()) {
                touchStateMachine?.onNoPoint()
                return@setOnMappedPointPercentListener
            }

            if (pointPrecent == null) {
                touchStateMachine?.onNoPoint()
                return@setOnMappedPointPercentListener
            }
            val (screenWidth, screenHeight) = getScreenSizePx()
            if (screenWidth <= 0 || screenHeight <= 0) return@setOnMappedPointPercentListener

            val x = (screenWidth * (pointPrecent.xPercent / 100f)).toInt().coerceIn(0, screenWidth)
            val y = (screenHeight * (pointPrecent.yPercent / 100f)).toInt().coerceIn(0, screenHeight)

            // 驱动触摸事件状态机
            touchStateMachine?.onPoint(x.toFloat(), y.toFloat(), screenWidth, screenHeight)
        }


        cameraGestureController?.initialize(overlayCropWindow)
    }

    private fun updateControlsUi() {
        if (cameraGestureController == null || binding == null) return
        binding?.overlay?.clear()
    }

    /**
     * 更新预览视图
     */
    private fun updatePreviewView() {
        val container = containerView ?: return

        if (binding == null) {
            binding = GestureCameraBinding.inflate(LayoutInflater.from(context), container, false)

            container.removeAllViews()
            container.addView(binding?.root)
        }

        previewView = binding?.previewView?.apply {
            scaleType = PreviewView.ScaleType.FILL_START
        }

        previewView?.visibility = if (cameraGestureController?.isCameraPreviewEnabled == true) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun ensureContainerView() {
        if (containerView != null) return
        containerView = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // 设置半透明白色背景色：#99ffffff (60% 透明度)
            setBackgroundColor(android.graphics.Color.parseColor("#99ffffff"))
        }
    }

    private fun ensureTouchStateMachine() {
        if (touchStateMachine != null) return
        val appContext = context.applicationContext
        val stateMachine = TouchStateMachine(injector = NoopTouchInjector(appContext))
        val injector = TouchInjectorFactory.create(appContext, stateMachine)
        stateMachine.setInjector(injector)
        touchStateMachine = stateMachine
    }

    private fun attachWindowIfNeeded() {
        val container = containerView ?: return
        if (isWindowAttached) return

        try {
            windowManager.addView(container, createWindowLayoutParams())
            isWindowAttached = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating window", e)
        }
    }

    private fun detachWindowIfNeeded() {
        val container = containerView ?: return
        if (!isWindowAttached) return

        try {
            windowManager.removeView(container)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach floating window", e)
        } finally {
            isWindowAttached = false
        }
    }

    private fun createWindowLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            format = android.graphics.PixelFormat.TRANSLUCENT
            // 保持不可聚焦，但允许触摸以支持编辑手势和隐藏按钮点击。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // 固定宽高 不要改动
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun shouldForwardMappedTouch(): Boolean {
        // New policy: while floating UI is visible, do not dispatch mapped touch events.
        return !isWindowAttached && dispatchWhenWindowHidden
    }

    private fun isTouchDispatchAvailable(): Boolean {
        return shouldForwardMappedTouch() && isEventSimulationEnabled
    }

    private fun syncTouchDispatchState() {
        touchStateMachine?.setDispatchEnabled(isTouchDispatchAvailable())
        syncRecognitionCropState()
    }

    private fun syncRecognitionCropState() {
        // 浮窗不显示，但是可分发事件时，启用裁剪
        val enabled = !isWindowAttached && isTouchDispatchAvailable()
        cameraGestureController?.setCropEnabled(enabled)
    }

    private fun scheduleDelayedHideOnControlExit() {
        mainHandler.removeCallbacks(delayedHideRunnable)
        mainHandler.postDelayed(delayedHideRunnable, CONTROL_EXIT_HIDE_DELAY_MS)
    }

    private fun cancelDelayedHideOnControlExit() {
        mainHandler.removeCallbacks(delayedHideRunnable)
    }

    private fun setupEventSimulationToggleButton() {
        updateEventSimulationToggleButtonText()
        binding?.btnToggleEventSimulation?.setOnClickListener {
            isEventSimulationEnabled = !isEventSimulationEnabled
            syncTouchDispatchState()
            updateEventSimulationToggleButtonText()
        }
    }

    private fun setupPreviewToggleButton() {
        updatePreviewToggleButtonText()
        binding?.btnTogglePreview?.setOnClickListener {
            cameraGestureController?.isCameraPreviewEnabled = !(cameraGestureController?.isCameraPreviewEnabled?:false)
            updatePreviewView()
            updatePreviewToggleButtonText()
            cameraGestureController?.rebindCameraUseCases()
        }
        binding?.btnHideFloatingWindow?.setOnClickListener {
            hide()
        }
    }

    private fun updatePreviewToggleButtonText() {
        binding?.btnTogglePreview?.text = if (cameraGestureController?.isCameraPreviewEnabled == true) {
            context.getString(R.string.hide_preview)
        } else {
            context.getString(R.string.show_preview)
        }
    }

    private fun updateEventSimulationToggleButtonText() {
        binding?.btnToggleEventSimulation?.text = if (isEventSimulationEnabled) {
            context.getString(R.string.disable_event_simulation)
        } else {
            context.getString(R.string.enable_event_simulation)
        }
    }

    /**
     * 获取当前屏幕旋转角度
     */
    private fun getSurfaceRotation(): Int {
        val previewRotation = previewView?.display?.rotation
        if (isValidSurfaceRotation(previewRotation)) return previewRotation!!

        val containerRotation = binding?.previewContainer?.display?.rotation
        if (isValidSurfaceRotation(containerRotation)) return containerRotation!!

        val contextDisplayRotation = resolveActivityDisplayRotation()
        if (isValidSurfaceRotation(contextDisplayRotation)) return contextDisplayRotation!!

        val windowRotation = try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation
        } catch (_: Throwable) {
            null
        }
        if (isValidSurfaceRotation(windowRotation)) return windowRotation!!

        return INVALID_ROTATION
    }

    private fun resolveActivityDisplayRotation(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return try {
                    current.display?.rotation
                } catch (_: UnsupportedOperationException) {
                    null
                } catch (_: Throwable) {
                    null
                }
            }
            current = current.baseContext
        }
        return null
    }

    private fun isValidSurfaceRotation(rotation: Int?): Boolean {
        return rotation == Surface.ROTATION_0 ||
                rotation == Surface.ROTATION_90 ||
                rotation == Surface.ROTATION_180 ||
                rotation == Surface.ROTATION_270
    }

    private fun getScreenSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

}
