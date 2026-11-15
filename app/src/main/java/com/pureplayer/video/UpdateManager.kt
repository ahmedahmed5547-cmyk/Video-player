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

internal fun MainActivity.normalizeGitHubRepoInput(rawInput: String): String {
    var clean = rawInput.trim()
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .removePrefix("github.com/")
        .trim('/')
    if (clean.endsWith(".git")) {
        clean = clean.removeSuffix(".git")
    }
    val parts = clean.split("/").filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0]}/${parts[1]}"
        parts.size == 1 -> "${parts[0]}/Video-player"
        else -> ""
    }
}

internal fun MainActivity.openGitHubUpdaterFlow() {
    val savedRepo = PlaybackStore.getGitHubRepo(this)
    if (savedRepo.isBlank() || !savedRepo.contains("/")) {
        showConfigureGitHubRepoDialog { configuredRepo ->
            checkForGitHubUpdatesNow(configuredRepo)
        }
    } else {
        checkForGitHubUpdatesNow(savedRepo)
    }
}

internal fun MainActivity.showConfigureGitHubRepoDialog(onConfigured: (String) -> Unit = {}) {
    val hostActivity = this
    val hostActivity = this
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val tvTitle = TextView(this).apply {
        text = getString(R.string.update_repo_setup_title)
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 17.5f
        setTypeface(typeface, Typeface.BOLD)
    }

    val tvDesc = TextView(this).apply {
        text = getString(R.string.update_repo_setup_desc)
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 13f
        setPadding(0, dp(6), 0, dp(14))
    }

    val currentRepo = PlaybackStore.getGitHubRepo(this)
    val etRepo = EditText(this).apply {
        setText(currentRepo)
        hint = "username/Video-player"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    stylePremiumInputBox(etRepo, bg, accent)

    container.addView(tvTitle)
    container.addView(tvDesc)
    container.addView(etRepo)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(18), 0, 0)
    }

    val dialog = AlertDialog.Builder(this).setView(container).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val btnCancel = TextView(this).apply {
        text = getString(R.string.cancel)
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 14f
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    bindSubDialogBackNavigation(dialog, btnCancel)

    val btnSave = TextView(this).apply {
        text = getString(R.string.save)
        setTextColor(Color.parseColor("#090D16"))
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(accent)
        }
        setOnClickListener {
            val normalized = normalizeGitHubRepoInput(etRepo.text.toString())
            if (!normalized.contains("/")) {
                Toast.makeText(hostActivity, getString(R.string.update_repo_invalid_toast), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                PlaybackStore.setGitHubRepo(hostActivity, normalized)
                onConfigured(normalized)
            }
        }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnSave)
    container.addView(btnRow)
    dialog.show()
}

