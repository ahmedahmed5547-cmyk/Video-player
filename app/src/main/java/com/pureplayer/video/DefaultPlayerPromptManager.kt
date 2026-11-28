package com.pureplayer.video

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pure-Kotlin eligibility policy for the "Make V Player your default video player" prompt.
 * Never shows on first launch, respects "Don't ask again" & 7-day "Later" cooldown,
 * and skips whenever V Player is already the system default video handler.
 */
object DefaultPlayerPromptPolicy {
    const val MIN_VIDEO_PLAYS = 3
    const val MIN_SESSIONS_WITH_PLAY = 2
    const val LATER_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L

    fun shouldShowPrompt(
        isAlreadyDefault: Boolean,
        dontAskAgain: Boolean,
        videoPlayCount: Int,
        sessionCount: Int,
        lastShownMs: Long,
        nowMs: Long
    ): Boolean {
        if (isAlreadyDefault || dontAskAgain) return false
        val enoughUsage = videoPlayCount >= MIN_VIDEO_PLAYS ||
            (sessionCount >= MIN_SESSIONS_WITH_PLAY && videoPlayCount >= 1)
        if (!enoughUsage) return false
        if (lastShownMs > 0L && (nowMs - lastShownMs) < LATER_COOLDOWN_MS) return false
        return true
    }
}

/**
 * Coordinates programmatic default-video-player detection and a modern, theme-aware prompt card
 * matching all V Player themes (Light / Dark / System + all 10 color themes).
 */
object DefaultPlayerPromptManager {

    private var hasShownInCurrentProcess = false

    fun recordAppSession(context: Context) {
        PlaybackStore.incrementDefaultPromptSessionCount(context)
    }

    fun recordVideoPlayed(context: Context) {
        PlaybackStore.incrementDefaultPromptVideoPlayCount(context)
    }

    /**
     * Programmatically checks whether V Player is currently set as the default handler for video/mp4.
     */
    fun isVPlayerDefaultVideoHandler(context: Context): Boolean {
        return runCatching {
            val probeIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.pureplayer.video.probe/sample.mp4"), "video/mp4")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    probeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfo?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    fun maybeShowPrompt(activity: MainActivity) {
        if (hasShownInCurrentProcess || activity.isFinishing || activity.isDestroyed) return
        val nowMs = System.currentTimeMillis()
        val eligible = DefaultPlayerPromptPolicy.shouldShowPrompt(
            isAlreadyDefault = isVPlayerDefaultVideoHandler(activity),
            dontAskAgain = PlaybackStore.isDefaultPromptDontAskAgain(activity),
            videoPlayCount = PlaybackStore.getDefaultPromptVideoPlayCount(activity),
            sessionCount = PlaybackStore.getDefaultPromptSessionCount(activity),
            lastShownMs = PlaybackStore.getDefaultPromptLastShownMs(activity),
            nowMs = nowMs
        )
        if (!eligible) return

        hasShownInCurrentProcess = true
        PlaybackStore.setDefaultPromptLastShownMs(activity, nowMs)
        showThemedDefaultPlayerDialog(activity)
    }

    private fun openSystemDefaultAppsSettings(context: Context) {
        val opened = runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!opened) {
            runCatching {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
        Toast.makeText(context, context.getString(R.string.default_player_steps_toast), Toast.LENGTH_LONG).show()
    }

    private fun showThemedDefaultPlayerDialog(activity: MainActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val accent = PlaybackStore.getAccentColor(activity)
        val surface = PlaybackStore.getSurfaceColor(activity)
        val primaryText = PlaybackStore.getPrimaryTextColor(activity)
        val secondaryText = PlaybackStore.getSecondaryTextColor(activity)
        val isLight = PlaybackStore.isLightThemeActive(activity)
        val borderColor = Color.argb(if (isLight) 70 else 48, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(surface)
                setStroke(dp(1), borderColor)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top App Icon Badge
        val iconBadge = ImageView(activity).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(iconBadge)

        // Headline
        val tvTitle = TextView(activity).apply {
            text = activity.getString(R.string.default_player_prompt_title)
            setTextColor(primaryText)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(tvTitle)

        // Friendly body + simple system steps
        val tvMessage = TextView(activity).apply {
            text = activity.getString(R.string.default_player_prompt_message)
            setTextColor(secondaryText)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        card.addView(tvMessage)

        // Primary CTA: "Set as Default"
        val primaryBtnShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(accent)
        }
        val onAccentText = if (Color.red(accent) * 0.299 + Color.green(accent) * 0.587 + Color.blue(accent) * 0.114 > 170) {
            Color.parseColor("#0B1120")
        } else {
            Color.WHITE
        }
        val btnSetDefault = TextView(activity).apply {
            text = activity.getString(R.string.default_player_btn_set)
            setTextColor(onAccentText)
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), primaryBtnShape, null)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                dialog.dismiss()
                openSystemDefaultAppsSettings(activity)
            }
        }
        card.addView(btnSetDefault)

        // Secondary Row: "Later" & "Don't ask again"
        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val laterShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), borderColor)
        }
        val btnLater = TextView(activity).apply {
            text = activity.getString(R.string.default_player_btn_later)
            setTextColor(primaryText)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            minimumHeight = dp(44)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))), laterShape, null)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                PlaybackStore.setDefaultPromptLastShownMs(activity, System.currentTimeMillis())
                dialog.dismiss()
            }
        }

        val btnDontAsk = TextView(activity).apply {
            text = activity.getString(R.string.default_player_btn_dont_ask)
            setTextColor(secondaryText)
            textSize = 13f
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(36, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))),
                null,
                GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PlaybackStore.setDefaultPromptDontAskAgain(activity, true)
                dialog.dismiss()
            }
        }

        secondaryRow.addView(btnLater)
        secondaryRow.addView(btnDontAsk)
        card.addView(secondaryRow)

        val wrapper = LinearLayout(activity).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(card)
        }
        dialog.setContentView(wrapper)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
