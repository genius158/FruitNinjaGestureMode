package com.fruitninj.gesture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.fruitninj.gesturemode.R

class FloatingCameraService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "FloatingCameraService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_camera_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Floating Camera"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fruitninj.gesture.action.START_FLOATING_CAMERA"
        const val ACTION_STOP = "com.fruitninj.gesture.action.STOP_FLOATING_CAMERA"

        fun start(context: Context) {
            val intent = Intent(context, FloatingCameraService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCameraService::class.java))
        }
    }

    private val viewModel = MainViewModel()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var floatingCameraWindow: FloatingCameraWindow? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing camera or overlay permission, stopping floating camera service")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForegroundService()
        showFloatingWindow()
        floatingCameraWindow?.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingCameraWindow?.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        floatingCameraWindow?.onPause()
        floatingCameraWindow?.stop()
        floatingCameraWindow = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFloatingWindow() {
        if (floatingCameraWindow == null) {
            floatingCameraWindow = FloatingCameraWindow(
                context = this,
                lifecycleOwner = this,
                viewModel = viewModel,
            )
        }
        // 使用 setWindowVisible(true) 而非 show()：
        // 每次 MainActivity 进入前台触发本方法时，强制重置可见状态并挂载到 WindowManager，
        // 确保用户之前手动隐藏的浮窗能在回到 MainActivity 后自动恢复。
        floatingCameraWindow?.setWindowVisible(true)
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasCameraPermission() && canDrawOverlays()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCameraService::class.java).apply {
                action = ACTION_STOP
            },
            pendingIntentFlags,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_camera_notification_title))
            .setContentText(getString(R.string.floating_camera_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.floating_camera_notification_stop),
                stopIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.floating_camera_notification_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
}



