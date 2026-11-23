package com.pureplayer.video

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Named gesture thresholds and tuning constants in one place.
 */
object PlayerGestureConstants {
    const val DEFAULT_TOUCH_SLOP_DP = 8f
    const val EDGE_EXCLUSION_DP = 24f
    const val LONG_PRESS_TIMEOUT_MS = 450L
    const val DOUBLE_TAP_TIMEOUT_MS = 260L
    const val DOUBLE_TAP_SLOP_MULTIPLIER = 4.0f
    const val LONG_PRESS_SPEED_RATE = 2.0f
    const val DOUBLE_TAP_SEEK_MS = 10_000L
    const val HORIZONTAL_SEEK_FULL_WIDTH_MS = 90_000L
    const val INDICATOR_FADE_DELAY_MS = 1_000L
    const val DOUBLE_TAP_LEFT_MAX_RATIO = 0.40f
    const val DOUBLE_TAP_RIGHT_MIN_RATIO = 0.60f
}

/**
 * Explicit states for the unified player gesture state machine.
 */
enum class PlayerGestureState {
    IDLE,
    PENDING,
    LONG_PRESS_SPEED,
    VOLUME,
    BRIGHTNESS,
    SEEK,
    DOUBLE_TAP,
    TAP
}

enum class PointerAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    POINTER_DOWN
}

enum class TapZone {
    LEFT,
    CENTER,
    RIGHT
}

/**
 * Pure-Kotlin pointer event data (decoupled from Android MotionEvent for deterministic unit testing).
 */
data class PointerInput(
    val action: PointerAction,
    val x: Float,
    val y: Float,
    val timeMs: Long,
    val pointerCount: Int = 1,
    val viewWidth: Float,
    val viewHeight: Float,
    val isOnControls: Boolean = false
)

/**
 * Production-grade single gesture state machine for VEXO Player.
 *
 * Guarantees:
 * - Mutual exclusion across LONG_PRESS_SPEED, VOLUME, BRIGHTNESS, SEEK, DOUBLE_TAP, and TAP.
 * - Strict axis locking once touch slop is exceeded (no mid-touch switching).
 * - Permanent long-press cancellation for a touch as soon as finger moves beyond touch slop.
 * - Exact playback speed restoration (never hardcoded to 1.0x) on release, cancel, multi-touch, or lifecycle interruption.
 * - Tokenized timer hygiene so stale callbacks can never affect a later touch.
 */
