package com.pureplayer.video

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.CancellationSignal
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.pureplayer.video.databinding.ActivityMainBinding
import android.widget.PopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Hardware-backed AndroidKeyStore guard that binds Biometric authentication to the current
 * enrolled fingerprints ONLY after the owner enters the app Password.
 * If ANY new fingerprint is registered in Android Settings, AndroidKeyStore permanently invalidates
 * the key (setInvalidatedByBiometricEnrollment(true)), blocking fingerprint access until the
 * owner enters the Password again!
 */

data class PremiumMenuItem(
    val iconEmoji: String,
    val title: String,
    val subtitle: String? = null,
    val isAccentHighlighted: Boolean = false,
    val isDanger: Boolean = false,
    val sectionHeader: String? = null,
    val isInteractive: Boolean = true,
    val iconResId: Int = 0,
    val statusChip: String? = null,
    val onClick: () -> Unit = {}
)

internal fun MainActivity.dp(value: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}

/**
 * Premium UI Engine: Builds a sleek, floating, theme-harmonious card popup dialog
 * with a glowing top pill, bold header, subtitle badge, and tactile option cards!
 */
internal fun MainActivity.pushSettingsBackStack(reopenParent: () -> Unit) {
    settingsBackStack.addLast(reopenParent)
}

internal fun MainActivity.popSettingsBackStack(): Boolean {
    val parent = settingsBackStack.removeLastOrNull() ?: return false
    parent.invoke()
    return true
}

internal fun MainActivity.bindSubDialogBackNavigation(dialog: AlertDialog, btnCancelView: View? = null) {
    btnCancelView?.setOnClickListener {
        isNavigatingToSubDialog = true
        dialog.dismiss()
        isNavigatingToSubDialog = false
        popSettingsBackStack()
    }
    dialog.setOnCancelListener {
        if (!isNavigatingToSubDialog) {
            popSettingsBackStack()
        }
    }
}

internal fun MainActivity.animateDialogCardEntrance(cardView: View, staggeredRows: List<View> = emptyList()) {
    cardView.alpha = 0f
    cardView.translationY = dp(26).toFloat()
    cardView.scaleX = 0.94f
    cardView.scaleY = 0.94f
    cardView.animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(240L)
        .setInterpolator(DecelerateInterpolator(1.7f))
        .start()

    val rowInterp = DecelerateInterpolator(1.5f)
    staggeredRows.forEachIndexed { idx, row ->
        row.alpha = 0f
        row.translationY = dp(14).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((idx.coerceAtMost(10) * 22L) + 45L)
            .setDuration(210L)
            .setInterpolator(rowInterp)
            .start()
    }
}

internal fun MainActivity.closeActiveFullScreenDialogSmoothly(onFinished: () -> Unit = {}) {
    val dialog = activeFullScreenDialog
    val cardView = activeFullScreenCardView
    if (dialog == null || !dialog.isShowing) {
        isFullScreenDialogClosing = false
        activeFullScreenDialog = null
        activeFullScreenHost = null
        activeFullScreenCardView = null
        onFinished()
        return
    }
    if (cardView == null) {
        isFullScreenDialogClosing = false
        isNavigatingToSubDialog = true
        runCatching { dialog.dismiss() }
        isNavigatingToSubDialog = false
        activeFullScreenDialog = null
        activeFullScreenHost = null
        activeFullScreenCardView = null
        onFinished()
        return
    }
    val closeToken = fullScreenMenuRenderToken
    isFullScreenDialogClosing = true
    cardView.animate().cancel()
    cardView.animate()
        .alpha(0f)
        .translationY(dp(18).toFloat())
        .scaleX(0.95f)
        .scaleY(0.95f)
        .setDuration(135L)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            if (!isFullScreenDialogClosing || fullScreenMenuRenderToken != closeToken) {
                return@withEndAction
            }
            isFullScreenDialogClosing = false
            isNavigatingToSubDialog = true
            if (dialog.isShowing) {
                runCatching { dialog.dismiss() }
            }
            isNavigatingToSubDialog = false
            activeFullScreenDialog = null
            activeFullScreenHost = null
            activeFullScreenCardView = null
            onFinished()
        }
        .start()
}

internal fun MainActivity.navigateBackInSettingsOrClose() {
    if (!popSettingsBackStack()) {
        closeActiveFullScreenDialogSmoothly()
    }
}

/**
 * Renders any full-screen Settings or Menu page inside a single persistent Full-Screen AlertDialog host.
 * Navigating forward or backward swaps views in-place over an opaque surface background so the underlying
 * MainActivity (Video Folders) never flashes for even a single frame!
 */
