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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.fruitninj.gesture.GestureRecognizerHelper.Companion.TAG
import com.fruitninj.gesture.utils.LogUtil
import com.fruitninj.gesturemode.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    data class PointPercent(
        val xPercent: Float,
        val yPercent: Float,
    ){
        override fun toString(): String {
            return "PointPercent(xPercent=$xPercent, yPercent=$yPercent)"
        }
    }

    private var results: GestureRecognizerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var cropBoxPaint = Paint()
    private var mappingBoxPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    // Full (pre-crop) frame dimensions and crop offset for coordinate remapping.
    private var fullImageWidth: Int = -1
    private var fullImageHeight: Int = -1
    private var cropOffsetX: Int = 0
    private var cropOffsetY: Int = 0
    private var hasCropRegion: Boolean = false
    // Main data structures: normalized coordinates.
    // Keep stable mutable objects and update fields in place so external references stay in sync.
    private lateinit var cropWindow: CropWindow
    private lateinit var mappingWindow: MappingWindow
    // Cached RectF for rendering only
    private val cropRect = RectF()
    private val mappingRect = RectF()
    private var isCropEditMode = false
    private var isGestureControlMode = false
    // 是否正在编辑映射框（独立于裁剪框编辑模式）
    private var isMappingEditMode = false
    // 映射框是否已被用户独立调整（true 时不再根据裁剪框自动重算）
    private var isCustomMappingRect: Boolean = false
    private var downPointerId = MotionEvent.INVALID_POINTER_ID
    private var editPointerId = MotionEvent.INVALID_POINTER_ID
    private var lastEditXFull = 0f
    private var lastEditYFull = 0f
    private var mappedPointPercentListener: ((PointPercent?) -> Unit)? = null
    private var latestPointPercent: PointPercent? = null
    private var lastGestureXFull: Float? = null
    private var lastGestureYFull: Float? = null
    private var lastGestureHandSize: Float? = null


    private val gestureDetector = GestureDetector(
        this.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (isGestureControlMode) return
                val fullX = viewXToFullFrame(e.x)
                val fullY = viewYToFullFrame(e.y)

                // 优先检测映射框（在裁剪框内部，更精确）
                if (mappingRect.width() > 0f && mappingRect.height() > 0f
                    && mappingRect.contains(fullX, fullY)
                ) {
                    isMappingEditMode = true
                    editPointerId = downPointerId
                    lastEditXFull = fullX
                    lastEditYFull = fullY
                    invalidate()
                    return
                }

                // 再检测裁剪框
                if (cropRect.width() <= 0f || cropRect.height() <= 0f) return
                isCropEditMode = true
                editPointerId = downPointerId
                lastEditXFull = viewXToFullFrame(e.x)
                lastEditYFull = viewYToFullFrame(e.y)
                invalidate()
            }
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        this.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isCropEditMode && !isMappingEditMode) return false
                val focusX = viewXToFullFrame(detector.focusX)
                val focusY = viewYToFullFrame(detector.focusY)
                if (isCropEditMode) {
                    scaleCropRect(focusX, focusY, detector.scaleFactor)
                } else {
                    scaleMappingRect(focusX, focusY, detector.scaleFactor)
                }
                return true
            }

        },
    )

    init {
        initPaints()
    }

    fun clear() {
        Log.i(TAG, "clear: ----")
        ensureWindowsInitialized()
        results = null
        hasCropRegion = false
        cropRect.setEmpty()
        mappingRect.setEmpty()
        isCropEditMode = false
        isMappingEditMode = false
        isCustomMappingRect = false
        downPointerId = MotionEvent.INVALID_POINTER_ID
        editPointerId = MotionEvent.INVALID_POINTER_ID
        linePaint.reset()
        pointPaint.reset()
        cropBoxPaint.reset()
        mappingBoxPaint.reset()
        latestPointPercent = null
        lastGestureXFull = null
        lastGestureYFull = null
        lastGestureHandSize = null
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.RED
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        cropBoxPaint.color = Color.YELLOW
        cropBoxPaint.strokeWidth = CROP_BOX_STROKE_WIDTH
        cropBoxPaint.style = Paint.Style.STROKE

        mappingBoxPaint.color = Color.CYAN
        mappingBoxPaint.strokeWidth = MAPPING_BOX_STROKE_WIDTH
        mappingBoxPaint.style = Paint.Style.STROKE
    }

    fun setOnMappedPointPercentListener(listener: ((PointPercent?) -> Unit)?) {
        mappedPointPercentListener = listener
    }


    fun setControlMode(enabled: Boolean) {
        LogUtil.i(TAG, "setControlMode: $enabled")
        if (isGestureControlMode == enabled) return
        isGestureControlMode = enabled
        LogUtil.i(TAG, "setControlMode: isGestureControlMode $isGestureControlMode")

        if (enabled) {
            isCropEditMode = false
            isMappingEditMode = false
            downPointerId = MotionEvent.INVALID_POINTER_ID
            editPointerId = MotionEvent.INVALID_POINTER_ID
            lastGestureXFull = null
            lastGestureYFull = null
            lastGestureHandSize = null
        } else {
            lastGestureXFull = null
            lastGestureYFull = null
            lastGestureHandSize = null
        }
        invalidate()
    }

    private val isFullFrameAvailable: Boolean
        get() = fullImageWidth > 0 && fullImageHeight > 0


    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!isFullFrameAvailable) return

        if (cropRect.width() > 0f && cropRect.height() > 0f) {
            cropBoxPaint.color =
                if (isCropEditMode || isGestureControlMode) Color.GREEN else Color.YELLOW
            canvas.drawRect(
                cropRect.left * scaleFactor,
                cropRect.top * scaleFactor,
                cropRect.right * scaleFactor,
                cropRect.bottom * scaleFactor,
                cropBoxPaint,
            )
        }

        if (mappingRect.width() > 0f && mappingRect.height() > 0f) {
            mappingBoxPaint.color = if (isMappingEditMode) Color.GREEN else Color.CYAN
            canvas.drawRect(
                mappingRect.left * scaleFactor,
                mappingRect.top * scaleFactor,
                mappingRect.right * scaleFactor,
                mappingRect.bottom * scaleFactor,
                mappingBoxPaint,
            )
        }

        results?.let { gestureRecognizerResult ->
            val firstHandLandmarks = gestureRecognizerResult.landmarks().firstOrNull()
            if (firstHandLandmarks != null) {
                val palmCenter = computePalmCenter(firstHandLandmarks)
                // Map landmark from crop-normalized space back to full-frame pixel space,
                // then apply the uniform scaleFactor (which is computed against the full frame).
                val canvasX = (cropOffsetX + palmCenter.first * imageWidth) * scaleFactor
                val canvasY = (cropOffsetY + palmCenter.second * imageHeight) * scaleFactor
                canvas.drawPoint(canvasX, canvasY, pointPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGestureControlMode) return false
        if (width <= 0 || height <= 0 || scaleFactor <= 0f) return super.onTouchEvent(event)

        val handledByGestureDetector = gestureDetector.onTouchEvent(event)
        if (isCropEditMode || isMappingEditMode) {
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPointerId = event.getPointerId(0)
                lastEditXFull = viewXToFullFrame(event.x)
                lastEditYFull = viewYToFullFrame(event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if ((isCropEditMode || isMappingEditMode) && !scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(editPointerId)
                    if (pointerIndex >= 0) {
                        val currentX = viewXToFullFrame(event.getX(pointerIndex))
                        val currentY = viewYToFullFrame(event.getY(pointerIndex))
                        if (isCropEditMode) {
                            moveCropRect(currentX - lastEditXFull, currentY - lastEditYFull)
                        } else {
                            moveMappingRect(currentX - lastEditXFull, currentY - lastEditYFull)
                        }
                        lastEditXFull = currentX
                        lastEditYFull = currentY
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedPointerId = event.getPointerId(event.actionIndex)
                if (liftedPointerId == editPointerId) {
                    exitCropEditMode()
                    exitMappingEditMode()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !isCropEditMode && !isMappingEditMode) {
                    performClick()
                }
                exitCropEditMode()
                exitMappingEditMode()
                downPointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        return handledByGestureDetector || isCropEditMode || isMappingEditMode || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun computePalmCenter(landmarks: List<NormalizedLandmark>): Pair<Float, Float> {
        val tip = landmarks[INDEX_FINGER_TIP_INDEX]
        return Pair(tip.x(), tip.y())
    }

    fun setResults(
        gestureRecognizerResult: GestureRecognizerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE,
        fullImageWidth: Int = imageWidth,
        fullImageHeight: Int = imageHeight,
        cropOffsetX: Int = 0,
        cropOffsetY: Int = 0,
    ) {
        ensureWindowsInitialized()
        results = gestureRecognizerResult

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        this.fullImageWidth = fullImageWidth
        this.fullImageHeight = fullImageHeight
        this.cropOffsetX = cropOffsetX
        this.cropOffsetY = cropOffsetY
        hasCropRegion =
            cropOffsetX != 0 ||
                cropOffsetY != 0 ||
                imageWidth != fullImageWidth ||
                imageHeight != fullImageHeight

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. Scale against the full frame so
                // the landmark overlay matches the visible preview.
                max(width * 1f / fullImageWidth, height * 1f / fullImageHeight)
            }
        }

        syncRectsFromWindowsInPlace()
        applyBaseConstraintsInPlace()
        if (isGestureControlMode) {
            applyGestureControlTransform(gestureRecognizerResult)
        }
        syncWindowsFromRectsInPlace()

        updateMappedPointPercent(gestureRecognizerResult)
        invalidate()
    }

    private fun moveCropRect(deltaX: Float, deltaY: Float) {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return

        cropRect.offset(deltaX, deltaY)
        applyBaseConstraintsInPlace()
        ensureCropContainsMappingInPlace(allowExpand = false)
        applyMappingInsideCropInPlace()
        syncWindowsFromRectsInPlace()
        updateMappedPointPercent(results)
        invalidate()
    }

    private fun scaleMappingRect(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (mappingRect.width() <= 0f || mappingRect.height() <= 0f) return
        scaleMappingRectInPlace(focusX, focusY, scaleFactor)
        isCustomMappingRect = true
        applyConstraintsForMappingEditInPlace()
        syncWindowsFromRectsInPlace()
        updateMappedPointPercent(results)
        invalidate()
    }

    private fun scaleCropRect(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return

        val clampedScale = scaleFactor.coerceIn(MIN_SCALE_FACTOR_PER_STEP, MAX_SCALE_FACTOR_PER_STEP)
        val newWidth = cropRect.width() * clampedScale
        val newHeight = cropRect.height() * clampedScale

        cropRect.set(
            focusX - newWidth / 2f,
            focusY - newHeight / 2f,
            focusX + newWidth / 2f,
            focusY + newHeight / 2f,
        )

        applyBaseConstraintsInPlace()
        ensureCropContainsMappingInPlace(allowExpand = false)
        applyMappingInsideCropInPlace()
        syncWindowsFromRectsInPlace()
        updateMappedPointPercent(results)
        invalidate()
    }

    private fun updateMappedPointPercent(result: GestureRecognizerResult?) {
        val firstHandLandmarks = result?.landmarks()?.firstOrNull()
        val handSize = result?.landmarks()?.size ?: 0
        if (firstHandLandmarks == null || handSize <= 0 || mappingRect.width() <= 0f || mappingRect.height() <= 0f) {
            emitMappedPointPercent(null)
            return
        }

        val point = computePalmCenter(firstHandLandmarks)
        val pointXFull = cropOffsetX + point.first * imageWidth
        val pointYFull = cropOffsetY + point.second * imageHeight

        val matchedXFull = pointXFull.coerceIn(mappingRect.left, mappingRect.right)
        val matchedYFull = pointYFull.coerceIn(mappingRect.top, mappingRect.bottom)

        val xPercent = ((matchedXFull - mappingRect.left) / mappingRect.width() * 100f)
            .coerceIn(0f, 100f)
        val yPercent = ((matchedYFull - mappingRect.top) / mappingRect.height() * 100f)
            .coerceIn(0f, 100f)
        emitMappedPointPercent(PointPercent(xPercent = xPercent, yPercent = yPercent))
    }

    private fun emitMappedPointPercent(next: PointPercent?) {
        latestPointPercent = next
        mappedPointPercentListener?.invoke(next)
    }

    private fun applyCropBoundsInPlace() {
        val visibleBounds = visibleFullFrameBounds()
        val frameW = visibleBounds.width()
        val frameH = visibleBounds.height()
        if (frameW <= 0f || frameH <= 0f) {
            cropRect.setEmpty()
            return
        }

        val minW = min(frameW * MIN_CROP_SIZE_NORM, frameW)
        val minH = min(frameH * MIN_CROP_SIZE_NORM, frameH)
        val desiredW = cropRect.width().coerceIn(minW, frameW)
        val desiredH = cropRect.height().coerceIn(minH, frameH)
        val minCenterX = visibleBounds.left + desiredW / 2f
        val maxCenterX = visibleBounds.right - desiredW / 2f
        val minCenterY = visibleBounds.top + desiredH / 2f
        val maxCenterY = visibleBounds.bottom - desiredH / 2f
        val centerX = coerceInSafely(cropRect.centerX(), minCenterX, maxCenterX)
        val centerY = coerceInSafely(cropRect.centerY(), minCenterY, maxCenterY)

        cropRect.set(
            centerX - desiredW / 2f,
            centerY - desiredH / 2f,
            centerX + desiredW / 2f,
            centerY + desiredH / 2f,
        )
    }

    /**
     * 处理浮点误差导致的空区间：当 max < min 时回退到区间中点，避免 coerceIn 抛异常。
     */
    private fun coerceInSafely(value: Float, minValue: Float, maxValue: Float): Float {
        if (maxValue >= minValue) return value.coerceIn(minValue, maxValue)
        return (minValue + maxValue) / 2f
    }

    private fun resolveDefaultMarginInFullFrame(): Float {
        if (scaleFactor <= 0f) return DEFAULT_BOX_MARGIN_PX
        return DEFAULT_BOX_MARGIN_PX / scaleFactor
    }

    private fun applyMappingInsideCropInPlace() {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) {
            mappingRect.setEmpty()
            return
        }
        val targetAspectRatio = resolveMappingAspectRatio()
        if (mappingRect.width() <= 0f || mappingRect.height() <= 0f) {
            val margin = resolveDefaultMarginInFullFrame().coerceAtMost(min(cropRect.width(), cropRect.height()) / 4f)
            val maxWidth = max(cropRect.width() - margin * 2f, MIN_RECT_SIZE_FULL)
            val maxHeight = max(cropRect.height() - margin * 2f, MIN_RECT_SIZE_FULL)
            val targetWidth = min(maxWidth, maxHeight * targetAspectRatio).coerceAtLeast(MIN_RECT_SIZE_FULL)
            val targetHeight = (targetWidth / targetAspectRatio).coerceAtLeast(MIN_RECT_SIZE_FULL)
            mappingRect.set(
                cropRect.centerX() - targetWidth / 2f,
                cropRect.centerY() - targetHeight / 2f,
                cropRect.centerX() + targetWidth / 2f,
                cropRect.centerY() + targetHeight / 2f,
            )
        }

        val margin = resolveDefaultMarginInFullFrame()
        val maxWidth = max(cropRect.width() - 2f * margin, MIN_RECT_SIZE_FULL)
        val maxHeight = max(cropRect.height() - 2f * margin, MIN_RECT_SIZE_FULL)
        val fitWidthByHeight = maxHeight * targetAspectRatio
        val desiredWidth = min(mappingRect.width().coerceAtLeast(MIN_RECT_SIZE_FULL), min(maxWidth, fitWidthByHeight))
            .coerceAtLeast(MIN_RECT_SIZE_FULL)
        val desiredHeight = (desiredWidth / targetAspectRatio).coerceAtMost(maxHeight)
            .coerceAtLeast(MIN_RECT_SIZE_FULL)
        val minCenterX = cropRect.left + margin + desiredWidth / 2f
        val maxCenterX = cropRect.right - margin - desiredWidth / 2f
        val minCenterY = cropRect.top + margin + desiredHeight / 2f
        val maxCenterY = cropRect.bottom - margin - desiredHeight / 2f
        val centerX = coerceInSafely(mappingRect.centerX(), minCenterX, maxCenterX)
        val centerY = coerceInSafely(mappingRect.centerY(), minCenterY, maxCenterY)

        mappingRect.set(
            centerX - desiredWidth / 2f,
            centerY - desiredHeight / 2f,
            centerX + desiredWidth / 2f,
            centerY + desiredHeight / 2f,
        )
    }

    private fun ensureCropContainsMappingInPlace(allowExpand: Boolean) {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return
        if (mappingRect.width() <= 0f || mappingRect.height() <= 0f) return

        val margin = resolveDefaultMarginInFullFrame()
        val requiredLeft = mappingRect.left - margin
        val requiredTop = mappingRect.top - margin
        val requiredRight = mappingRect.right + margin
        val requiredBottom = mappingRect.bottom + margin
        val visibleBounds = visibleFullFrameBounds()
        if (visibleBounds.width() <= 0f || visibleBounds.height() <= 0f) return

        if (allowExpand) {
            // Only expand the edge whose margin is about to be violated.
            var nextLeft = cropRect.left
            var nextTop = cropRect.top
            var nextRight = cropRect.right
            var nextBottom = cropRect.bottom

            if (nextLeft > requiredLeft) nextLeft = requiredLeft
            if (nextTop > requiredTop) nextTop = requiredTop
            if (nextRight < requiredRight) nextRight = requiredRight
            if (nextBottom < requiredBottom) nextBottom = requiredBottom

            // If required area exceeds visible bounds, use the full visible span on that axis.
            if (requiredRight - requiredLeft >= visibleBounds.width()) {
                nextLeft = visibleBounds.left
                nextRight = visibleBounds.right
            } else {
                nextLeft = nextLeft.coerceAtLeast(visibleBounds.left)
                nextRight = nextRight.coerceAtMost(visibleBounds.right)
            }
            if (requiredBottom - requiredTop >= visibleBounds.height()) {
                nextTop = visibleBounds.top
                nextBottom = visibleBounds.bottom
            } else {
                nextTop = nextTop.coerceAtLeast(visibleBounds.top)
                nextBottom = nextBottom.coerceAtMost(visibleBounds.bottom)
            }

            cropRect.set(nextLeft, nextTop, nextRight, nextBottom)
            return
        }

        val reqWidth = (requiredRight - requiredLeft).coerceAtLeast(MIN_RECT_SIZE_FULL)
        val reqHeight = (requiredBottom - requiredTop).coerceAtLeast(MIN_RECT_SIZE_FULL)
        if (cropRect.width() < reqWidth || cropRect.height() < reqHeight) {
            val nextWidth = max(cropRect.width(), reqWidth)
            val nextHeight = max(cropRect.height(), reqHeight)
            cropRect.set(
                cropRect.centerX() - nextWidth / 2f,
                cropRect.centerY() - nextHeight / 2f,
                cropRect.centerX() + nextWidth / 2f,
                cropRect.centerY() + nextHeight / 2f,
            )
            applyCropBoundsInPlace()
        }

        var dx = 0f
        var dy = 0f
        if (cropRect.left > requiredLeft) dx = requiredLeft - cropRect.left
        if (cropRect.right < requiredRight) dx = requiredRight - cropRect.right
        if (cropRect.top > requiredTop) dy = requiredTop - cropRect.top
        if (cropRect.bottom < requiredBottom) dy = requiredBottom - cropRect.bottom
        cropRect.offset(dx, dy)
        applyCropBoundsInPlace()
    }

    private fun applyBaseConstraintsInPlace() {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) {
            syncRectsFromWindowsInPlace()
        }
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) {
            applyCropBoundsInPlace()
        }
        applyCropBoundsInPlace()
        applyMappingInsideCropInPlace()
    }

    private fun applyGestureControlTransform(result: GestureRecognizerResult?) {
        val firstHand = result?.landmarks()?.firstOrNull() ?: run {
            lastGestureXFull = null
            lastGestureYFull = null
            lastGestureHandSize = null
            return
        }

        val point = computePalmCenter(firstHand)
        val pointXFull = cropOffsetX + point.first * imageWidth
        val pointYFull = cropOffsetY + point.second * imageHeight
        val handSize = estimateHandSize(firstHand)
        val prevX = lastGestureXFull
        val prevY = lastGestureYFull
        val prevSize = lastGestureHandSize
        if (prevX == null || prevY == null || prevSize == null || prevSize <= 0f) {
            lastGestureXFull = pointXFull
            lastGestureYFull = pointYFull
            lastGestureHandSize = handSize
            return
        }

        // Smooth hand size to reduce per-frame jitter that causes slow scale drift.
        val smoothedHandSize =
            prevSize + (handSize - prevSize) * CONTROL_HAND_SIZE_EMA_ALPHA

        val deltaX = pointXFull - prevX
        val deltaY = pointYFull - prevY
        mappingRect.offset(deltaX, deltaY)
        // Keep translation constraints identical to manual mapping-box drag.
        applyConstraintsForMappingEditInPlace()

        val rawScaleRatio = if (prevSize > 0f) {
            smoothedHandSize / prevSize
        } else {
            1f
        }
        val scaleRatio = if (abs(rawScaleRatio - 1f) <= CONTROL_SCALE_RATIO_DEAD_ZONE) {
            1f
        } else {
            rawScaleRatio.coerceIn(MIN_SCALE_FACTOR_PER_STEP, MAX_SCALE_FACTOR_PER_STEP)
        }

        if (scaleRatio != 1f) {
            isCustomMappingRect = true
            scaleMappingRectInPlace(pointXFull, pointYFull, scaleRatio)

            if (scaleRatio < 1f) {
                // Hand shrinking scales both mapping and crop with the same ratio.
                scaleRectAroundPointInPlace(cropRect, pointXFull, pointYFull, scaleRatio)
                applyCropBoundsInPlace()
                ensureCropContainsMappingInPlace(allowExpand = true)
                applyMappingInsideCropInPlace()
            } else {
                // Hand growing matches manual mapping-box scale behavior.
                applyConstraintsForMappingEditInPlace()
            }
        }

        lastGestureXFull = pointXFull
        lastGestureYFull = pointYFull
        lastGestureHandSize = smoothedHandSize
    }

    private fun estimateHandSize(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.isEmpty()) return 0f
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        landmarks.forEach { landmark ->
            minX = min(minX, landmark.x())
            maxX = max(maxX, landmark.x())
            minY = min(minY, landmark.y())
            maxY = max(maxY, landmark.y())
        }
        val widthNorm = (maxX - minX).coerceAtLeast(0f)
        val heightNorm = (maxY - minY).coerceAtLeast(0f)
        return max(widthNorm, heightNorm)
    }

    private fun scaleRectAroundPointInPlace(rect: RectF, focusX: Float, focusY: Float, scale: Float) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val nextWidth = (rect.width() * scale).coerceAtLeast(MIN_RECT_SIZE_FULL)
        val nextHeight = (rect.height() * scale).coerceAtLeast(MIN_RECT_SIZE_FULL)
        rect.set(
            focusX - nextWidth / 2f,
            focusY - nextHeight / 2f,
            focusX + nextWidth / 2f,
            focusY + nextHeight / 2f,
        )
    }

    private fun syncRectsFromWindowsInPlace() {
        if (!::cropWindow.isInitialized || !::mappingWindow.isInitialized) return
        if (!isFullFrameAvailable) return
        val fullW = fullImageWidth.coerceAtLeast(1)
        val fullH = fullImageHeight.coerceAtLeast(1)

        cropRect.set(
            (cropWindow.centerXNorm - cropWindow.widthNorm / 2f) * fullW,
            (cropWindow.centerYNorm - cropWindow.heightNorm / 2f) * fullH,
            (cropWindow.centerXNorm + cropWindow.widthNorm / 2f) * fullW,
            (cropWindow.centerYNorm + cropWindow.heightNorm / 2f) * fullH,
        )

        mappingRect.set(
            (mappingWindow.centerXNorm - mappingWindow.widthNorm / 2f) * fullW,
            (mappingWindow.centerYNorm - mappingWindow.heightNorm / 2f) * fullH,
            (mappingWindow.centerXNorm + mappingWindow.widthNorm / 2f) * fullW,
            (mappingWindow.centerYNorm + mappingWindow.heightNorm / 2f) * fullH,
        )
    }

    private fun syncWindowsFromRectsInPlace() {
        if (!::cropWindow.isInitialized || !::mappingWindow.isInitialized) return
        if (!isFullFrameAvailable) return

        val fullW = fullImageWidth.coerceAtLeast(1)
        val fullH = fullImageHeight.coerceAtLeast(1)

        cropWindow.centerXNorm = (cropRect.centerX() / fullW).coerceIn(0f, 1f)
        cropWindow.centerYNorm = (cropRect.centerY() / fullH).coerceIn(0f, 1f)
        cropWindow.widthNorm = (cropRect.width() / fullW).coerceIn(MIN_CROP_SIZE_NORM, 1f)
        cropWindow.heightNorm = (cropRect.height() / fullH).coerceIn(MIN_CROP_SIZE_NORM, 1f)

        mappingWindow.centerXNorm = (mappingRect.centerX() / fullW).coerceIn(0f, 1f)
        mappingWindow.centerYNorm = (mappingRect.centerY() / fullH).coerceIn(0f, 1f)
        mappingWindow.widthNorm = (mappingRect.width() / fullW).coerceIn(MIN_MAPPING_SIZE_NORM, 1f)
        mappingWindow.heightNorm = (mappingRect.height() / fullH).coerceIn(MIN_MAPPING_SIZE_NORM, 1f)
    }

    private fun exitCropEditMode() {
        if (!isCropEditMode) return
        isCropEditMode = false
        editPointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    // ---- 映射框独立编辑方法 ----

    private fun moveMappingRect(deltaX: Float, deltaY: Float) {
        if (mappingRect.width() <= 0f || mappingRect.height() <= 0f) return
        mappingRect.offset(deltaX, deltaY)
        isCustomMappingRect = true
        applyConstraintsForMappingEditInPlace()
        syncWindowsFromRectsInPlace()
        updateMappedPointPercent(results)
        invalidate()
    }

    private fun applyConstraintsForMappingEditInPlace() {
        // During mapping edit, prefer expanding crop to preserve minimum edge margin.
        applyCropBoundsInPlace()
        ensureCropContainsMappingInPlace(allowExpand = true)
        applyMappingInsideCropInPlace()
    }

    private fun scaleMappingRectInPlace(focusX: Float, focusY: Float, scaleFactor: Float) {
        val clampedScale = scaleFactor.coerceIn(MIN_SCALE_FACTOR_PER_STEP, MAX_SCALE_FACTOR_PER_STEP)
        val targetAspectRatio = resolveMappingAspectRatio()
        val newWidth = (mappingRect.width() * clampedScale).coerceAtLeast(MIN_RECT_SIZE_FULL)
        val newHeight = (newWidth / targetAspectRatio).coerceAtLeast(MIN_RECT_SIZE_FULL)
        mappingRect.set(
            focusX - newWidth / 2f,
            focusY - newHeight / 2f,
            focusX + newWidth / 2f,
            focusY + newHeight / 2f,
        )
    }

    private fun resolveMappingAspectRatio(): Float {
        if (width > 0 && height > 0) {
            return (width.toFloat() / height.toFloat()).coerceAtLeast(0.01f)
        }
        val visibleBounds = visibleFullFrameBounds()
        if (visibleBounds.height() > 0f) {
            return (visibleBounds.width() / visibleBounds.height()).coerceAtLeast(0.01f)
        }
        return 1f
    }


    // ...existing code...

    private fun exitMappingEditMode() {
        if (!isMappingEditMode) return
        isMappingEditMode = false
        editPointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    private fun viewXToFullFrame(viewX: Float): Float {
        val visibleBounds = visibleFullFrameBounds()
        if (scaleFactor <= 0f || visibleBounds.width() <= 0f) return 0f
        return (viewX / scaleFactor).coerceIn(visibleBounds.left, visibleBounds.right)
    }

    private fun viewYToFullFrame(viewY: Float): Float {
        val visibleBounds = visibleFullFrameBounds()
        if (scaleFactor <= 0f || visibleBounds.height() <= 0f) return 0f
        return (viewY / scaleFactor).coerceIn(visibleBounds.top, visibleBounds.bottom)
    }

    private fun visibleFullFrameBounds(): RectF {
        if (scaleFactor <= 0f || width <= 0 || height <= 0) return RectF()

        // Constraint is view-first: convert OverlayView visible size back to frame space.
        val visibleRight = width / scaleFactor
        val visibleBottom = height / scaleFactor
        return RectF(0f, 0f, visibleRight, visibleBottom)
    }


    fun attachOverlayRect(overlayCropWindow: CropWindow, overlayMappingWindow: MappingWindow) {
        this.cropWindow = overlayCropWindow
        this.mappingWindow = overlayMappingWindow
        syncRectsFromWindowsInPlace()
        applyBaseConstraintsInPlace()
        syncWindowsFromRectsInPlace()
    }

    private fun ensureWindowsInitialized() {
        if (!::cropWindow.isInitialized) {
            cropWindow = CropWindow()
        }
        if (!::mappingWindow.isInitialized) {
            mappingWindow = MappingWindow()
        }
    }

    companion object {
        // 绘制样式（像素）
        private const val LANDMARK_STROKE_WIDTH = 16F
        private const val CROP_BOX_STROKE_WIDTH = 6F
        private const val MAPPING_BOX_STROKE_WIDTH = 4F

        // 手部关键点索引（MediaPipe 21 点）
        private const val INDEX_FINGER_TIP_INDEX = 12

        // 裁剪框最小尺寸（相对 full frame 的归一化比例）
        private const val MIN_CROP_SIZE_NORM = 0.4f

        // 单帧缩放限幅，避免手势噪声导致跳变
        private const val MIN_SCALE_FACTOR_PER_STEP = 0.85f
        private const val MAX_SCALE_FACTOR_PER_STEP = 1.15f

        // GestureControlMode 缩放平滑/死区参数
        private const val CONTROL_HAND_SIZE_EMA_ALPHA = 0.35f
        private const val CONTROL_SCALE_RATIO_DEAD_ZONE = 0.02f

        // 映射框几何约束
        // 归一化最小尺寸（相对 full frame，0..1）。
        // 在 syncWindowsFromRectsInPlace 中写回 mappingWindow 时作为下限，避免持久化为过小值。
        // 与 MIN_RECT_SIZE_FULL（像素下限）构成双重保护：一个约束归一化空间，一个约束像素空间。
        private const val MIN_MAPPING_SIZE_NORM = 0.2f
        private const val MIN_RECT_SIZE_FULL = 100f
        // 默认映射框与裁剪框最小边距（view 像素）
        private const val DEFAULT_BOX_MARGIN_PX = 150f
    }
}