internal fun MainActivity.checkForGitHubUpdatesNow(repoSlug: String) {
    Toast.makeText(this, getString(R.string.update_checking_toast, repoSlug), Toast.LENGTH_SHORT).show()

    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            var remoteVersionCode = -1
            var remoteVersionName = ""
            var directApkUrl: String? = null
            var latestActionsUrl = "https://github.com/$repoSlug/actions"

            // 1. Check remote app/build.gradle.kts on main or master branch for latest versionCode
            for (branch in listOf("main", "master")) {
                try {
                    val rawUrl = "https://raw.githubusercontent.com/$repoSlug/$branch/app/build.gradle.kts"
                    val conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                    }
                    if (conn.responseCode in 200..299) {
                        val gradleText = conn.inputStream.bufferedReader().use { it.readText() }
                        val codeMatch = Regex("versionCode\\s*=\\s*(\\d+)").find(gradleText)
                        val nameMatch = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradleText)
                        if (codeMatch != null) {
                            remoteVersionCode = codeMatch.groupValues[1].toIntOrNull() ?: -1
                            remoteVersionName = nameMatch?.groupValues?.getOrNull(1) ?: "$remoteVersionCode.0"
                            break
                        }
                    }
                } catch (e: Exception) { Log.w("UpdateManager", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            }

            // 2. Check GitHub Releases (latest) for a direct .apk download asset
            try {
                val relUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
                val conn = (URL(relUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                }
                if (conn.responseCode in 200..299) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonStr)
                    val assets = root.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val dlUrl = asset.optString("browser_download_url")
                            if (dlUrl.endsWith(".apk", ignoreCase = true)) {
                                directApkUrl = dlUrl
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.w("UpdateManager", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

            // 3. Check latest successful GitHub Actions workflow run URL
            try {
                val runsUrl = "https://api.github.com/repos/$repoSlug/actions/runs?status=success&per_page=1"
                val conn = (URL(runsUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                }
                if (conn.responseCode in 200..299) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val runs = JSONObject(jsonStr).optJSONArray("workflow_runs")
                    if (runs != null && runs.length() > 0) {
                        val htmlUrl = runs.optJSONObject(0)?.optString("html_url")
                        if (!htmlUrl.isNullOrBlank()) {
                            latestActionsUrl = htmlUrl
                        }
                    }
                }
            } catch (e: Exception) { Log.w("UpdateManager", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

            Pair(
                Pair(remoteVersionCode, remoteVersionName.ifBlank { "$remoteVersionCode.0" }),
                Pair(directApkUrl ?: "https://github.com/$repoSlug/releases/latest/download/PureVideoPlayer.apk", latestActionsUrl)
            )
        }

        if (isFinishing || isDestroyed) return@launch
        val (versionPair, urlPair) = result
        val (remoteCode, remoteName) = versionPair
        val (apkDownloadUrl, actionsRunUrl) = urlPair
        val currentCode = BuildConfig.VERSION_CODE
        val currentName = BuildConfig.VERSION_NAME

        val statusSubtitle = when {
            remoteCode > currentCode -> getString(R.string.update_status_new_available, remoteName, currentName)
            remoteCode == currentCode -> getString(R.string.update_status_up_to_date, currentName)
            else -> "GitHub: $repoSlug (v$currentName)"
        }

        val items = listOf(
            PremiumMenuItem(
                iconEmoji = "⬇️",
                title = getString(R.string.update_download_direct_btn),
                subtitle = apkDownloadUrl.substringAfter("github.com/"),
                isAccentHighlighted = true,
                onClick = {
                    downloadAndInstallApkInApp(apkDownloadUrl, actionsRunUrl)
                }
            ),
            PremiumMenuItem(
                iconEmoji = "🌐",
                title = getString(R.string.update_open_actions_btn),
                subtitle = actionsRunUrl.substringAfter("github.com/"),
                onClick = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionsRunUrl)))
                    } catch (e: Exception) { Log.w("UpdateManager", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
                }
            ),
            PremiumMenuItem(
                iconEmoji = "⚙️",
                title = getString(R.string.update_change_repo_btn, repoSlug),
                subtitle = getString(R.string.update_repo_setup_desc),
                onClick = {
                    showConfigureGitHubRepoDialog { newRepo ->
                        checkForGitHubUpdatesNow(newRepo)
                    }
                }
            )
        )

        showPremiumMenuDialog(
            headerIcon = "🚀",
            title = getString(R.string.update_check_option_title),
            subtitle = statusSubtitle,
            items = items
        )
    }
}

internal fun MainActivity.downloadAndInstallApkInApp(apkUrl: String, fallbackActionsUrl: String) {
    val hostActivity = this
    val hostActivity = this
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(22))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val tvTitle = TextView(this).apply {
        text = getString(R.string.update_downloading_title)
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
    }

    val tvPercent = TextView(this).apply {
        text = "0%"
        setTextColor(accent)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(10))
    }

    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(accent)
    }

    container.addView(tvTitle)
    container.addView(tvPercent)
    container.addView(progressBar)

    val dialog = AlertDialog.Builder(this)
        .setView(container)
        .setCancelable(false)
        .create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()

    lifecycleScope.launch {
        val downloadedFile = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", "PureVideoPlayer-Updater")
                }
                if (conn.responseCode !in 200..299) return@withContext null
                val totalBytes = conn.contentLength.coerceAtLeast(1)
                val outFile = File(externalCacheDir ?: cacheDir, "PureVideoPlayer_update.apk")
                if (outFile.exists()) outFile.delete()

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                progressBar.progress = pct
                                val mb = String.format(Locale.US, "%.1f MB", downloaded / (1024.0 * 1024.0))
                                tvPercent.text = "$pct% ($mb)"
                            }
                        }
                    }
                }
                if (outFile.exists() && outFile.length() > 100_000L) outFile else null
            } catch (_: Exception) {
                null
            }
        }

        if (isFinishing || isDestroyed) return@launch
        dialog.dismiss()

        if (downloadedFile != null) {
            installDownloadedApkFile(downloadedFile)
        } else {
            // If GitHub Release APK wasn't published yet, open the latest successful GitHub Actions run page directly!
            Toast.makeText(hostActivity, getString(R.string.update_fallback_actions_toast), Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackActionsUrl)))
            } catch (e: Exception) { Log.w("UpdateManager", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }
}

internal fun MainActivity.installDownloadedApkFile(apkFile: File) {
    try {
        val apkUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    } catch (_: Exception) {
        Toast.makeText(this, getString(R.string.update_install_error_toast), Toast.LENGTH_LONG).show()
    }
}
