package com.fruitninj.gesture

class CropOptimizer(private val baseCropWindow: CropWindow) {

    companion object {
        private const val TAG = "CropOptimizer"
        /** Consecutive no-hand frames before an expansion step fires. */
        const val NO_HAND_FRAME_THRESHOLD = 20


        /** Consecutive hand frames required before a shrink step fires. */
        const val HAND_CONFIRMED_FRAME_THRESHOLD = 40

        /** Multiplier applied to the current expansion factor per expansion step. */
        const val EXPANSION_STEP_FACTOR = 1.5f

        const val SHRINK_STEP_DELTA = 1f / 5f
    }

    private val state = CropOptState(baseCropWindow = baseCropWindow)

    val currentCropWindow = baseCropWindow.copy()

    fun updateCropOptimization(
        imageWidth: Int,
        imageHeight: Int,
        hasHand: Boolean,
        currentTimeMs: Long,
    ) {
        if (hasHand) {
            handleHandDetected(imageWidth, imageHeight)
        } else {
            handleNoHandDetected(imageWidth, imageHeight, currentTimeMs)
        }
    }

    // ── Internal state handlers ───────────────────────────────────────────────

    private fun handleHandDetected(imageWidth: Int, imageHeight: Int) {
        // Reset no-hand streak.
        state.noHandDetectionFrames = 0
        state.handConfirmedFrames++

        // Shrink once hand is confirmed for enough consecutive frames and still expanded.
        if (state.handConfirmedFrames >= HAND_CONFIRMED_FRAME_THRESHOLD
            && state.currentExpansionFactor > 1.0f
        ) {
            shrinkCropWindow(imageWidth, imageHeight)
        }
    }

    private fun handleNoHandDetected(
        imageWidth: Int,
        imageHeight: Int,
        currentTimeMs: Long,
    ) {
        // Reset hand-confirmed streak.
        state.handConfirmedFrames = 0

        state.noHandDetectionFrames++

        val shouldExpand =
            state.noHandDetectionFrames >= NO_HAND_FRAME_THRESHOLD

        if (shouldExpand && !state.useFullFrame) {
            expandCropWindow(imageWidth, imageHeight)
            // Reset streak so the next expansion requires another full window.
            state.noHandDetectionFrames = 0
        }
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun expandCropWindow(imageWidth: Int, imageHeight: Int) {
        val newFactor = state.currentExpansionFactor * EXPANSION_STEP_FACTOR
        val expanded = applyExpansionFactor(newFactor, imageWidth, imageHeight)

        if (expanded == CropWindow.FULL_FRAME) {
            state.useFullFrame = true
            state.currentExpansionFactor =
                (1F / baseCropWindow.widthNorm).coerceAtLeast(1F / baseCropWindow.heightNorm)
        } else {
            state.currentExpansionFactor = newFactor
        }
        currentCropWindow.set(expanded)
    }

    private fun shrinkCropWindow(imageWidth: Int, imageHeight: Int) {
        val newFactor = (state.currentExpansionFactor - SHRINK_STEP_DELTA).coerceAtLeast(1.0f)
        if (newFactor <= 1.0f) {
            // Fully restored to base.
            state.currentExpansionFactor = 1.0f
            state.useFullFrame = false
            currentCropWindow .set(baseCropWindow)
        } else {
            val shrunk = applyExpansionFactor(newFactor, imageWidth, imageHeight)
            state.currentExpansionFactor = newFactor
            currentCropWindow.set(shrunk)
        }
    }

    /**
     * Computes a [CropWindow] by scaling [baseCropWindow] by [factor] around its centre,
     * clamped to the image boundaries.
     *
     * Returns [CropWindow.FULL_FRAME] when either expanded dimension equals or exceeds
     * the corresponding image dimension.
     *
     * **Shared by both expand and shrink** to resolve the PRD pseudo-code API conflict
     * where `shrinkCropWindow` called a 3-arg overload of `expandCropWindow` that did not exist.
     *
     * @param factor      Expansion multiplier (1.0 = base size).
     * @param imageWidth  Full-frame width in pixels.
     * @param imageHeight Full-frame height in pixels.
     */
    private fun applyExpansionFactor(
        factor: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): CropWindow {
        val expandedWidth = baseCropWindow.widthNorm * imageWidth * factor
        val expandedHeight = baseCropWindow.heightNorm * imageHeight * factor

        // Fall back to full frame when expanded area would exceed image boundaries.
        if (expandedWidth >= imageWidth || expandedHeight >= imageHeight) {
            return CropWindow.FULL_FRAME
        }

        // Expand symmetrically around the base crop centre, then clamp to [0, imageSize].
        val baseCenterPxX = baseCropWindow.centerXNorm * imageWidth
        val baseCenterPxY = baseCropWindow.centerYNorm * imageHeight

        val expandedLeft =
            (baseCenterPxX - expandedWidth / 2f).coerceIn(0f, imageWidth - expandedWidth)
        val expandedTop =
            (baseCenterPxY - expandedHeight / 2f).coerceIn(0f, imageHeight - expandedHeight)

        return CropWindow(
            centerXNorm = ((expandedLeft + expandedWidth / 2f) / imageWidth).coerceIn(0f, 1f),
            centerYNorm = ((expandedTop + expandedHeight / 2f) / imageHeight).coerceIn(0f, 1f),
            widthNorm = (expandedWidth / imageWidth).coerceIn(0f, 1f),
            heightNorm = (expandedHeight / imageHeight).coerceIn(0f, 1f),
        )
    }
}