internal fun MainActivity.presentInFullScreenHost(
    pageScroll: View,
    cardContainer: View,
    animatedRows: List<View>,
    surfaceColor: Int
) {
    activeFullScreenCardView?.animate()?.cancel()
    isFullScreenDialogClosing = false
    fullScreenMenuRenderToken++
    activeFullScreenCardView = cardContainer

    val existingDialog = activeFullScreenDialog
    val existingHost = activeFullScreenHost
    if (existingDialog != null && existingDialog.isShowing && existingHost != null) {
        existingHost.setBackgroundColor(surfaceColor)
        existingDialog.window?.setBackgroundDrawable(ColorDrawable(surfaceColor))
        existingHost.removeAllViews()
        existingHost.addView(
            pageScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        animateDialogCardEntrance(cardContainer, animatedRows)
        return
    }

    val host = FrameLayout(this).apply {
        setBackgroundColor(surfaceColor)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    host.addView(
        pageScroll,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    val dialog = AlertDialog.Builder(this, R.style.Theme_PureVideoPlayer_FullScreenDialog)
        .setView(host)
        .create()

    dialog.window?.setBackgroundDrawable(ColorDrawable(surfaceColor))

    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            navigateBackInSettingsOrClose()
            true
        } else {
            false
        }
    }

    dialog.setOnCancelListener {
        activeFullScreenDialog = null
        activeFullScreenHost = null
        activeFullScreenCardView = null
        if (!isNavigatingToSubDialog) {
            popSettingsBackStack()
        }
    }

    activeFullScreenDialog = dialog
    activeFullScreenHost = host
    dialog.show()
    dialog.window?.setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT
    )
    animateDialogCardEntrance(cardContainer, animatedRows)
}

/**
 * Premium UI Engine: Builds a sleek, full-screen, theme-harmonious page
 * with fluid spring entrance, staggered row cascade, and zero-flicker in-place navigation!
 */
