package com.fruitninj.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class CameraGestureController(
    private val appContext: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: MainViewModel,
    private val previewViewProvider: () -> PreviewView?,
    private val displayRotationProvider: () -> Int,
    private val listener: Listener,
) : GestureRecognizerHelper.GestureRecognizerListener {

    companion object {
        private const val TAG = "Hand gesture recognizer"
        private const val UNKNOWN_CAMERA_ID = "unknown"
        private const val INVALID_ROTATION = -1

        // Force camera target rotation by +90 degrees only on the specific car GSI model.
        private const val CAR_GSI_ARM64_MODEL = "Car GSI on arm64"
        private const val FORCE_ROTATION_OFFSET_STEPS = 1
        private const val THUMB_TIP_INDEX = 4
        private const val MIDDLE_FINGER_TIP_INDEX = 12
        private const val CLOSED_FIST_GESTURE_NAME = "closed_fist"
        private const val CLOSED_FIST_SCORE_THRESHOLD = 0.6f
        private const val ENTRY_CONTROL_HOLD_MS = 3_000L
        private const val CONTROL_STABLE_EXIT_MS = 2_000L
        private const val CONTROL_STILL_MOVE_EPS_NORM = 0.004f
        private const val CONTROL_STILL_SIZE_EPS_NORM = 0.01f
        private const val CONTROL_DIAGNOSTIC_LOG_INTERVAL_MS = 500L
        private const val HAND_NEAR_SIZE_THRESHOLD = 0.22f
        private const val HAND_MID_SIZE_THRESHOLD = 0.14f
        private const val HAND_NEAR_DEPTH_THRESHOLD = -0.04f
        private const val HAND_MID_DEPTH_THRESHOLD = -0.015f
    }

    interface Listener {
        fun onResults(
            resultBundle: GestureRecognizerHelper.ResultBundle,
            gestureResult: GestureRecognizerResult?,
        )

        fun onControlModeChanged(enabled: Boolean)

        fun onError(error: String, errorCode: Int)
    }

    private enum class ControlState {
        IDLE,
        ENTRY_CONTROL,
    }

    private enum class HandProximity {
        NEAR,
        MID,
        FAR,
    }

    private data class HandLandmarkSample(
        val handLabel: String?,
        val centerX: Float,
        val centerY: Float,
        val thumbX: Float,
        val thumbY: Float,
        val middleX: Float,
        val middleY: Float,
        val thumbMiddleDistance: Float,
        val averageDepthZ: Float,
        val handSizeEstimate: Float,
        val isClosedFist: Boolean,
    )

    private val mainExecutor by lazy { ContextCompat.getMainExecutor(appContext) }
    private val forceRotation90Enabled: Boolean =
        Build.MODEL.equals(CAR_GSI_ARM64_MODEL, ignoreCase = true)

    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var initialized = false
    private var lastAppliedTargetRotation: Int? = null

    private var controlState = ControlState.IDLE
    private var preControlStartMs: Long = 0L
    private var controlInactivityStartMs: Long = 0L
    private var lastControlHandSample: HandLandmarkSample? = null

    private var lastLoggedCameraCapabilityId: String? = null

    var isCameraPreviewEnabled: Boolean = false
    var lastControlDiagnosticLogMs: Long = 0L


    fun initialize(cropWindow: CropWindow) {
        if (initialized) return

        backgroundExecutor = Executors.newSingleThreadExecutor()
        initialized = true

        setUpCamera()
        backgroundExecutor.execute {
            gestureRecognizerHelper = GestureRecognizerHelper(
                context = appContext,
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                gestureRecognizerListener = this,
                cropWindow = cropWindow
            )
        }
    }


    fun setCropEnabled(enabled: Boolean) {
        backgroundExecutor.execute {
            if (this::gestureRecognizerHelper.isInitialized) {
                gestureRecognizerHelper.setCropEnabled(enabled)
            }
        }
    }

    fun onResume() {
        if (!initialized) return

        backgroundExecutor.execute {
            if (this::gestureRecognizerHelper.isInitialized && gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }

        if (cameraProvider != null) {
            syncUseCaseRotation(reason = "onResume")
            bindCameraUseCases()
        }
    }

    fun onPause() {
        if (!initialized) return

        resetControlState(emitExitEvent = false)
        if (this::gestureRecognizerHelper.isInitialized) {
            viewModel.setMinHandDetectionConfidence(gestureRecognizerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(gestureRecognizerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(gestureRecognizerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(gestureRecognizerHelper.currentDelegate)

            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }
    }

    fun onDestroy() {
        if (!initialized) return

        if (this::gestureRecognizerHelper.isInitialized) {
            backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
        }

        backgroundExecutor.shutdown()
        try {
            backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Background executor termination interrupted", interrupted)
        }

        initialized = false
    }

    fun onConfigurationChanged() {
        syncUseCaseRotation(reason = "onConfigurationChanged")
    }


    fun rebindCameraUseCases() {
        if (cameraProvider != null) {
            syncUseCaseRotation(reason = "rebind")
            bindCameraUseCases()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            mainExecutor,
        )
    }

    // Declare and bind preview and analysis use cases.
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // Always apply the forced rotation offset when building use cases so that
        // the initial targetRotation is correct even when preview is hidden.
        val baseRotation = resolveDisplayRotation()
        val targetRotation = applyForcedTargetRotation(baseRotation)
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                Size(832,624),//test
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER

            )).build()

        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    recognizeHand(image)
                }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer,
            )

            preview?.surfaceProvider = previewViewProvider()?.surfaceProvider
            // Reset so syncUseCaseRotation always writes the forced rotation after rebind,
            // even when display rotation hasn't changed between two bindings.
            lastAppliedTargetRotation = null
            syncUseCaseRotation(reason = "bind")
            requestZoomApply("bind")
            logFrontCameraCapabilitiesIfNeeded("bind")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun requestZoomApply(reason: String) {
        applyCurrentZoomRatio(reason)
        previewViewProvider()?.post {
            applyCurrentZoomRatio("$reason-post")
        }
    }

    private fun applyCurrentZoomRatio(reason: String, zoomStateOverride: ZoomState? = null) {
        if (cameraFacing != CameraSelector.LENS_FACING_FRONT) return

        val boundCamera = camera ?: return
        val zoomState = zoomStateOverride ?: boundCamera.cameraInfo.zoomState.value
        if (zoomState == null) {
            Log.d(TAG, "applyCurrentZoomRatio skipped: zoomState not ready, reason=$reason")
            return
        }

        Log.d(
            TAG,
            String.format(
                Locale.US,
                "apply zoom reason=%s minZoomRatio=%.2f maxZoomRatio=%.2f zoomRatio=%.2f",
                reason,
                zoomState.minZoomRatio,
                zoomState.maxZoomRatio,
                zoomState.zoomRatio,
            ),
        )
    }

    private fun recognizeHand(imageProxy: ImageProxy) {
        if (!this::gestureRecognizerHelper.isInitialized) {
            imageProxy.close()
            return
        }

        // Some tablets do not dispatch config callbacks reliably; refresh target rotation on stream.
        syncUseCaseRotation(reason = "analyze")
        gestureRecognizerHelper.recognizeLiveStream(imageProxy = imageProxy)
    }

    private fun resolveDisplayRotation(): Int {
        val providerRotation = try {
            displayRotationProvider()
        } catch (error: Throwable) {
            Log.w(TAG, "resolveDisplayRotation provider failed", error)
            INVALID_ROTATION
        }

        if (isValidSurfaceRotation(providerRotation)) {
            return providerRotation
        }

        val previewDisplayRotation = previewViewProvider()?.display?.rotation ?: INVALID_ROTATION
        if (isValidSurfaceRotation(previewDisplayRotation)) {
            return previewDisplayRotation
        }

        val fallback = lastAppliedTargetRotation ?: Surface.ROTATION_0
        Log.w(
            TAG,
            "resolveDisplayRotation fallback providerRotation=$providerRotation previewDisplayRotation=$previewDisplayRotation using=$fallback",
        )
        return fallback
    }

    private fun isValidSurfaceRotation(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_0 ||
                rotation == Surface.ROTATION_90 ||
                rotation == Surface.ROTATION_180 ||
                rotation == Surface.ROTATION_270
    }

    private fun applyForcedTargetRotation(baseRotation: Int): Int {
        if (!forceRotation90Enabled) return baseRotation
        val normalized = when (baseRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> return baseRotation
        }
        return when ((normalized + FORCE_ROTATION_OFFSET_STEPS).mod(4)) {
            0 -> Surface.ROTATION_0
            1 -> Surface.ROTATION_90
            2 -> Surface.ROTATION_180
            else -> Surface.ROTATION_270
        }
    }

    private fun syncUseCaseRotation(reason: String) {
        val displayRotation = resolveDisplayRotation()
        val targetRotation = applyForcedTargetRotation(displayRotation)
        val previous = lastAppliedTargetRotation
        if (previous == targetRotation) return

        imageAnalyzer?.targetRotation = targetRotation
        preview?.targetRotation = targetRotation
        lastAppliedTargetRotation = targetRotation
        Log.i(
            TAG,
            "rotation-sync reason=$reason baseDisplayRotation=$displayRotation forced90=$forceRotation90Enabled targetRotation=$targetRotation previousTargetRotation=$previous",
        )
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {
        val gestureResult = resultBundle.results.firstOrNull()
        updateControlState(resultBundle, gestureResult)

        mainExecutor.execute {
            listener.onResults(resultBundle, gestureResult)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        resetControlState(emitExitEvent = true)
        mainExecutor.execute {
            listener.onError(error, errorCode)
        }
    }

    private fun updateControlState(
        resultBundle: GestureRecognizerHelper.ResultBundle,
        result: GestureRecognizerResult?,
    ) {
        val handLandmarkSamples = collectHandLandmarkSamples(resultBundle, result)
        val closedFistDetected = detectClosedFist(result)

        val primaryControlHand = selectPrimaryControlHand(handLandmarkSamples)
        logControlDiagnosticsIfNeeded(handLandmarkSamples, closedFistDetected)

        val now = SystemClock.uptimeMillis()

        if (controlState != ControlState.ENTRY_CONTROL && closedFistDetected) {
            if (preControlStartMs == 0L) {
                preControlStartMs = now
            }

            if (now - preControlStartMs >= ENTRY_CONTROL_HOLD_MS) {
                preControlStartMs = 0L
                controlState = ControlState.ENTRY_CONTROL
                enterControlMode(primaryControlHand)
                Log.i(TAG, "updateControlState: ControlEvent.ENTRY_CONTROL")
            }
        } else if (!closedFistDetected) {
            preControlStartMs = 0L
        }

        if (controlState == ControlState.ENTRY_CONTROL) {
            if (updateEntryControlAutoExit(primaryControlHand, now)) {
                return
            }
            if (primaryControlHand != null) {
                updateCropWindowFromControlHand(primaryControlHand)
            }
        }
    }

    private fun updateEntryControlAutoExit(
        primaryControlHand: HandLandmarkSample?,
        now: Long,
    ): Boolean {
        val lastSample = lastControlHandSample
        val noHandDetected = primaryControlHand == null

        val handStill = if (primaryControlHand != null && lastSample != null) {
            val movedDistance = distance2d(
                primaryControlHand.centerX,
                primaryControlHand.centerY,
                lastSample.centerX,
                lastSample.centerY,
            )
            val sizeDelta = abs(primaryControlHand.handSizeEstimate - lastSample.handSizeEstimate)
            movedDistance <= CONTROL_STILL_MOVE_EPS_NORM && sizeDelta <= CONTROL_STILL_SIZE_EPS_NORM
        } else {
            false
        }

        if (primaryControlHand != null) {
            lastControlHandSample = primaryControlHand
        }

        val shouldAccumulateInactivity = noHandDetected || handStill
        if (!shouldAccumulateInactivity) {
            controlInactivityStartMs = 0L
            return false
        }

        if (controlInactivityStartMs == 0L) {
            controlInactivityStartMs = now
            return false
        }

        if (now - controlInactivityStartMs < CONTROL_STABLE_EXIT_MS) {
            return false
        }

        val reason = if (noHandDetected) "no_hand_2s" else "still_hand_2s"
        Log.i(TAG, "updateControlState: ControlEvent.EXIT_CONTROL reason=$reason")
        resetControlState(emitExitEvent = true)
        return true
    }

    private fun resetControlState(emitExitEvent: Boolean) {
        val wasInControlMode = controlState == ControlState.ENTRY_CONTROL
        val shouldExit = emitExitEvent && wasInControlMode

        controlState = ControlState.IDLE
        preControlStartMs = 0L
        controlInactivityStartMs = 0L
        lastControlHandSample = null
        if (wasInControlMode) {
            mainExecutor.execute {
                listener.onControlModeChanged(false)
            }
        }
        if (shouldExit) {
            Log.i(TAG, "updateControlState: ControlEvent.EXIT_CONTROL")
        }
    }

    private fun detectClosedFist(result: GestureRecognizerResult?): Boolean {
        result?:return false
        return result.gestures().any { handGestures ->
            handGestures.any { category ->
                category.categoryName().equals(CLOSED_FIST_GESTURE_NAME, ignoreCase = true) &&
                        category.score() >= CLOSED_FIST_SCORE_THRESHOLD
            }
        }
    }

    private fun collectHandLandmarkSamples(
        resultBundle: GestureRecognizerHelper.ResultBundle,
        result: GestureRecognizerResult?,
    ): List<HandLandmarkSample> ?{
        result?:return null
        val landmarks = result.landmarks()
        val handedness = result.handedness()
        val samples = mutableListOf<HandLandmarkSample>()

        val fullWidth = resultBundle.fullImageWidth.toFloat().coerceAtLeast(1f)
        val fullHeight = resultBundle.fullImageHeight.toFloat().coerceAtLeast(1f)
        val inputWidth = resultBundle.inputImageWidth.toFloat().coerceAtLeast(1f)
        val inputHeight = resultBundle.inputImageHeight.toFloat().coerceAtLeast(1f)
        val cropOffsetX = resultBundle.cropOffsetX.toFloat()
        val cropOffsetY = resultBundle.cropOffsetY.toFloat()

        for (index in landmarks.indices) {
            val handLandmarks = landmarks[index]
            if (handLandmarks.size <= MIDDLE_FINGER_TIP_INDEX) continue

            val thumbTip = handLandmarks[THUMB_TIP_INDEX]
            val middleTip = handLandmarks[MIDDLE_FINGER_TIP_INDEX]
            val minX = handLandmarks.minOf { it.x() }
            val maxX = handLandmarks.maxOf { it.x() }
            val minY = handLandmarks.minOf { it.y() }
            val maxY = handLandmarks.maxOf { it.y() }
            val averageDepthZ = handLandmarks.map { it.z() }.average().toFloat()
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            val centerXFullNorm = ((cropOffsetX + centerX * inputWidth) / fullWidth).coerceIn(0f, 1f)
            val centerYFullNorm = ((cropOffsetY + centerY * inputHeight) / fullHeight).coerceIn(0f, 1f)
            val middleXFullNorm = ((cropOffsetX + middleTip.x() * inputWidth) / fullWidth).coerceIn(0f, 1f)
            val middleYFullNorm = ((cropOffsetY + middleTip.y() * inputHeight) / fullHeight).coerceIn(0f, 1f)
            val thumbXFullNorm = ((cropOffsetX + thumbTip.x() * inputWidth) / fullWidth).coerceIn(0f, 1f)
            val thumbYFullNorm = ((cropOffsetY + thumbTip.y() * inputHeight) / fullHeight).coerceIn(0f, 1f)
            val handWidthFullNorm = ((maxX - minX) * inputWidth / fullWidth).coerceAtLeast(0f)
            val handHeightFullNorm = ((maxY - minY) * inputHeight / fullHeight).coerceAtLeast(0f)
            val handLabel = handedness
                .getOrNull(index)
                ?.firstOrNull()
                ?.categoryName()
                ?.lowercase(Locale.US)
            val isClosedFist = result.gestures()
                .getOrNull(index)
                ?.any { category ->
                    category.categoryName().equals(CLOSED_FIST_GESTURE_NAME, ignoreCase = true) &&
                            category.score() >= CLOSED_FIST_SCORE_THRESHOLD
                }
                ?: false

            samples.add(
                HandLandmarkSample(
                    handLabel = handLabel,
                    centerX = centerXFullNorm,
                    centerY = centerYFullNorm,
                    thumbX = thumbXFullNorm,
                    thumbY = thumbYFullNorm,
                    middleX = middleXFullNorm,
                    middleY = middleYFullNorm,
                    thumbMiddleDistance = distance2d(
                        thumbXFullNorm,
                        thumbYFullNorm,
                        middleXFullNorm,
                        middleYFullNorm,
                    ),
                    averageDepthZ = averageDepthZ,
                    handSizeEstimate = max(handWidthFullNorm, handHeightFullNorm),
                    isClosedFist = isClosedFist,
                ),
            )
        }

        return samples
    }

    private fun logControlDiagnosticsIfNeeded(
        handLandmarkSamples: List<HandLandmarkSample>?,
        closedFistDetected: Boolean,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastControlDiagnosticLogMs < CONTROL_DIAGNOSTIC_LOG_INTERVAL_MS) return
        lastControlDiagnosticLogMs = now

        val detail = handLandmarkSamples?.joinToString(prefix = "[", postfix = "]") {
            val proximity = classifyHandProximity(it)
            String.format(
                Locale.US,
                "%s thumb-middle=%.4f avg-z=%.4f hand-size=%.4f middle-tip=(%.4f,%.4f) proximity=%s",
                it.handLabel ?: "unknown",
                it.thumbMiddleDistance,
                it.averageDepthZ,
                it.handSizeEstimate,
                it.middleX,
                it.middleY,
                proximity,
            )
        }
        Log.d(
            TAG,
            "control-diagnostics state=$controlState hands=${handLandmarkSamples?.size} fist=$closedFistDetected holdMs=${now - preControlStartMs} labels=$detail",
        )
    }

    private fun classifyHandProximity(sample: HandLandmarkSample): HandProximity {
        var score = 0

        score += when {
            sample.handSizeEstimate >= HAND_NEAR_SIZE_THRESHOLD -> 2
            sample.handSizeEstimate >= HAND_MID_SIZE_THRESHOLD -> 1
            else -> 0
        }

        score += when {
            sample.averageDepthZ <= HAND_NEAR_DEPTH_THRESHOLD -> 2
            sample.averageDepthZ <= HAND_MID_DEPTH_THRESHOLD -> 1
            else -> 0
        }

        return when {
            score >= 3 -> HandProximity.NEAR
            score >= 1 -> HandProximity.MID
            else -> HandProximity.FAR
        }
    }

    private fun distance2d(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun selectPrimaryControlHand(samples: List<HandLandmarkSample>?): HandLandmarkSample? {
        samples?:return null
        return samples
            .filter { it.isClosedFist }
            .maxByOrNull { it.handSizeEstimate }
            ?: samples.maxByOrNull { it.handSizeEstimate }
    }

    private fun enterControlMode(sample: HandLandmarkSample?) {
        controlInactivityStartMs = 0L
        lastControlHandSample = sample
        mainExecutor.execute {
            listener.onControlModeChanged(true)
        }
    }

    private fun updateCropWindowFromControlHand(sample: HandLandmarkSample) {

    }


    private fun logFrontCameraCapabilitiesIfNeeded(reason: String) {
        if (cameraFacing != CameraSelector.LENS_FACING_FRONT) return

        val cameraManager = appContext.getSystemService(CameraManager::class.java)
        if (cameraManager == null) {
            Log.w(TAG, "logFrontCameraCapabilitiesIfNeeded skipped: cameraManager unavailable")
            return
        }

        val frontCameraId = findCameraIdByFacing(cameraManager, CameraCharacteristics.LENS_FACING_FRONT)
        val cameraId = frontCameraId ?: UNKNOWN_CAMERA_ID
        if (lastLoggedCameraCapabilityId == cameraId) return

        try {
            val targetCameraId = frontCameraId
            if (targetCameraId == null) {
                Log.w(TAG, "front-camera-capabilities skipped: cannot resolve front camera id")
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(targetCameraId)
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)
            val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
            val previewSizes = streamMap?.getOutputSizes(SurfaceTexture::class.java)

            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }
            val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val cameraXZoomState = camera?.cameraInfo?.zoomState?.value

            Log.i(
                TAG,
                "front-camera-capabilities reason=$reason cameraId=$targetCameraId lensFacing=$facing",
            )
            Log.i(
                TAG,
                "front-camera-resolution preview=${formatSizes(previewSizes)} yuv=${formatSizes(yuvSizes)} jpeg=${formatSizes(jpegSizes)}",
            )
            Log.i(
                TAG,
                "front-camera-zoom supportedZoomRatio=${formatZoomRatioRange(zoomRatioRange)} maxDigitalZoom=${"%.2f".format(Locale.US, maxDigitalZoom ?: 0f)} cameraX=min=${"%.2f".format(Locale.US, cameraXZoomState?.minZoomRatio ?: 0f)},max=${"%.2f".format(Locale.US, cameraXZoomState?.maxZoomRatio ?: 0f)}",
            )
            Log.i(
                TAG,
                "front-camera-meta activeArray=${activeArray ?: "unknown"} focalLengths=${focalLengths?.joinToString(prefix = "[", postfix = "]") { "%.2f".format(Locale.US, it) } ?: "[]"}",
            )
            lastLoggedCameraCapabilityId = targetCameraId
        } catch (error: Exception) {
            Log.w(TAG, "front-camera-capabilities failed", error)
        }
    }

    private fun findCameraIdByFacing(cameraManager: CameraManager, lensFacing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            runCatching {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) == lensFacing
            }.getOrDefault(false)
        }
    }

    private fun formatSizes(sizes: Array<Size>?): String {
        if (sizes.isNullOrEmpty()) return "[]"
        return sizes
            .distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .joinToString(prefix = "[", postfix = "]") { "${it.width}x${it.height}" }
    }

    private fun formatZoomRatioRange(range: android.util.Range<Float>?): String {
        if (range == null) return "unknown"
        return "${"%.2f".format(Locale.US, range.lower)}-${"%.2f".format(Locale.US, range.upper)}"
    }

}