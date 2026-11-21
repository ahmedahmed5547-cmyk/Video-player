package com.pureplayer.video

import android.app.Activity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator

enum class WindowWidthSizeClass {
    COMPACT,   // < 600dp (phones, Z Fold cover screen, Z Flip cover screen)
    MEDIUM,    // 600dp .. 839dp (unfolded inner screen portrait, small tablets)
    EXPANDED   // >= 840dp (unfolded inner screen landscape, large tablets, Chromebooks)
}

enum class WindowHeightSizeClass {
    COMPACT,   // < 480dp (phone landscape, Flip cover screen)
    MEDIUM,    // 480dp .. 899dp (standard portrait, tablet landscape)
    EXPANDED   // >= 900dp (large tablet portrait)
}

enum class FoldPosture {
    FLAT_COMPACT,
    FLAT_EXPANDED,
    TABLETOP,  // HALF_OPENED + HORIZONTAL hinge
    BOOK       // HALF_OPENED + VERTICAL hinge
}

data class HingeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class AdaptiveWindowState(
    val widthDp: Int,
    val heightDp: Int,
    val widthClass: WindowWidthSizeClass,
    val heightClass: WindowHeightSizeClass,
    val posture: FoldPosture,
    val hingeBounds: HingeBounds? = null,
    val isSeparating: Boolean = false,
    val hasOcclusion: Boolean = false
)

object FoldableAdaptiveMath {

    fun classifyWidth(widthDp: Int): WindowWidthSizeClass {
        return when {
            widthDp >= 840 -> WindowWidthSizeClass.EXPANDED
            widthDp >= 600 -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.COMPACT
        }
    }

    fun classifyHeight(heightDp: Int): WindowHeightSizeClass {
        return when {
            heightDp >= 900 -> WindowHeightSizeClass.EXPANDED
            heightDp >= 480 -> WindowHeightSizeClass.MEDIUM
            else -> WindowHeightSizeClass.COMPACT
        }
    }

    fun resolvePosture(
        widthDp: Int,
        heightDp: Int,
        isHalfOpened: Boolean,
        isHorizontalHinge: Boolean,
        isSeparating: Boolean,
        hasOcclusion: Boolean,
        hingeBounds: HingeBounds?
    ): AdaptiveWindowState {
        val wClass = classifyWidth(widthDp)
        val hClass = classifyHeight(heightDp)
        val posture = when {
            isHalfOpened && isHorizontalHinge && hingeBounds != null -> FoldPosture.TABLETOP
            isHalfOpened && !isHorizontalHinge && hingeBounds != null -> FoldPosture.BOOK
            wClass != WindowWidthSizeClass.COMPACT -> FoldPosture.FLAT_EXPANDED
            else -> FoldPosture.FLAT_COMPACT
        }
        return AdaptiveWindowState(
            widthDp = widthDp,
            heightDp = heightDp,
            widthClass = wClass,
            heightClass = hClass,
            posture = posture,
            hingeBounds = hingeBounds,
            isSeparating = isSeparating,
            hasOcclusion = hasOcclusion
        )
    }

    /**
     * Caps settings/dialog width on Medium & Expanded screens (Fold unfolded / Tablets / Chromebooks)
     * so content stays centered and readable instead of stretching across the entire display.
     * Returns 0 on Compact screens (full width with standard padding).
     */
    fun computeDialogMaxWidthDp(widthClass: WindowWidthSizeClass): Int {
        return when (widthClass) {
            WindowWidthSizeClass.COMPACT -> 0
            WindowWidthSizeClass.MEDIUM -> 580
            WindowWidthSizeClass.EXPANDED -> 620
        }
    }

    /**
     * Computes Tabletop posture split: (upperVideoHeightPx, lowerControlsHeightPx)
     * strictly avoiding the horizontal hinge occlusion band (hingeTopPx to hingeBottomPx).
     */
    fun computeTabletopSplit(
        containerHeightPx: Int,
        hingeTopPx: Int,
        hingeBottomPx: Int
    ): Pair<Int, Int> {
        val safeTop = hingeTopPx.coerceIn(0, containerHeightPx)
        val safeBottom = hingeBottomPx.coerceIn(safeTop, containerHeightPx)
        val upperHeight = safeTop.coerceAtLeast(1)
        val lowerHeight = (containerHeightPx - safeBottom).coerceAtLeast(1)
        return Pair(upperHeight, lowerHeight)
    }

    /**
     * Computes Book posture split: (startVideoWidthPx, endControlsWidthPx)
     * strictly avoiding the vertical hinge occlusion band (hingeLeftPx to hingeRightPx).
     */
    fun computeBookSplit(
        containerWidthPx: Int,
        hingeLeftPx: Int,
        hingeRightPx: Int
    ): Pair<Int, Int> {
        val safeLeft = hingeLeftPx.coerceIn(0, containerWidthPx)
        val safeRight = hingeRightPx.coerceIn(safeLeft, containerWidthPx)
        val startWidth = safeLeft.coerceAtLeast(1)
        val endWidth = (containerWidthPx - safeRight).coerceAtLeast(1)
        return Pair(startWidth, endWidth)
    }
}

object FoldableWindowMetricsHelper {

    fun computeCurrentWindowDp(activity: Activity): Pair<Int, Int> {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val widthDp = (metrics.bounds.width() / density).toInt().coerceAtLeast(1)
        val heightDp = (metrics.bounds.height() / density).toInt().coerceAtLeast(1)
        return Pair(widthDp, heightDp)
    }

    fun fromWindowLayoutInfo(activity: Activity, layoutInfo: WindowLayoutInfo): AdaptiveWindowState {
        val (widthDp, heightDp) = computeCurrentWindowDp(activity)
        val foldingFeature = layoutInfo.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull()

        if (foldingFeature == null) {
            return FoldableAdaptiveMath.resolvePosture(
                widthDp = widthDp,
                heightDp = heightDp,
                isHalfOpened = false,
                isHorizontalHinge = false,
                isSeparating = false,
                hasOcclusion = false,
                hingeBounds = null
            )
        }

        val rect = foldingFeature.bounds
        val hinge = HingeBounds(rect.left, rect.top, rect.right, rect.bottom)
        val isHalfOpened = foldingFeature.state == FoldingFeature.State.HALF_OPENED
        val isHorizontal = foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL
        val hasOcclusion = foldingFeature.occlusionType == FoldingFeature.OcclusionType.FULL

        return FoldableAdaptiveMath.resolvePosture(
            widthDp = widthDp,
            heightDp = heightDp,
            isHalfOpened = isHalfOpened,
            isHorizontalHinge = isHorizontal,
            isSeparating = foldingFeature.isSeparating,
            hasOcclusion = hasOcclusion,
            hingeBounds = hinge
        )
    }
}
