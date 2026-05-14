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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap
import com.fruitninj.gesture.utils.LogUtil

class GestureRecognizerHelper(
    var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
    var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
    var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
    var numHands: Int = DEFAULT_NUM_HANDS,
    var currentDelegate: Int = DELEGATE_GPU,
    var runningMode: RunningMode = RunningMode.LIVE_STREAM,
    val context: Context,
    val cropWindow: CropWindow,
    val gestureRecognizerListener: GestureRecognizerListener? = null,
    var tuningPreset: TuningPreset = TuningPreset.BALANCED,
) {

    // For this example this needs to be a var so it can be reset on changes. If the GestureRecognizer
    // will not change, a lazy val would be preferable.
    private var gestureRecognizer: GestureRecognizer? = null

    /**
     * Dynamic crop optimizer. Non-null only while [isCropEnabled] is true.
     * Created in [setCropEnabled](true) and destroyed in [setCropEnabled](false) / [clearGestureRecognizer].
     * All accesses run on the single [backgroundExecutor] thread — no additional locking required.
     */
    private var cropOptimizer: CropOptimizer? = null

    private val reusableTransformMatrix = Matrix()
    private val reusableDrawMatrix = Matrix()
    private val reusableTransformedBounds = RectF()
    private val bitmapDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pendingFrameMetadata = ConcurrentHashMap<Long, FrameMetadata>()
    @Volatile
    private var isCropEnabled: Boolean = false

    init {
        applyTuningPreset(tuningPreset, recreateRecognizer = false)
        setupGestureRecognizer()
    }

    fun clearGestureRecognizer() {
        gestureRecognizer?.close()
        gestureRecognizer = null
        cropOptimizer = null
        bitmapCache = null
        pendingFrameMetadata.clear()
    }

    fun setCropEnabled(enabled: Boolean) {
        isCropEnabled = enabled
        cropOptimizer = if (enabled) CropOptimizer(cropWindow) else null
    }


    /**
     * Applies a scenario preset for quick threshold tuning.
     *
     * LOW_LIGHT: favors recall to reduce missed detections in noisy frames.
     * FAST_MOTION: keeps hand presence stricter while easing tracking continuity.
     */
    fun applyTuningPreset(
        preset: TuningPreset,
        recreateRecognizer: Boolean = true,
    ) {
        val values = preset.toThresholdValues()
        tuningPreset = preset
        minHandDetectionConfidence = values.minDetectionConfidence
        minHandTrackingConfidence = values.minTrackingConfidence
        minHandPresenceConfidence = values.minPresenceConfidence
        numHands = values.numHands

        if (recreateRecognizer) {
            setupGestureRecognizer()
        }
    }

    // Initialize the gesture recognizer using current settings on the
    // thread that is using it. CPU can be used with recognizers
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the recognizer
    fun setupGestureRecognizer() {
        gestureRecognizer?.close()
        gestureRecognizer = null
        pendingFrameMetadata.clear()

        // Set general recognition options, including number of used threads
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }

            DELEGATE_GPU -> {
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionBuilder.setModelAssetPath(MP_RECOGNIZER_TASK)

        try {
            val baseOptions = baseOptionBuilder.build()
            val optionsBuilder =
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(minHandDetectionConfidence)
                    .setMinTrackingConfidence(minHandTrackingConfidence)
                    .setMinHandPresenceConfidence(minHandPresenceConfidence)
                    .setNumHands(numHands)
                    .setRunningMode(runningMode)

            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }
            val options = optionsBuilder.build()
            gestureRecognizer =
                GestureRecognizer.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for " + "details"
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        } catch (e: RuntimeException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for " + "details",
                GPU_ERROR
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to GestureRecognizer.
    fun recognizeLiveStream(
        imageProxy: ImageProxy,
    ) {
        if (gestureRecognizer == null) {
            gestureRecognizerListener?.onError("Gesture Recognizer is not initialized.")
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val sourceBitmap = imageProxy.use { proxy ->
            try {
                convertYUVToBitmap(proxy)
            } catch (e: Exception) {
                // Fallback keeps the stream running if reflection is blocked on this device/version.
                Log.w(TAG, "Reflect YUV conversion failed, fallback to ImageProxy.toBitmap()", e)
                proxy.toBitmap()
            }
        }

        LogUtil.setInterval(TAG,500)
        LogUtil.i("recognizeLiveStream",TAG, "recognizeLiveStream  width:${sourceBitmap.width}  height:${sourceBitmap.height}  rotationDegrees:$rotationDegrees")

        // Compute transform in preview/overlay space so crop coordinates stay aligned.
        reusableTransformMatrix.reset()
        reusableTransformMatrix.postRotate(rotationDegrees.toFloat())
        reusableTransformMatrix.postScale(
            -1f,
            1f,
            sourceBitmap.width / 2f,
            sourceBitmap.height / 2f
        )

        reusableTransformedBounds.set(
            0f,
            0f,
            sourceBitmap.width.toFloat(),
            sourceBitmap.height.toFloat()
        )
        reusableTransformMatrix.mapRect(reusableTransformedBounds)

        val fullFrameWidth = reusableTransformedBounds.width().roundToInt()
        val fullFrameHeight = reusableTransformedBounds.height().roundToInt()

        // When crop is enabled, prefer the optimizer's dynamic window so the crop region
        // adapts to hand-detection state.  Fall back to the static cropWindow if the
        // optimizer has not been created yet.
        // NOTE: 1-frame delay is expected — the optimizer is updated in returnLivestreamResult
        // (result callback) after this frame has already been submitted for inference.
        val activeCropWindow = if (isCropEnabled) {
            cropOptimizer?.currentCropWindow ?: cropWindow
        } else {
            CropWindow.FULL_FRAME
        }

        val minCropWidth = max(1, (fullFrameWidth * 0.2f).roundToInt())
        val minCropHeight = max(1, (fullFrameHeight * 0.2f).roundToInt())
        val targetCropWidth =
            (fullFrameWidth * activeCropWindow.widthNorm)
                .roundToInt()
                .coerceIn(minCropWidth, fullFrameWidth)
        val targetCropHeight =
            (fullFrameHeight * activeCropWindow.heightNorm)
                .roundToInt()
                .coerceIn(minCropHeight, fullFrameHeight)

        val centerX = (fullFrameWidth * activeCropWindow.centerXNorm)
            .roundToInt()
            .coerceIn(0, fullFrameWidth)
        val centerY = (fullFrameHeight * activeCropWindow.centerYNorm)
            .roundToInt()
            .coerceIn(0, fullFrameHeight)

        var cropX = centerX - targetCropWidth / 2
        var cropY = centerY - targetCropHeight / 2
        cropX = min(max(cropX, 0), fullFrameWidth - targetCropWidth)
        cropY = min(max(cropY, 0), fullFrameHeight - targetCropHeight)

        val croppedBitmap = createBitmap(targetCropWidth, targetCropHeight)
        val cropCanvas = Canvas(croppedBitmap)

        reusableDrawMatrix.set(reusableTransformMatrix)
        reusableDrawMatrix.postTranslate(
            -reusableTransformedBounds.left,
            -reusableTransformedBounds.top,
        )
        reusableDrawMatrix.postTranslate(-cropX.toFloat(), -cropY.toFloat())
        cropCanvas.drawBitmap(sourceBitmap, reusableDrawMatrix, bitmapDrawPaint)

        pendingFrameMetadata[frameTime] = FrameMetadata(
            fullImageWidth = fullFrameWidth,
            fullImageHeight = fullFrameHeight,
            cropOffsetX = cropX,
            cropOffsetY = cropY,
        )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(croppedBitmap).build()

        recognizeAsync(mpImage, frameTime)
    }

    // Run hand gesture recognition using MediaPipe Gesture Recognition API
    @VisibleForTesting
    fun recognizeAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the recognition result will
        // be returned in returnLivestreamResult function
        gestureRecognizer?.recognizeAsync(mpImage, frameTime)
    }

    // Run a single frame in VIDEO mode and return the result synchronously.
    fun recognizeVideoFrame(mpImage: MPImage, timestampMs: Long): ResultBundle? {
        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                "Attempting to call recognizeVideoFrame while not using RunningMode.VIDEO"
            )
        }

        val startTime = SystemClock.uptimeMillis()
        val recognizerResult = gestureRecognizer?.recognizeForVideo(mpImage, timestampMs)
        if (recognizerResult == null) {
            gestureRecognizerListener?.onError("Gesture Recognizer failed to recognize frame in VIDEO mode.")
            return null
        }

        val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
        return ResultBundle(
            results = listOf(recognizerResult),
            inferenceTime = inferenceTimeMs,
            inputImageHeight = mpImage.height,
            inputImageWidth = mpImage.width,
            fullImageWidth = mpImage.width,
            fullImageHeight = mpImage.height,
            cropOffsetX = 0,
            cropOffsetY = 0,
        )
    }

    private var bitmapCache: Bitmap? = null
    private var nativeYuvToBitmapMethod: Method? = null

    fun convertYUVToBitmap(imageProxy: ImageProxy, scaleFactor: Float = 1f): Bitmap {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Input image format must be YUV_420_888")
        }
        require(scaleFactor > 0f) { "scaleFactor must be greater than 0" }

        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height
        val srcStrideY = imageProxy.planes[0].rowStride
        val srcStrideU = imageProxy.planes[1].rowStride
        val srcStrideV = imageProxy.planes[2].rowStride
        val srcPixelStrideY = imageProxy.planes[0].pixelStride
        val srcPixelStrideUV = imageProxy.planes[1].pixelStride

        val bitmap =
            if (bitmapCache == null || bitmapCache?.width != imageWidth || bitmapCache?.height != imageHeight) {
                createBitmap(imageWidth, imageHeight).also { bitmapCache = it }
            } else {
                bitmapCache!!
            }
        val bitmapStride = bitmap.rowBytes

        val result = invokeNativeConvertAndroid420ToBitmap(
            imageProxy.planes[0].buffer,
            srcStrideY,
            imageProxy.planes[1].buffer,
            srcStrideU,
            imageProxy.planes[2].buffer,
            srcStrideV,
            srcPixelStrideY,
            srcPixelStrideUV,
            bitmap,
            bitmapStride,
            imageWidth,
            imageHeight
        )
        if (result != 0) {
            throw UnsupportedOperationException("YUV to RGB conversion failed, result=$result")
        }

        if (scaleFactor == 1f) {
            return bitmap
        }

        val scaledWidth = max(1, (bitmap.width * scaleFactor).roundToInt())
        val scaledHeight = max(1, (bitmap.height * scaleFactor).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    private fun invokeNativeConvertAndroid420ToBitmap(
        srcByteBufferY: ByteBuffer,
        srcStrideY: Int,
        srcByteBufferU: ByteBuffer,
        srcStrideU: Int,
        srcByteBufferV: ByteBuffer,
        srcStrideV: Int,
        srcPixelStrideY: Int,
        srcPixelStrideUV: Int,
        bitmap: Bitmap,
        bitmapStride: Int,
        width: Int,
        height: Int,
    ): Int {
        val method = getNativeYuvToBitmapMethod() ?: return -1
        return try {
            method.invoke(
                null,
                srcByteBufferY,
                srcStrideY,
                srcByteBufferU,
                srcStrideU,
                srcByteBufferV,
                srcStrideV,
                srcPixelStrideY,
                srcPixelStrideUV,
                bitmap,
                bitmapStride,
                width,
                height,
            ) as Int
        } catch (e: Exception) {
            Log.e(TAG, "Reflect invoke nativeConvertAndroid420ToBitmap failed", e)
            -1
        }
    }

    private fun getNativeYuvToBitmapMethod(): Method? {
        nativeYuvToBitmapMethod?.let { return it }

        return try {
            val method = Class.forName("androidx.camera.core.ImageProcessingUtil")
                .getDeclaredMethod(
                    "nativeConvertAndroid420ToBitmap",
                    ByteBuffer::class.java,
                    Int::class.javaPrimitiveType,
                    ByteBuffer::class.java,
                    Int::class.javaPrimitiveType,
                    ByteBuffer::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Bitmap::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            nativeYuvToBitmapMethod = method
            method
        } catch (e: Exception) {
            Log.e(TAG, "Resolve nativeConvertAndroid420ToBitmap by reflection failed", e)
            null
        }
    }

    // Accepts the URI for a video file loaded from the user's gallery and attempts to run
    // gesture recognizer inference on the video. This process will evaluate
    // every frame in the video and attach the results to a bundle that will be
    // returned.
    fun recognizeVideoFile(
        videoUri: Uri,
        inferenceIntervalMs: Long
    ): ResultBundle? {
        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                "Attempting to call recognizeVideoFile" +
                        " while not using RunningMode.VIDEO"
            )
        }

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        var didErrorOccurred = false

        // Load frames from the video and run the gesture recognizer.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoLengthMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null recognition result
        if ((videoLengthMs == null) || (width == null) || (height == null)) return null

        // Next, we'll get one frame every frameInterval ms, then run recognizer
        // on these frames.
        val resultList = mutableListOf<GestureRecognizerResult>()
        val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

        for (i in 0..numberOfFrameToRead) {
            val timestampMs = i * inferenceIntervalMs // ms

            retriever
                .getFrameAtTime(
                    timestampMs * 1000, // convert from ms to micro-s
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                ?.let { frame ->
                    // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                    val argb8888Frame =
                        if (frame.config == Bitmap.Config.ARGB_8888) frame
                        else frame.copy(Bitmap.Config.ARGB_8888, false)

                    // Convert the input Bitmap object to an MPImage object to run inference
                    val mpImage = BitmapImageBuilder(argb8888Frame).build()

                    // Run gesture recognizer using MediaPipe Gesture Recognizer
                    // API
                    gestureRecognizer?.recognizeForVideo(mpImage, timestampMs)
                        ?.let { recognizerResult ->
                            resultList.add(recognizerResult)
                        } ?: {
                        didErrorOccurred = true
                        gestureRecognizerListener?.onError(
                            "ResultBundle could not be returned" +
                                    " in recognizeVideoFile"
                        )
                    }
                }
                ?: run {
                    didErrorOccurred = true
                    gestureRecognizerListener?.onError(
                        "Frame at specified time could not be" +
                                " retrieved when recognition in video."
                    )
                }
        }

        retriever.release()

        val inferenceTimePerFrameMs =
            (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

        return if (didErrorOccurred) {
            null
        } else {
            ResultBundle(resultList, inferenceTimePerFrameMs, height, width)
        }
    }

    // Accepted a Bitmap and runs gesture recognizer inference on it to
    // return results back to the caller
    fun recognizeImage(image: Bitmap): ResultBundle? {
        if (runningMode != RunningMode.IMAGE) {
            throw IllegalArgumentException(
                "Attempting to call detectImage" +
                        " while not using RunningMode.IMAGE"
            )
        }


        // Inference time is the difference between the system time at the
        // start and finish of the process
        val startTime = SystemClock.uptimeMillis()

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(image).build()

        // Run gesture recognizer using MediaPipe Gesture Recognizer API
        gestureRecognizer?.recognize(mpImage)?.also { recognizerResult ->
            val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
            return ResultBundle(
                listOf(recognizerResult),
                inferenceTimeMs,
                image.height,
                image.width
            )
        }

        // If gestureRecognizer?.recognize() returns null, this is likely an error. Returning null
        // to indicate this.
        gestureRecognizerListener?.onError(
            "Gesture Recognizer failed to recognize."
        )
        return null
    }

    // Return running status of the recognizer helper
    fun isClosed(): Boolean {
        return gestureRecognizer == null
    }

    // Return the recognition result to the GestureRecognizerHelper's caller
    private fun returnLivestreamResult(
        result: GestureRecognizerResult, input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        val frameMetadata = pendingFrameMetadata.remove(result.timestampMs())

        // Drive the optimizer with the current frame's hand-detection state.
        // fullImageWidth/Height come from the pre-crop metadata stored in recognizeLiveStream;
        // the fallback to input.width/height is a safety guard (input is the cropped bitmap,
        // so this path should not be hit in normal operation).
        val fullW = frameMetadata?.fullImageWidth ?: input.width
        val fullH = frameMetadata?.fullImageHeight ?: input.height
        val hasHand = hasDetectedHandInsideCrop(
            result = result,
            inputImageWidth = input.width,
            inputImageHeight = input.height,
            fullImageWidth = fullW,
            fullImageHeight = fullH,
            cropOffsetX = frameMetadata?.cropOffsetX ?: 0,
            cropOffsetY = frameMetadata?.cropOffsetY ?: 0,
        )
        cropOptimizer?.updateCropOptimization(fullW, fullH, hasHand, result.timestampMs())

        gestureRecognizerListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width,
                fullImageWidth = fullW,
                fullImageHeight = fullH,
                cropOffsetX = frameMetadata?.cropOffsetX ?: 0,
                cropOffsetY = frameMetadata?.cropOffsetY ?: 0,
            )
        )

    }

    // Return errors thrown during recognition to this GestureRecognizerHelper's caller.
    private fun returnLivestreamError(error: RuntimeException) {
        pendingFrameMetadata.clear()
        gestureRecognizerListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    /**
     * A hand only counts as "present" for crop optimisation when MediaPipe detected a hand
     * and that hand's representative point maps back inside the configured base crop window.
     *
     * Detection landmarks are normalized in the cropped-input coordinate space, so they must be
     * projected back to full-frame normalized coordinates before comparing with [cropWindow].
     */
    private fun hasDetectedHandInsideCrop(
        result: GestureRecognizerResult,
        inputImageWidth: Int,
        inputImageHeight: Int,
        fullImageWidth: Int,
        fullImageHeight: Int,
        cropOffsetX: Int,
        cropOffsetY: Int,
    ): Boolean {
        if (result.landmarks().isEmpty()) return false

        if (!isCropEnabled) return true

        val safeInputWidth = inputImageWidth.coerceAtLeast(1)
        val safeInputHeight = inputImageHeight.coerceAtLeast(1)
        val safeFullWidth = fullImageWidth.coerceAtLeast(1)
        val safeFullHeight = fullImageHeight.coerceAtLeast(1)

        val cropLeft = cropWindow.centerXNorm - cropWindow.widthNorm / 2f
        val cropTop = cropWindow.centerYNorm - cropWindow.heightNorm / 2f
        val cropRight = cropWindow.centerXNorm + cropWindow.widthNorm / 2f
        val cropBottom = cropWindow.centerYNorm + cropWindow.heightNorm / 2f

        return result.landmarks().any { handLandmarks ->
            isHandRepresentativePointInsideCrop(
                handLandmarks = handLandmarks,
                inputImageWidth = safeInputWidth,
                inputImageHeight = safeInputHeight,
                fullImageWidth = safeFullWidth,
                fullImageHeight = safeFullHeight,
                cropOffsetX = cropOffsetX,
                cropOffsetY = cropOffsetY,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
            )
        }
    }

    private fun isHandRepresentativePointInsideCrop(
        handLandmarks: List<NormalizedLandmark>,
        inputImageWidth: Int,
        inputImageHeight: Int,
        fullImageWidth: Int,
        fullImageHeight: Int,
        cropOffsetX: Int,
        cropOffsetY: Int,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
    ): Boolean {
        val representativePoint = computeHandRepresentativePoint(handLandmarks)
        val fullXNorm = ((cropOffsetX + representativePoint.first * inputImageWidth) / fullImageWidth.toFloat())
            .coerceIn(0f, 1f)
        val fullYNorm = ((cropOffsetY + representativePoint.second * inputImageHeight) / fullImageHeight.toFloat())
            .coerceIn(0f, 1f)

        return fullXNorm in cropLeft..cropRight && fullYNorm in cropTop..cropBottom
    }

    private fun computeHandRepresentativePoint(handLandmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        val representativeLandmark = handLandmarks.getOrNull(INDEX_FINGER_TIP_INDEX)
            ?: handLandmarks.firstOrNull()
            ?: return 0f to 0f
        return representativeLandmark.x() to representativeLandmark.y()
    }

    companion object {
        val TAG = "GestureRecognizerHelper ${this.hashCode()}"
        private const val MP_RECOGNIZER_TASK = "gesture_recognizer.task"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1

        // Lower defaults improve re-entry responsiveness after hands briefly leave the frame.
        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.45F
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.45F
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.40F
        const val DEFAULT_NUM_HANDS = 1
        private const val INDEX_FINGER_TIP_INDEX = 12
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1

    }

    enum class TuningPreset {
        BALANCED,
        LOW_LIGHT,
        FAST_MOTION,
        LOW_LIGHT_FAST_MOTION,
    }

    private data class ThresholdValues(
        val minDetectionConfidence: Float,
        val minTrackingConfidence: Float,
        val minPresenceConfidence: Float,
        val numHands: Int,
    )

    private fun TuningPreset.toThresholdValues(): ThresholdValues {
        return when (this) {
            TuningPreset.BALANCED -> ThresholdValues(
                minDetectionConfidence = DEFAULT_HAND_DETECTION_CONFIDENCE,
                minTrackingConfidence = DEFAULT_HAND_TRACKING_CONFIDENCE,
                minPresenceConfidence = DEFAULT_HAND_PRESENCE_CONFIDENCE,
                numHands = DEFAULT_NUM_HANDS,
            )
            TuningPreset.LOW_LIGHT -> ThresholdValues(
                minDetectionConfidence = 0.25F,
                minTrackingConfidence = 0.30F,
                minPresenceConfidence = 0.25F,
                numHands = DEFAULT_NUM_HANDS,
            )
            TuningPreset.FAST_MOTION -> ThresholdValues(
                minDetectionConfidence = 0.30F,
                minTrackingConfidence = 0.25F,
                minPresenceConfidence = 0.40F,
                numHands = DEFAULT_NUM_HANDS,
            )
            TuningPreset.LOW_LIGHT_FAST_MOTION -> ThresholdValues(
                minDetectionConfidence = 0.22F,
                minTrackingConfidence = 0.22F,
                minPresenceConfidence = 0.35F,
                numHands = DEFAULT_NUM_HANDS,
            )
        }
    }

    data class ResultBundle(
        val results: List<GestureRecognizerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        /** Width of the full (pre-crop) frame. Equals inputImageWidth when no crop is applied. */
        val fullImageWidth: Int = inputImageWidth,
        /** Height of the full (pre-crop) frame. Equals inputImageHeight when no crop is applied. */
        val fullImageHeight: Int = inputImageHeight,
        /** X offset (in full-frame pixels) where the crop region starts. */
        val cropOffsetX: Int = 0,
        /** Y offset (in full-frame pixels) where the crop region starts. */
        val cropOffsetY: Int = 0,
    )

    private data class FrameMetadata(
        val fullImageWidth: Int,
        val fullImageHeight: Int,
        val cropOffsetX: Int,
        val cropOffsetY: Int,
    )

    interface GestureRecognizerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}
