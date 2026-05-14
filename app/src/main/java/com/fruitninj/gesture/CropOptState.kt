package com.fruitninj.gesture

/**
 * Holds mutable state for the dynamic crop optimization algorithm.
 *
 * Frame dimensions are intentionally excluded from this class: they are passed per-frame
 * to [CropOptimizer.updateCropOptimization] so that the optimizer is decoupled from
 * any specific camera resolution.
 *
 * @param baseCropWindow Immutable reference crop region configured by the user.
 *   The optimizer never shrinks the active window below this region.
 */
data class CropOptState(

    // ── Base configuration (immutable) ────────────────────────────────────────

    /** The original user-defined crop window used as the floor for dynamic expansion. */
    val baseCropWindow: CropWindow,

    // ── Current dynamic state ─────────────────────────────────────────────────

    /**
     * Current expansion multiplier relative to [baseCropWindow].
     * - 1.0 = base size (no expansion)
     * - 1.5, 2.25, 3.375 … = progressive expansion steps
     * - [Float.MAX_VALUE] = full-frame sentinel (see [useFullFrame])
     */
    var currentExpansionFactor: Float = 1.0f,

    /**
     * True when the dynamic crop window has been expanded to cover the entire frame.
     * While true, no further expansion is attempted until the optimizer is reset
     * (e.g. via [CropOptimizer.updateCropOptimization] receiving a hand detection).
     */
    var useFullFrame: Boolean = false,

    // ── No-hand streak tracking ───────────────────────────────────────────────

    /**
     * Number of consecutive incoming frames in which no hand was detected.
     * Reset to 0 whenever a hand is detected or an expansion step fires.
     */
    var noHandDetectionFrames: Int = 0,

    /**
     * Monotonic timestamp (ms) when the current no-hand streak began.
     * -1L indicates no streak is currently in progress.
     */
    var noHandDetectionStartMs: Long = -1L,

    // ── Hand-confirmed tracking ───────────────────────────────────────────────

    /**
     * Number of consecutive incoming frames in which a hand was detected.
     * Reset to 0 whenever a frame without a hand arrives.
     */
    var handConfirmedFrames: Int = 0,
)