class PlayerGestureStateMachine(
    private val touchSlopPx: Float,
    private val edgeExclusionPx: Float,
    private val callbacks: Callbacks,
    private val longPressTimeoutMs: Long = PlayerGestureConstants.LONG_PRESS_TIMEOUT_MS,
    private val doubleTapTimeoutMs: Long = PlayerGestureConstants.DOUBLE_TAP_TIMEOUT_MS
) {

    interface Callbacks {
        fun scheduleLongPress(delayMs: Long, token: Long)
        fun cancelLongPress()
        fun scheduleSingleTap(delayMs: Long, token: Long)
        fun cancelSingleTap()
        fun getCurrentPlaybackSpeed(): Float
        fun onLongPressSpeedStart(targetSpeed: Float, savedOriginalSpeed: Float)
        fun onLongPressSpeedEnd(restoredSpeed: Float)
        fun onBrightnessDelta(deltaFraction: Float)
        fun onVolumeDelta(deltaFraction: Float)
        fun onSeekPreview(deltaMs: Long)
        fun onSeekCommit(deltaMs: Long)
        fun onSeekCancel()
        fun onDoubleTap(zone: TapZone)
        fun onSingleTapConfirmed()
        fun onMultiTouchCancel()
    }

    var state: PlayerGestureState = PlayerGestureState.IDLE
        private set

    private var touchGeneration: Long = 0L
    private var singleTapToken: Long = 0L

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var downViewWidth: Float = 1f
    private var downViewHeight: Float = 1f

    private var longPressCancelledForTouch: Boolean = false
    private var isLongPressSpeedActive: Boolean = false
    private var savedSpeedBeforeLongPress: Float = 1.0f
    private var currentSeekDeltaMs: Long = 0L

    private var pendingTapTimeMs: Long = 0L
    private var pendingTapX: Float = 0f
    private var pendingTapY: Float = 0f

    private val doubleTapSlopPx: Float
        get() = (touchSlopPx * PlayerGestureConstants.DOUBLE_TAP_SLOP_MULTIPLIER).coerceAtLeast(36f)

    /**
     * Feeds a pointer event into the state machine.
     * Returns true if the gesture system consumed/tracks this touch, false if ignored.
     */
    fun onPointerEvent(input: PointerInput): Boolean {
        // Multi-touch immediately cancels any pending or active single-finger gesture
        if (input.action == PointerAction.POINTER_DOWN || input.pointerCount > 1) {
            cancelAll(notifyMultiTouch = true)
            return false
        }

        return when (input.action) {
            PointerAction.DOWN -> handleDown(input)
            PointerAction.MOVE -> handleMove(input)
            PointerAction.UP -> handleUp(input)
            PointerAction.CANCEL -> handleCancel()
            PointerAction.POINTER_DOWN -> false
        }
    }

    private fun handleDown(input: PointerInput): Boolean {
        callbacks.cancelLongPress()
        touchGeneration++

        // Ignore touches starting on visible controls or inside system gesture edge zones
        if (input.isOnControls || isInSystemEdgeZone(input.x, input.y, input.viewWidth, input.viewHeight)) {
            state = PlayerGestureState.IDLE
            return false
        }

        // Safety: if a previous long-press speed somehow wasn't released, restore it first
        restoreSpeedIfActive()

        downX = input.x
        downY = input.y
        lastX = input.x
        lastY = input.y
        downViewWidth = input.viewWidth.coerceAtLeast(1f)
        downViewHeight = input.viewHeight.coerceAtLeast(1f)
        longPressCancelledForTouch = false
        currentSeekDeltaMs = 0L
        state = PlayerGestureState.PENDING

        callbacks.scheduleLongPress(longPressTimeoutMs, touchGeneration)
        return true
    }

    private fun handleMove(input: PointerInput): Boolean {
        when (state) {
            PlayerGestureState.IDLE,
            PlayerGestureState.DOUBLE_TAP,
            PlayerGestureState.TAP -> return false

            PlayerGestureState.LONG_PRESS_SPEED -> {
                // While long-press speed is active, ignore volume/brightness/seek until release
                lastX = input.x
                lastY = input.y
                return true
            }

            PlayerGestureState.PENDING -> {
                val totalDx = input.x - downX
                val totalDy = input.y - downY
                val distance = hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                if (distance <= touchSlopPx) {
                    return true
                }

                // Finger left touch slop before long-press timeout -> permanently cancel long-press for this touch
                longPressCancelledForTouch = true
                callbacks.cancelLongPress()
                clearPendingSingleTap()

                // Lock axis permanently until UP/CANCEL by dominant direction
                if (abs(totalDy) >= abs(totalDx)) {
                    state = if (downX < downViewWidth * 0.5f) {
                        PlayerGestureState.BRIGHTNESS
                    } else {
                        PlayerGestureState.VOLUME
                    }
                    // Apply initial vertical movement smoothly from touch-down reference
                    val deltaFraction = -(input.y - lastY) / downViewHeight
                    if (state == PlayerGestureState.BRIGHTNESS) {
                        callbacks.onBrightnessDelta(deltaFraction)
                    } else {
                        callbacks.onVolumeDelta(deltaFraction)
                    }
                } else {
                    state = PlayerGestureState.SEEK
                    currentSeekDeltaMs = computeSeekDeltaMs(totalDx, downViewWidth)
                    callbacks.onSeekPreview(currentSeekDeltaMs)
                }

                lastX = input.x
                lastY = input.y
                return true
            }

            PlayerGestureState.BRIGHTNESS -> {
                val deltaFraction = -(input.y - lastY) / downViewHeight
                lastX = input.x
                lastY = input.y
                if (deltaFraction != 0f) {
                    callbacks.onBrightnessDelta(deltaFraction)
                }
                return true
            }

            PlayerGestureState.VOLUME -> {
                val deltaFraction = -(input.y - lastY) / downViewHeight
                lastX = input.x
                lastY = input.y
                if (deltaFraction != 0f) {
                    callbacks.onVolumeDelta(deltaFraction)
                }
                return true
            }

            PlayerGestureState.SEEK -> {
                val totalDx = input.x - downX
                lastX = input.x
                lastY = input.y
                currentSeekDeltaMs = computeSeekDeltaMs(totalDx, downViewWidth)
                callbacks.onSeekPreview(currentSeekDeltaMs)
                return true
            }
        }
    }

    private fun handleUp(input: PointerInput): Boolean {
        callbacks.cancelLongPress()
        touchGeneration++

        val currentState = state
        when (currentState) {
            PlayerGestureState.LONG_PRESS_SPEED -> {
                restoreSpeedIfActive()
                clearPendingSingleTap()
                state = PlayerGestureState.IDLE
                return true
            }

            PlayerGestureState.SEEK -> {
                val finalDelta = currentSeekDeltaMs
                currentSeekDeltaMs = 0L
                clearPendingSingleTap()
                state = PlayerGestureState.IDLE
                callbacks.onSeekCommit(finalDelta)
                return true
            }

            PlayerGestureState.VOLUME,
            PlayerGestureState.BRIGHTNESS -> {
                clearPendingSingleTap()
                state = PlayerGestureState.IDLE
                return true
            }

            PlayerGestureState.PENDING -> {
                // Quick tap (finger stayed within touch slop and did not long-press)
                val withinDoubleTapWindow = pendingTapTimeMs > 0L &&
                    (input.timeMs - pendingTapTimeMs) in 0L..doubleTapTimeoutMs
                val tapDist = hypot((input.x - pendingTapX).toDouble(), (input.y - pendingTapY).toDouble()).toFloat()

                if (withinDoubleTapWindow && tapDist <= doubleTapSlopPx) {
                    clearPendingSingleTap()
                    state = PlayerGestureState.DOUBLE_TAP
                    val zone = resolveTapZone(input.x, downViewWidth)
                    callbacks.onDoubleTap(zone)
                    state = PlayerGestureState.IDLE
                } else {
                    clearPendingSingleTap()
                    pendingTapTimeMs = input.timeMs
                    pendingTapX = input.x
                    pendingTapY = input.y
                    singleTapToken++
                    state = PlayerGestureState.IDLE
                    callbacks.scheduleSingleTap(doubleTapTimeoutMs, singleTapToken)
                }
                return true
            }

            else -> {
                state = PlayerGestureState.IDLE
                return false
            }
        }
    }

    private fun handleCancel(): Boolean {
        cancelAll(notifyMultiTouch = false)
        return true
    }

    /**
     * Must be invoked by the host when the scheduled long-press timer fires.
     */
    fun onLongPressTimeout(token: Long) {
        if (token != touchGeneration) return
        if (state != PlayerGestureState.PENDING || longPressCancelledForTouch) return

        clearPendingSingleTap()
        savedSpeedBeforeLongPress = callbacks.getCurrentPlaybackSpeed()
        isLongPressSpeedActive = true
        state = PlayerGestureState.LONG_PRESS_SPEED
        callbacks.onLongPressSpeedStart(
            targetSpeed = PlayerGestureConstants.LONG_PRESS_SPEED_RATE,
            savedOriginalSpeed = savedSpeedBeforeLongPress
        )
    }

    /**
     * Must be invoked by the host when the single-tap confirmation window expires.
     */
    fun onSingleTapTimeout(token: Long) {
        if (token != singleTapToken) return
        if (pendingTapTimeMs == 0L || state != PlayerGestureState.IDLE) return

        pendingTapTimeMs = 0L
        state = PlayerGestureState.TAP
        callbacks.onSingleTapConfirmed()
        state = PlayerGestureState.IDLE
    }

    /**
     * Lifecycle & interruption safety entry point.
     * Cancels all pending timers/callbacks, restores playback speed if active, cancels seek preview,
     * and resets to IDLE so no stale state or stuck speed can ever survive.
     */
    fun cancelAll(notifyMultiTouch: Boolean = false) {
        touchGeneration++
        callbacks.cancelLongPress()
        clearPendingSingleTap()

        val wasSeeking = (state == PlayerGestureState.SEEK)
        currentSeekDeltaMs = 0L

        restoreSpeedIfActive()
        state = PlayerGestureState.IDLE

        if (wasSeeking) {
            callbacks.onSeekCancel()
        }
        if (notifyMultiTouch) {
            callbacks.onMultiTouchCancel()
        }
    }

    fun isLongPressSpeedCurrentlyActive(): Boolean = isLongPressSpeedActive

    private fun restoreSpeedIfActive() {
        if (isLongPressSpeedActive) {
            isLongPressSpeedActive = false
            callbacks.onLongPressSpeedEnd(savedSpeedBeforeLongPress)
        }
    }

    private fun clearPendingSingleTap() {
        singleTapToken++
        pendingTapTimeMs = 0L
        callbacks.cancelSingleTap()
    }

    private fun isInSystemEdgeZone(x: Float, y: Float, width: Float, height: Float): Boolean {
        if (width <= 0f || height <= 0f || edgeExclusionPx <= 0f) return false
        return x < edgeExclusionPx ||
            x > (width - edgeExclusionPx) ||
            y < edgeExclusionPx ||
            y > (height - edgeExclusionPx)
    }

    private fun computeSeekDeltaMs(totalDx: Float, viewWidth: Float): Long {
        if (viewWidth <= 0f) return 0L
        return ((totalDx / viewWidth) * PlayerGestureConstants.HORIZONTAL_SEEK_FULL_WIDTH_MS).toLong()
    }

    private fun resolveTapZone(x: Float, viewWidth: Float): TapZone {
        val ratio = if (viewWidth > 0f) x / viewWidth else 0.5f
        return when {
            ratio < PlayerGestureConstants.DOUBLE_TAP_LEFT_MAX_RATIO -> TapZone.LEFT
            ratio > PlayerGestureConstants.DOUBLE_TAP_RIGHT_MIN_RATIO -> TapZone.RIGHT
            else -> TapZone.CENTER
        }
    }
}
