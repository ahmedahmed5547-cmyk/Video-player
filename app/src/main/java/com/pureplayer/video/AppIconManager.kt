package com.pureplayer.video

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Manages V Player adaptive launcher icon styles via activity-alias switching.
 * Guarantees exactly one launcher alias is enabled at any time (default on first run)
 * using PackageManager.DONT_KILL_APP without altering applicationId or MainActivity identity.
 */
object AppIconManager {

    private const val TAG = "AppIconManager"

    const val STYLE_DEFAULT = "default"
    const val STYLE_CRYSTAL = "crystal_prism"
    const val STYLE_ROYAL_V = "royal_v_gold"
    const val STYLE_MATERIAL_YOU = "material_you"
    const val STYLE_GLASS_LIGHT = "glass_light"
    const val STYLE_GLASS_DARK = "glass_dark"
    const val STYLE_DARK = "obsidian_dark"
    const val STYLE_GRADIENT = "aurora_gradient"
    const val STYLE_EMERALD = "emerald_prism"

    data class AppIconOption(
        val id: String,
        val aliasClassName: String,
        val mipmapResId: Int,
        val titleResId: Int,
        val subtitleResId: Int
    )

    val allOptions: List<AppIconOption> = listOf(
        AppIconOption(
            id = STYLE_DEFAULT,
            aliasClassName = "com.pureplayer.video.LauncherAliasDefault",
            mipmapResId = R.mipmap.ic_launcher,
            titleResId = R.string.icon_style_default,
            subtitleResId = R.string.icon_style_default_sub
        ),
        AppIconOption(
            id = STYLE_CRYSTAL,
            aliasClassName = "com.pureplayer.video.LauncherAliasCrystal",
            mipmapResId = R.mipmap.ic_launcher_crystal,
            titleResId = R.string.icon_style_crystal,
            subtitleResId = R.string.icon_style_crystal_sub
        ),
        AppIconOption(
            id = STYLE_ROYAL_V,
            aliasClassName = "com.pureplayer.video.LauncherAliasRoyalV",
            mipmapResId = R.mipmap.ic_launcher_royal_v,
            titleResId = R.string.icon_style_royal_v,
            subtitleResId = R.string.icon_style_royal_v_sub
        ),
        AppIconOption(
            id = STYLE_MATERIAL_YOU,
            aliasClassName = "com.pureplayer.video.LauncherAliasMaterialYou",
            mipmapResId = R.mipmap.ic_launcher_material_you,
            titleResId = R.string.icon_style_material_you,
            subtitleResId = R.string.icon_style_material_you_sub
        ),
        AppIconOption(
            id = STYLE_GLASS_LIGHT,
            aliasClassName = "com.pureplayer.video.LauncherAliasGlassLight",
            mipmapResId = R.mipmap.ic_launcher_glass_light,
            titleResId = R.string.icon_style_glass_light,
            subtitleResId = R.string.icon_style_glass_light_sub
        ),
        AppIconOption(
            id = STYLE_GLASS_DARK,
            aliasClassName = "com.pureplayer.video.LauncherAliasGlassDark",
            mipmapResId = R.mipmap.ic_launcher_glass_dark,
            titleResId = R.string.icon_style_glass_dark,
            subtitleResId = R.string.icon_style_glass_dark_sub
        ),
        AppIconOption(
            id = STYLE_DARK,
            aliasClassName = "com.pureplayer.video.LauncherAliasDark",
            mipmapResId = R.mipmap.ic_launcher_dark,
            titleResId = R.string.icon_style_dark,
            subtitleResId = R.string.icon_style_dark_sub
        ),
        AppIconOption(
            id = STYLE_GRADIENT,
            aliasClassName = "com.pureplayer.video.LauncherAliasGradient",
            mipmapResId = R.mipmap.ic_launcher_gradient,
            titleResId = R.string.icon_style_gradient,
            subtitleResId = R.string.icon_style_gradient_sub
        ),
        AppIconOption(
            id = STYLE_EMERALD,
            aliasClassName = "com.pureplayer.video.LauncherAliasEmerald",
            mipmapResId = R.mipmap.ic_launcher_emerald,
            titleResId = R.string.icon_style_emerald,
            subtitleResId = R.string.icon_style_emerald_sub
        )
    )