internal fun MainActivity.showPremiumMenuDialog(
    headerIcon: String,
    title: String,
    subtitle: String?,
    items: List<PremiumMenuItem>,
    showHeaderVersionBadge: Boolean = false
) {
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)
    val isLight = PlaybackStore.isLightThemeActive(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val hairlineColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1FFFFFFF")
    val cardFillColor = if (isLight) Color.parseColor("#FFFFFF") else surface

    val outerScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(bg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val (winWidthDp, _) = FoldableWindowMetricsHelper.computeCurrentWindowDp(this)
    val effectiveWidthDp = FoldableAdaptiveMath.computeEffectiveMenuWidthDp(winWidthDp)
    val innerPadDp = FoldableAdaptiveMath.computeMenuInnerPaddingDp(winWidthDp)
    val cardWidthPx = if (effectiveWidthDp > 0) dp(effectiveWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT
    val cardContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "vexo_responsive_menu_card"
        setPadding(dp(innerPadDp), dp(22), dp(innerPadDp), dp(32))
        setBackgroundColor(bg)
        layoutParams = FrameLayout.LayoutParams(
            cardWidthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL
        )
    }
    outerScroll.addView(cardContainer)

    val topPill = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.argb(165, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }
    cardContainer.addView(topPill)

    // Luxury Header: VEXO Wordmark + Dynamic BuildConfig Version Pill + Glass Circle Close/Back
    val headerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), dp(16))
    }

    val headerTextCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titlePillRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val tvTitle = TextView(this).apply {
        text = title
        setTextColor(primaryText)
        textSize = 20f
        letterSpacing = 0.04f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    titlePillRow.addView(tvTitle)

    val tvVersionPill = TextView(this).apply {
        text = "v${BuildConfig.VERSION_NAME}"
        setTextColor(accent)
        textSize = 11f
        letterSpacing = 0.04f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(9), dp(3), dp(9), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(10)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }
    if (false && showHeaderVersionBadge) {
        titlePillRow.addView(tvVersionPill)
    }
    headerTextCol.addView(titlePillRow)

    if (!subtitle.isNullOrBlank()) {
        val tvSubtitle = TextView(this).apply {
            text = subtitle
            setTextColor(secondaryText)
            textSize = 12.5f
            letterSpacing = 0.02f
            setPadding(0, dp(4), 0, 0)
        }
        headerTextCol.addView(tvSubtitle)
    }

    val hasParentInStackTop = settingsBackStack.isNotEmpty()
    val btnHeaderNav = ImageView(this).apply {
        setImageResource(if (hasParentInStackTop) R.drawable.ic_arrow_back_modern else R.drawable.ic_close_modern)
        imageTintList = ColorStateList.valueOf(primaryText)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = getString(if (hasParentInStackTop) R.string.back else R.string.close)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginStart = dp(10)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1AFFFFFF"))
            setStroke(dp(1), hairlineColor)
        }
        setOnClickListener {
            navigateBackInSettingsOrClose()
        }
    }

    headerRow.addView(headerTextCol)
    headerRow.addView(btnHeaderNav)
    cardContainer.addView(headerRow)

    val divider = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(10)
        }
        setBackgroundColor(hairlineColor)
    }
    cardContainer.addView(divider)

    val animatedRows = mutableListOf<View>()

    for (item in items) {
        if (!item.sectionHeader.isNullOrBlank()) {
            val tvSec = TextView(this).apply {
                text = item.sectionHeader.uppercase(Locale.getDefault())
                setTextColor(accent)
                textSize = 11.5f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(6), dp(14), dp(6), dp(8))
            }
            cardContainer.addView(tvSec)
            animatedRows.add(tvSec)
        }

        val rowCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            val strokeC = when {
                item.isDanger -> Color.parseColor("#55F43F5E")
                item.isAccentHighlighted -> Color.argb(85, Color.red(accent), Color.green(accent), Color.blue(accent))
                else -> hairlineColor
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(cardFillColor)
                setStroke(dp(1), strokeC)
            }
        }

        val iconCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (item.isDanger) Color.parseColor("#24F43F5E")
                else Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent))
            )
            setStroke(dp(1), Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }

        val iconView: View = if (item.iconResId != 0) {
            ImageView(this).apply {
                setImageResource(item.iconResId)
                imageTintList = ColorStateList.valueOf(
                    if (item.isDanger) Color.parseColor("#F43F5E") else accent
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(14)
                }
                background = iconCircleBg
            }
        } else {
            TextView(this).apply {
                text = item.iconEmoji
                textSize = 17f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(14)
                }
                background = iconCircleBg
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvItemTitle = TextView(this).apply {
            text = item.title
            setTextColor(if (item.isDanger) Color.parseColor("#F43F5E") else primaryText)
            textSize = 15f
            letterSpacing = 0.01f
            setTypeface(typeface, Typeface.BOLD)
        }
        textCol.addView(tvItemTitle)

        if (!item.subtitle.isNullOrBlank()) {
            val tvItemSub = TextView(this).apply {
                text = item.subtitle
                setTextColor(secondaryText)
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            }
            textCol.addView(tvItemSub)
        }

        rowCard.addView(iconView)
        rowCard.addView(textCol)

        if (!item.statusChip.isNullOrBlank()) {
            val chipView = TextView(this).apply {
                text = item.statusChip
                setTextColor(accent)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(dp(1), Color.argb(75, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
            }
            rowCard.addView(chipView)
        }

        if (item.isInteractive) {
            val ivChevron = ImageView(this).apply {
                setImageResource(R.drawable.ic_lux_chevron)
                imageTintList = ColorStateList.valueOf(
                    if (item.isDanger) Color.parseColor("#F43F5E") else secondaryText
                )
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginStart = dp(6)
                }
            }
            rowCard.addView(ivChevron)

            rowCard.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(85L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(OvershootInterpolator(1.2f)).start()
                }
                false
            }

            rowCard.setOnClickListener {
                val tokenBefore = fullScreenMenuRenderToken
                val stackBefore = settingsBackStack.size
                item.onClick()
                if (fullScreenMenuRenderToken == tokenBefore && settingsBackStack.size <= stackBefore && settingsBackStack.isEmpty()) {
                    closeActiveFullScreenDialogSmoothly()
                }
            }
        }

        cardContainer.addView(rowCard)
        animatedRows.add(rowCard)
    }

    // Slim back pill only on sub-pages (since glass X already exists in header)
    if (settingsBackStack.isNotEmpty()) {
        val btnBackSlim = TextView(this).apply {
            text = getString(R.string.back_to_previous_menu)
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#18FFFFFF"))
                setStroke(dp(1), hairlineColor)
            }
            setOnClickListener { navigateBackInSettingsOrClose() }
        }
        cardContainer.addView(btnBackSlim)
    }

    // Refined Luxury Footer
    val tvFooter = TextView(this).apply {
        text = "V Player • Crafted by Ahmed M Moumen"
        gravity = Gravity.CENTER
        setTextColor(secondaryText)
        textSize = 11.5f
        letterSpacing = 0.05f
        setPadding(0, dp(22), 0, dp(4))
    }
    cardContainer.addView(tvFooter)

    presentInFullScreenHost(outerScroll, cardContainer, animatedRows, bg)
}

internal fun MainActivity.updateActiveFullScreenMenuLayoutForWindowSize() {
    val card = activeFullScreenCardView ?: return
    if (card.tag != "vexo_responsive_menu_card") return
    val (winWidthDp, _) = FoldableWindowMetricsHelper.computeCurrentWindowDp(this)
    val effectiveWidthDp = FoldableAdaptiveMath.computeEffectiveMenuWidthDp(winWidthDp)
    val innerPadDp = FoldableAdaptiveMath.computeMenuInnerPaddingDp(winWidthDp)
    val newWidthPx = if (effectiveWidthDp > 0) dp(effectiveWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT
    val lp = card.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(
        newWidthPx,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_HORIZONTAL
    )
    lp.width = newWidthPx
    lp.gravity = Gravity.CENTER_HORIZONTAL
    card.layoutParams = lp
    card.setPadding(dp(innerPadDp), dp(22), dp(innerPadDp), dp(32))
    card.requestLayout()
}
