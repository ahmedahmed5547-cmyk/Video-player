package com.pureplayer.video

import kotlin.math.ceil

enum class VlcAspectMode {
    SURFACE_BEST_FIT,
    SURFACE_FIT_SCREEN,
    SURFACE_FILL,
    SURFACE_16_9,
    SURFACE_4_3,
    SURFACE_ORIGINAL
}

data class SurfaceLayoutResult(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val aspectRatio: String?,
    val scale: Float
)

/**
 * Port of VLC for Android's changeSurfaceLayout() math.
 * Always computes explicit surface dimensions, aspectRatio, and scale from real
 * container size (sw, sh) and video visible size + SAR (sarNum / sarDen), so
 * switching from Fit -> Stretch (or any other sequence) never accumulates state
 * or crops the video.
 */
object VlcSurfaceLayoutMath {

    fun computeLayout(
        sw: Int,
        sh: Int,
        videoWidth: Int,
        videoHeight: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int,
        mode: VlcAspectMode
    ): SurfaceLayoutResult {
        if (sw <= 0 || sh <= 0) {
            return SurfaceLayoutResult(
                surfaceWidth = sw.coerceAtLeast(1),
                surfaceHeight = sh.coerceAtLeast(1),
                aspectRatio = null,
                scale = 0f
            )
        }

        // For Stretch / Fill, always fill the exact window dimensions with zero crop and zero black bars
        if (mode == VlcAspectMode.SURFACE_FILL) {
            return SurfaceLayoutResult(
                surfaceWidth = sw,
                surfaceHeight = sh,
                aspectRatio = "$sw:$sh",
                scale = 0f
            )
        }

        val effWidth = if (videoWidth > 0) videoWidth else sw
        val effHeight = if (videoHeight > 0) videoHeight else sh
        val effVisW = if (visibleWidth > 0) visibleWidth else effWidth
        val effVisH = if (visibleHeight > 0) visibleHeight else effHeight

        val vw: Double = if (sarDen == sarNum || sarDen <= 0 || sarNum <= 0) {
            effVisW.toDouble()
        } else {
            effVisW.toDouble() * sarNum.toDouble() / sarDen.toDouble()
        }

        val ar: Double = when (mode) {
            VlcAspectMode.SURFACE_16_9 -> 16.0 / 9.0
            VlcAspectMode.SURFACE_4_3 -> 4.0 / 3.0
            else -> vw / effVisH.toDouble()
        }

        val dar = sw.toDouble() / sh.toDouble()
        var dw = sw.toDouble()
        var dh = sh.toDouble()

        when (mode) {
            VlcAspectMode.SURFACE_BEST_FIT -> {
                if (dar < ar) {
                    dh = dw / ar
                } else {
                    dw = dh * ar
                }
            }
            VlcAspectMode.SURFACE_FIT_SCREEN -> {
                if (dar >= ar) {
                    dh = dw / ar
                } else {
                    dw = dh * ar
                }
            }
            VlcAspectMode.SURFACE_16_9, VlcAspectMode.SURFACE_4_3 -> {
                if (dar < ar) {
                    dh = dw / ar
                } else {
                    dw = dh * ar
                }
            }
            VlcAspectMode.SURFACE_ORIGINAL -> {
                dh = effVisH.toDouble()
                dw = vw
            }
            VlcAspectMode.SURFACE_FILL -> {
                // Handled above
            }
        }

        val surfaceW = ceil(dw * effWidth.toDouble() / effVisW.toDouble()).toInt().coerceAtLeast(1)
        val surfaceH = ceil(dh * effHeight.toDouble() / effVisH.toDouble()).toInt().coerceAtLeast(1)

        val forcedAspectRatio = when (mode) {
            VlcAspectMode.SURFACE_16_9 -> "16:9"
            VlcAspectMode.SURFACE_4_3 -> "4:3"
            else -> null
        }

        return SurfaceLayoutResult(
            surfaceWidth = surfaceW,
            surfaceHeight = surfaceH,
            aspectRatio = forcedAspectRatio,
            scale = 0f
        )
    }
}
