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

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.fruitninj.gesturemode.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private var usingFloatingWindow = false
    private var floatingCameraVisible = false
    private var cameraPermissionRequestedForFloatingWindow = false
    private var overlayPermissionRequestInProgress = false
    private var overlayPermissionPrompted = false

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            cameraPermissionRequestedForFloatingWindow = !isGranted
            if (isGranted) {
                initializeUI()
            } else {
                Log.w(TAG, "Camera permission denied, floating camera service not started")
            }
        }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        // Initialize and decide which UI to use
        initializeUI()
        printOrientation(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        printOrientation(newConfig)
    }

    private fun printOrientation(newConfig: Configuration) {
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏
            Log.i("ScreenOrientation", "Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // 竖屏
            Log.i("ScreenOrientation", "Portrait")
        }
    }

    override fun onPause() {
        super.onPause()
        // 浮窗会自动处理 pause 事件
        if (usingFloatingWindow) {
            Log.d(TAG, "Activity paused - floating window continues to run")
        }
    }

    override fun onResume() {
        super.onResume()
        if (overlayPermissionRequestInProgress) {
            // Returned from overlay permission settings screen.
            overlayPermissionRequestInProgress = false
        }
        initializeUI()
        // 浮窗会自动处理 resume 事件
        if (usingFloatingWindow && hasCameraPermission()) {
            Log.d(TAG, "Activity resumed - floating window resumed")
        }
    }

    /**
     * 初始化 UI - 根据权限选择使用浮窗还是 CameraFragment
     */
    private fun initializeUI() {
        if (canDrawOverlays()) {
            // 有浮窗权限，使用浮窗
            Log.i(TAG, "SYSTEM_ALERT_WINDOW permission available, using Floating Window")

            usingFloatingWindow = true
            overlayPermissionPrompted = false
            if (hasCameraPermission()) {
                cameraPermissionRequestedForFloatingWindow = false
                startFloatingCameraService()
                finish()
            } else if (!cameraPermissionRequestedForFloatingWindow) {
                cameraPermissionRequestedForFloatingWindow = true
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            // 没有浮窗权限，使用 CameraFragment
            Log.i(TAG, "SYSTEM_ALERT_WINDOW permission not available, using CameraFragment")
            usingFloatingWindow = false
            floatingCameraVisible = false
            cameraPermissionRequestedForFloatingWindow = false
            stopFloatingCameraService()

            // 主动引导用户前往系统设置页开启浮窗权限（仅提示一次，避免循环跳转）
            if (!overlayPermissionPrompted && !overlayPermissionRequestInProgress) {
                requestOverlayPermission()
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有浮窗权限
     */
    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun startFloatingCameraService() {
        floatingCameraVisible = true
        FloatingCameraService.start(this)
    }

    private fun stopFloatingCameraService() {
        floatingCameraVisible = false
        FloatingCameraService.stop(this)
    }

    /**
     * 显示全屏浮窗相机（仅在有权限时有效）
     */
    fun showFloatingCamera() {
        if (usingFloatingWindow) {
            if (hasCameraPermission()) {
                startFloatingCameraService()
            } else if (!cameraPermissionRequestedForFloatingWindow) {
                cameraPermissionRequestedForFloatingWindow = true
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            Log.w(TAG, "Not using floating window. SYSTEM_ALERT_WINDOW permission not available.")
        }
    }

    /**
     * 隐藏全屏浮窗相机（仅在有权限时有效）
     */
    fun hideFloatingCamera() {
        if (usingFloatingWindow) {
            stopFloatingCameraService()
        }
    }

    /**
     * 切换浮窗显示状态（仅在有权限时有效）
     */
    @Suppress("unused")
    fun toggleFloatingCamera() {
        if (usingFloatingWindow && floatingCameraVisible) {
            hideFloatingCamera()
        } else if (usingFloatingWindow) {
            showFloatingCamera()
        }
    }

    /**
     * 运行时请求浮窗权限（可选）
     */
    @Suppress("unused")
    fun requestOverlayPermission() {
        if (!canDrawOverlays()) {
            overlayPermissionPrompted = true
            overlayPermissionRequestInProgress = true
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }
}