    val allAliasClassNames: List<String> = listOf(
        "com.pureplayer.video.LauncherAliasDefault",
        "com.pureplayer.video.LauncherAliasCrystal",
        "com.pureplayer.video.LauncherAliasRoyalV",
        "com.pureplayer.video.LauncherAliasMaterialYou",
        "com.pureplayer.video.LauncherAliasGlassLight",
        "com.pureplayer.video.LauncherAliasGlassDark",
        "com.pureplayer.video.LauncherAliasDark",
        "com.pureplayer.video.LauncherAliasGradient",
        "com.pureplayer.video.LauncherAliasEmerald"
    )

    private val styleToAlias: Map<String, String> = mapOf(
        STYLE_DEFAULT to "com.pureplayer.video.LauncherAliasDefault",
        STYLE_CRYSTAL to "com.pureplayer.video.LauncherAliasCrystal",
        STYLE_ROYAL_V to "com.pureplayer.video.LauncherAliasRoyalV",
        STYLE_MATERIAL_YOU to "com.pureplayer.video.LauncherAliasMaterialYou",
        STYLE_GLASS_LIGHT to "com.pureplayer.video.LauncherAliasGlassLight",
        STYLE_GLASS_DARK to "com.pureplayer.video.LauncherAliasGlassDark",
        STYLE_DARK to "com.pureplayer.video.LauncherAliasDark",
        STYLE_GRADIENT to "com.pureplayer.video.LauncherAliasGradient",
        STYLE_EMERALD to "com.pureplayer.video.LauncherAliasEmerald"
    )

    /**
     * Pure-Kotlin state computation for unit testing:
     * Returns a map of aliasClassName -> enabled, guaranteeing EXACTLY ONE alias is enabled.
     * Unknown style IDs safely fall back to STYLE_DEFAULT.
     */
    fun computeAliasStates(requestedStyleId: String): Map<String, Boolean> {
        val targetAlias = styleToAlias[requestedStyleId] ?: styleToAlias.getValue(STYLE_DEFAULT)
        return allAliasClassNames.associateWith { it == targetAlias }
    }

    /**
     * Applies the selected launcher icon style safely without killing the app.
     * Enables the target alias FIRST before disabling others so the launcher never sees zero icons.
     */
    fun applyIconStyle(context: Context, styleId: String): Boolean {
        val validStyle = if (styleToAlias.containsKey(styleId)) styleId else STYLE_DEFAULT
        PlaybackStore.setAppIconStyle(context, validStyle)
        val states = computeAliasStates(validStyle)
        val pm = context.packageManager

        return try {
            // Step 1: Enable target alias first
            val targetAlias = states.entries.first { it.value }.key
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, targetAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // Step 2: Disable all other aliases
            for ((alias, enabled) in states) {
                if (!enabled) {
                    pm.setComponentEnabledSetting(
                        ComponentName(context.packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch launcher icon alias: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Re-verifies saved icon alias state safely on app startup (after updates or reboots).
     */
    fun ensureSavedIconApplied(context: Context) {
        val saved = PlaybackStore.getAppIconStyle(context)
        val states = computeAliasStates(saved)
        val pm = context.packageManager
        try {
            val targetAlias = states.entries.first { it.value }.key
            val currentState = pm.getComponentEnabledSetting(ComponentName(context.packageName, targetAlias))
            val isAlreadyTarget = if (saved == STYLE_DEFAULT) {
                currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (!isAlreadyTarget) {
                applyIconStyle(context, saved)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Icon alias verification skipped: ${e.javaClass.simpleName}")
        }
    }
}
