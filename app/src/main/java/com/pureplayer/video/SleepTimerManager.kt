package com.pureplayer.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SleepTimerMode {
    OFF,
    COUNTDOWN,
    END_OF_MEDIA
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0L,
    val isFadingOut: Boolean = false
) {
    val isActive: Boolean get() = mode != SleepTimerMode.OFF
}

/**
 * Process-scoped Sleep Timer manager that survives screen rotation, backgrounding,
 * and switching between Video and Audio-Only background playback.
 */
object SleepTimerManager {
    private const val FADE_OUT_WINDOW_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    @Volatile
    private var onVolumeScale: ((Float) -> Unit)? = null

    @Volatile
    private var onTimerExpired: (() -> Unit)? = null

    fun attachCallbacks(
        volumeScaleCallback: (Float) -> Unit,
        expireCallback: () -> Unit
    ) {
        onVolumeScale = volumeScaleCallback
        onTimerExpired = expireCallback
    }

    fun startMinutes(minutes: Int) {
        val ms = minutes.coerceIn(1, 720) * 60_000L
        startCountdownMs(ms)
    }

    fun startCountdownMs(durationMs: Long) {
        timerJob?.cancel()
        val endTimeMs = System.currentTimeMillis() + durationMs.coerceAtLeast(1_000L)
        _state.value = SleepTimerState(
            mode = SleepTimerMode.COUNTDOWN,
            remainingMs = durationMs.coerceAtLeast(1_000L),
            isFadingOut = false
        )
        onVolumeScale?.invoke(1.0f)

        timerJob = scope.launch {
            while (true) {
                val left = (endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                val fading = left in 1L..FADE_OUT_WINDOW_MS
                _state.value = SleepTimerState(
                    mode = SleepTimerMode.COUNTDOWN,
                    remainingMs = left,
                    isFadingOut = fading
                )
                if (fading) {
                    val fraction = (left.toFloat() / FADE_OUT_WINDOW_MS.toFloat()).coerceIn(0.05f, 1.0f)
                    onVolumeScale?.invoke(fraction)
                }
                if (left <= 0L) {
                    _state.value = SleepTimerState()
                    onTimerExpired?.invoke()
                    onVolumeScale?.invoke(1.0f)
                    break
                }
                delay(500L)
            }
        }
    }

    fun startEndOfCurrentMedia() {
        timerJob?.cancel()
        onVolumeScale?.invoke(1.0f)
        _state.value = SleepTimerState(
            mode = SleepTimerMode.END_OF_MEDIA,
            remainingMs = 0L,
            isFadingOut = false
        )
    }

    fun extendByMinutes(extraMinutes: Int = 5) {
        val cur = _state.value
        if (cur.mode == SleepTimerMode.COUNTDOWN) {
            startCountdownMs(cur.remainingMs + extraMinutes * 60_000L)
        } else {
            startMinutes(extraMinutes)
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        onVolumeScale?.invoke(1.0f)
        _state.value = SleepTimerState()
    }

    fun onMediaTrackEnded(): Boolean {
        if (_state.value.mode == SleepTimerMode.END_OF_MEDIA) {
            _state.value = SleepTimerState()
            onTimerExpired?.invoke()
            return true
        }
        return false
    }

    fun formatRemaining(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hrs > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
