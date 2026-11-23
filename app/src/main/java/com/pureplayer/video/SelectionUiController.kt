package com.pureplayer.video

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun MainActivity.setupSelectionModeUi() {
    binding.btnSelectionClose.setOnClickListener {
        selectionViewModel.exitSelection()
    }
    binding.btnSelectionSelectAll.setOnClickListener {
        val allKeys = getVisibleSelectableKeys()
        selectionViewModel.selectAll(allKeys, isVault = isInVaultTab)
    }
    binding.btnSelectionOverflow.setOnClickListener {
        showSelectionOverflowMenu()
    }

    lifecycleScope.launch {
        selectionViewModel.state.collect { st ->
            applySelectionBarState(st)
        }
    }
}

internal fun MainActivity.applySelectionBarState(st: SelectionState) {
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)

    adapter.updateSelectionState(st.isActive, st.selectedKeys)

    if (st.isActive) {
        binding.selectionTopBar.setBackgroundColor(surface)
        binding.tvSelectionCount.setTextColor(primaryText)
        binding.tvSelectionCount.text = getString(R.string.selection_count_format, st.count)
        binding.btnSelectionClose.imageTintList = ColorStateList.valueOf(primaryText)
        binding.btnSelectionOverflow.setTextColor(primaryText)
        binding.btnSelectionSelectAll.setTextColor(accent)
        binding.btnSelectionSelectAll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        if (binding.selectionTopBar.visibility != View.VISIBLE) {
            binding.selectionTopBar.visibility = View.VISIBLE
            if (!BatteryOptimizationManager.shouldReduceAnimations(this)) {
                binding.selectionTopBar.alpha = 0f
                binding.selectionTopBar.translationY = -dp(12).toFloat()
                binding.selectionTopBar.animate().alpha(1f).translationY(0f).setDuration(170L)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                binding.selectionTopBar.alpha = 1f
                binding.selectionTopBar.translationY = 0f
            }
        }
    } else {
        if (binding.selectionTopBar.visibility == View.VISIBLE) {
            binding.selectionTopBar.visibility = View.GONE
        }
    }
}

internal fun MainActivity.getVisibleSelectableKeys(): List<String> {
    val out = mutableListOf<String>()
    adapter.currentList.forEach { item ->
        when (item) {
            is VideoItem -> out.add(SelectionViewModel.keyForVideo(item))
            is VideoFolder -> out.add(SelectionViewModel.keyForFolder(item))
        }
    }
    return out
}

internal fun MainActivity.resolveSelectedItems(): Pair<List<VideoItem>, List<VideoFolder>> {
    val keys = selectionViewModel.state.value.selectedKeys
    val videos = mutableListOf<VideoItem>()
    val folders = mutableListOf<VideoFolder>()
    val seenVideos = hashSetOf<String>()
    val seenFolders = hashSetOf<String>()

    fun inspectList(list: List<Any>) {
        list.forEach { item ->
            when (item) {
                is VideoItem -> {
                    val k = SelectionViewModel.keyForVideo(item)
                    if (k in keys && seenVideos.add(k)) videos.add(item)
                }
                is VideoFolder -> {
                    val k = SelectionViewModel.keyForFolder(item)
                    if (k in keys && seenFolders.add(k)) folders.add(item)
                }
            }
        }
    }
    inspectList(adapter.currentList)
    inspectList(allDiscoveredVideos)
    inspectList(allDiscoveredFolders)
    return videos to folders
}

internal fun MainActivity.showSelectionOverflowMenu() {
    val st = selectionViewModel.state.value
    if (!st.isActive || st.count == 0) return
    val (selectedVideos, selectedFolders) = resolveSelectedItems()
    if (selectedVideos.isEmpty() && selectedFolders.isEmpty()) {
        selectionViewModel.exitSelection()
        return
    }
    val inVault = isInVaultTab
    val items = mutableListOf<PremiumMenuItem>()

    // Single video details (Main Library or Vault)
    if (selectedVideos.size == 1 && selectedFolders.isEmpty()) {
        val singleVideo = selectedVideos.first()
        items.add(
            PremiumMenuItem(
                iconEmoji = "ℹ️",
                title = getString(R.string.selection_action_details),
                subtitle = singleVideo.title,
                isAccentHighlighted = true,
                onClick = { showFullMediaDetailsDialog(singleVideo, isVault = inVault) }
            )
        )
    }

    if (inVault) {
        // Vault-specific actions (Task 1B)
        items.add(
            PremiumMenuItem(
                iconEmoji = "🔓",
                title = getString(R.string.vault_action_remove),
                subtitle = getString(R.string.vault_action_remove_sub),
                onClick = { removeSelectedFromVaultBatch(selectedVideos, selectedFolders) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "📂",
                title = getString(R.string.vault_action_move_folder),
                subtitle = getString(R.string.selection_paste_sub),
                onClick = { startBatchCopyOrMoveFlow(selectedVideos, selectedFolders, isMove = true, isVault = true) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "📋",
                title = getString(R.string.selection_action_copy),
                subtitle = getString(R.string.selection_paste_sub),
                onClick = { startBatchCopyOrMoveFlow(selectedVideos, selectedFolders, isMove = false, isVault = true) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "✂️",
                title = getString(R.string.selection_action_move),
                subtitle = getString(R.string.selection_paste_sub),
                onClick = { startBatchCopyOrMoveFlow(selectedVideos, selectedFolders, isMove = true, isVault = true) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.vault_action_delete_permanent),
                subtitle = getString(R.string.selection_count_format, st.count),
                onClick = { confirmAndBatchDeleteSelected(selectedVideos, selectedFolders, isVault = true) }
            )
        )
    } else {
        // Main library actions (Task 1)
        items.add(
            PremiumMenuItem(
                iconEmoji = "🔒",
                title = getString(R.string.selection_action_add_vault),
                subtitle = getString(R.string.selection_action_add_vault_sub),
                onClick = { addSelectedToVaultBatch(selectedVideos, selectedFolders) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "📋",
                title = getString(R.string.selection_action_copy),
                subtitle = getString(R.string.selection_paste_sub),
                onClick = { startBatchCopyOrMoveFlow(selectedVideos, selectedFolders, isMove = false, isVault = false) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "✂️",
                title = getString(R.string.selection_action_move),
                subtitle = getString(R.string.selection_paste_sub),
                onClick = { startBatchCopyOrMoveFlow(selectedVideos, selectedFolders, isMove = true, isVault = false) }
            )
        )
        items.add(
            PremiumMenuItem(
                iconEmoji = "🗑️",
                title = getString(R.string.delete_action),
                subtitle = getString(R.string.selection_count_format, st.count),
                onClick = { confirmAndBatchDeleteSelected(selectedVideos, selectedFolders, isVault = false) }
            )
        )
    }

    showPremiumMenuDialog(
        headerIcon = if (inVault) "🛡️" else "☑️",
        title = getString(R.string.selection_count_format, st.count),
        subtitle = if (inVault) getString(R.string.tab_vault) else getString(R.string.tab_video_folders_compact),
        items = items,
        showHeaderVersionBadge = false
    )
}

internal fun MainActivity.showFullMediaDetailsDialog(video: VideoItem, isVault: Boolean) {
    lifecycleScope.launch {
        val details = withContext(Dispatchers.IO) {
            var codecStr = "H.264 / Unknown"
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(this@showFullMediaDetailsDialog, video.uri, null)
                val codecs = mutableListOf<String>()
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        codecs.add(mime.substringAfter("/").uppercase(Locale.US))
                    }
                }
                if (codecs.isNotEmpty()) codecStr = codecs.distinct().joinToString(" • ")
            } catch (_: Exception) {
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
            val fileObj = if (video.path.isNotBlank()) File(video.path) else null
            val lastModMs = fileObj?.takeIf { it.exists() }?.lastModified() ?: (video.dateAddedSec * 1000L)
            val dateFormatted = if (lastModMs > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastModMs))
            } else {
                "-"
            }
            val sizeFormatted = String.format(Locale.US, "%.2f MB", video.size / (1024.0 * 1024.0))
            val durFormatted = PlaybackStore.formatTime(video.duration)
            val resFormatted = if (video.width > 0 && video.height > 0) "${video.width} × ${video.height} (${video.getResolutionLabel()})" else video.getResolutionLabel()
            listOf(
                getString(R.string.detail_name) to video.title,
                getString(R.string.detail_path) to (video.path.ifBlank { video.uri.toString() }),
                getString(R.string.detail_size) to sizeFormatted,
                getString(R.string.detail_duration) to durFormatted,
                getString(R.string.detail_resolution) to resFormatted,
                getString(R.string.detail_codec) to codecStr,
                (if (isVault) getString(R.string.detail_date_vault) else getString(R.string.detail_date_modified)) to dateFormatted
            )
        }

        val items = details.map { (k, v) ->
            PremiumMenuItem(
                iconEmoji = "•",
                title = k,
                subtitle = v,
                isInteractive = false
            )
        }
        showPremiumMenuDialog(
            headerIcon = "ℹ️",
            title = getString(R.string.video_details_title),
            subtitle = video.title,
            items = items,
            showHeaderVersionBadge = false
        )
    }
}

internal fun MainActivity.addSelectedToVaultBatch(videos: List<VideoItem>, folders: List<VideoFolder>) {
    if (!PlaybackStore.hasPrivacyPin(this)) {
        Toast.makeText(this, getString(R.string.vault_setup_pin_first), Toast.LENGTH_SHORT).show()
        return
    }
    val folderNames = linkedSetOf<String>()
    folders.forEach { folderNames.add(it.name) }
    videos.forEach { if (it.folderName.isNotBlank()) folderNames.add(it.folderName) }

    val progressDlg = createSimpleProgressDialog(getString(R.string.selection_action_add_vault))
    progressDlg.show()
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            folderNames.forEach { name ->
                PlaybackStore.setFolderLocked(this@addSelectedToVaultBatch, name, true)
                // Also ensure .nomedia marker if folder exists on disk
                val sampleVideo = allDiscoveredVideos.firstOrNull { it.folderName == name && it.path.isNotBlank() }
                sampleVideo?.let { sv ->
                    File(sv.path).parentFile?.let { dir -> VaultFileOperations.ensureNoMediaMarker(dir) }
                }
            }
        }
        progressDlg.dismiss()
        selectionViewModel.exitSelection()
        Toast.makeText(this@addSelectedToVaultBatch, getString(R.string.vault_added_batch_toast, folderNames.size), Toast.LENGTH_SHORT).show()
        applyFolderVisibilityAndRefreshUI()
    }
}

internal fun MainActivity.removeSelectedFromVaultBatch(videos: List<VideoItem>, folders: List<VideoFolder>) {
    val folderNames = linkedSetOf<String>()
    folders.forEach { folderNames.add(it.name) }
    videos.forEach { if (it.folderName.isNotBlank()) folderNames.add(it.folderName) }

    val progressDlg = createSimpleProgressDialog(getString(R.string.vault_action_remove))
    progressDlg.show()
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            folderNames.forEach { name ->
                PlaybackStore.setFolderLocked(this@removeSelectedFromVaultBatch, name, false)
                val sampleVideo = allDiscoveredVideos.firstOrNull { it.folderName == name && it.path.isNotBlank() }
                sampleVideo?.let { sv ->
                    File(sv.path).parentFile?.let { dir ->
                        val nomedia = File(dir, ".nomedia")
                        if (nomedia.exists()) nomedia.delete()
                    }
                }
            }
        }
        progressDlg.dismiss()
        selectionViewModel.exitSelection()
        Toast.makeText(this@removeSelectedFromVaultBatch, getString(R.string.vault_removed_batch_toast, folderNames.size), Toast.LENGTH_SHORT).show()
        applyFolderVisibilityAndRefreshUI()
    }
}

/**
 * Task 2: Batch delete triggering AT MOST ONE system permission dialog on Android 11+ (MediaStore.createDeleteRequest),
 * and zero system dialogs for directly deletable / app-owned Vault files.
 */
internal fun MainActivity.ensureAllFilesAccessThen(onProceed: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        onProceed()
        return
    }
    if (Environment.isExternalStorageManager()) {
        onProceed()
        return
    }
    if (allFilesAccessRefusedThisSession) {
        onProceed()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.all_files_access_title))
        .setMessage(getString(R.string.all_files_access_message))
        .setPositiveButton(getString(R.string.all_files_access_grant_btn)) { _, _ ->
            pendingAllFilesAction = onProceed
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                allFilesAccessLauncher.launch(intent)
            } catch (_: Exception) {
                try {
                    val fallback = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    allFilesAccessLauncher.launch(fallback)
                } catch (_: Exception) {
                    pendingAllFilesAction = null
                    allFilesAccessRefusedThisSession = true
                    onProceed()
                }
            }
        }
        .setNegativeButton(getString(R.string.all_files_access_later_btn)) { _, _ ->
            allFilesAccessRefusedThisSession = true
            onProceed()
        }
        .show()
}

/**
 * FIX 1: Zero repeated delete prompts when All Files Access (MANAGE_EXTERNAL_STORAGE) is granted.
 * Falls back to ONE batched MediaStore.createDeleteRequest if the user refuses All Files Access.
 */
internal fun MainActivity.confirmAndBatchDeleteSelected(
    videos: List<VideoItem>,
    folders: List<VideoFolder>,
    isVault: Boolean
) {
    val allUris = VaultFileOperations.collectUniqueDeleteUris(videos, folders)
    if (allUris.isEmpty()) {
        selectionViewModel.exitSelection()
        return
    }
    val msg = if (isVault) {
        getString(R.string.vault_delete_confirm_msg, allUris.size)
    } else {
        getString(R.string.batch_delete_confirm_msg, allUris.size)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.delete_confirm_title))
        .setMessage(msg)
        .setPositiveButton(getString(R.string.delete_action)) { _, _ ->
            ensureAllFilesAccessThen {
                executeBatchDeleteUris(allUris, videos, folders)
            }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

private fun MainActivity.executeBatchDeleteUris(
    allUris: List<Uri>,
    videos: List<VideoItem>,
    folders: List<VideoFolder>
) {
    val progressDlg = createSimpleProgressDialog(getString(R.string.delete_action))
    progressDlg.show()
    lifecycleScope.launch {
        val remainingForSystemPrompt = mutableListOf<Uri>()
        var deletedDirectly = 0
        val allVideoObjs = linkedMapOf<String, VideoItem>()
        videos.forEach { allVideoObjs[it.uri.toString()] = it }
        folders.forEach { f -> f.videos.forEach { allVideoObjs[it.uri.toString()] = it } }
        val hasAllFilesAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        withContext(Dispatchers.IO) {
            for (uri in allUris) {
                var removed = false
                val path = allVideoObjs[uri.toString()]?.path.orEmpty()

                // 1) With All Files Access (API 30+) or Android 10-, delete directly via File API first (ZERO system dialogs!)
                if (hasAllFilesAccess && path.isNotBlank()) {
                    val f = File(path)
                    if (f.exists() && f.delete()) {
                        removed = true
                        // Clean up stale MediaStore row silently
                        runCatching { contentResolver.delete(uri, null, null) }
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(path), null, null)
                    }
                }

                // 2) Try direct ContentResolver delete
                if (!removed) {
                    try {
                        val rows = contentResolver.delete(uri, null, null)
                        removed = rows > 0
                    } catch (_: SecurityException) {
                        removed = false
                    } catch (_: Exception) {
                        removed = false
                    }
                }

                // 3) Fallback direct File.delete if writable
                if (!removed && path.isNotBlank()) {
                    val f = File(path)
                    if (f.exists() && f.canWrite() && f.delete()) {
                        removed = true
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(path), null, null)
                    }
                }

                if (removed) {
                    deletedDirectly++
                } else {
                    remainingForSystemPrompt.add(uri)
                }
            }
        }
        progressDlg.dismiss()

        if (remainingForSystemPrompt.isEmpty()) {
            selectionViewModel.exitSelection()
            Toast.makeText(this@executeBatchDeleteUris, getString(R.string.batch_delete_done_toast, deletedDirectly), Toast.LENGTH_SHORT).show()
            videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = false)
            return@launch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, remainingForSystemPrompt)
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                batchDeleteRequestLauncher.launch(request)
                return@launch
            } catch (_: Exception) {}
        }

        selectionViewModel.exitSelection()
        Toast.makeText(this@executeBatchDeleteUris, getString(R.string.video_delete_failed_toast), Toast.LENGTH_SHORT).show()
        videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = false)
    }
}

internal fun MainActivity.startBatchCopyOrMoveFlow(
    videos: List<VideoItem>,
    folders: List<VideoFolder>,
    isMove: Boolean,
    isVault: Boolean
) {
    ensureAllFilesAccessThen {
        showDestinationFolderPicker(videos, folders, isMove, isVault)
    }
}

private fun MainActivity.showDestinationFolderPicker(
    videos: List<VideoItem>,
    folders: List<VideoFolder>,
    isMove: Boolean,
    isVault: Boolean
) {
    val candidateDirs = linkedMapOf<String, File>()
    val sourceFolders = if (isVault) vaultFoldersOnly else allDiscoveredFolders
    sourceFolders.forEach { f ->
        val samplePath = f.videos.firstOrNull { it.path.isNotBlank() }?.path.orEmpty()
        if (samplePath.isNotBlank()) {
            File(samplePath).parentFile?.takeIf { it.exists() && it.isDirectory }?.let { dir ->
                candidateDirs[f.name] = dir
            }
        }
    }
    if (!isVault) {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (moviesDir != null) candidateDirs["Movies"] = moviesDir
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null) candidateDirs["Download"] = downloadDir
    } else {
        val privateVaultDir = File(filesDir, "vexo_vault_private").apply {
            if (!exists()) mkdirs()
            VaultFileOperations.ensureNoMediaMarker(this)
        }
        candidateDirs["V Player Vault"] = privateVaultDir
    }

    val dirEntries = candidateDirs.entries.toList()
    if (dirEntries.isEmpty()) {
        Toast.makeText(this, getString(R.string.no_destination_folders), Toast.LENGTH_SHORT).show()
        return
    }

    val titleRes = if (isMove) R.string.selection_pick_dest_move else R.string.selection_pick_dest_copy
    val items = mutableListOf<PremiumMenuItem>()

    // Quick "+ Create new folder" card at top
    val baseParent = if (isVault) {
        File(filesDir, "vexo_vault_private").apply {
            if (!exists()) mkdirs()
            VaultFileOperations.ensureNoMediaMarker(this)
        }
    } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) ?: filesDir
    }
    items.add(
        PremiumMenuItem(
            iconEmoji = "➕",
            title = getString(R.string.selection_new_folder),
            subtitle = baseParent.absolutePath.replace("/storage/emulated/0", "Internal Storage"),
            isAccentHighlighted = true,
            onClick = {
                val input = android.widget.EditText(this).apply {
                    hint = getString(R.string.selection_new_folder_hint)
                    setSingleLine(true)
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.selection_new_folder))
                    .setView(input)
                    .setPositiveButton(getString(R.string.selection_paste_here_btn)) { _, _ ->
                        val name = input.text?.toString().orEmpty().trim().replace("/", "_")
                        if (name.isNotEmpty()) {
                            val newDir = File(baseParent, name).apply { mkdirs() }
                            if (isVault) VaultFileOperations.ensureNoMediaMarker(newDir)
                            prepareAndResolveConflictsBeforeTransfer(videos, folders, newDir, isMove, isVault)
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        )
    )

    dirEntries.forEach { (folderLabel, dirFile) ->
        val friendlyPath = dirFile.absolutePath.replace("/storage/emulated/0", "Internal Storage")
        val emoji = when {
            isVault -> "🛡️"
            folderLabel.equals("Movies", ignoreCase = true) -> "🎬"
            folderLabel.equals("Download", ignoreCase = true) -> "⬇️"
            else -> "📁"
        }
        items.add(
            PremiumMenuItem(
                iconEmoji = emoji,
                title = folderLabel,
                subtitle = friendlyPath,
                statusChip = getString(R.string.selection_paste_here_btn),
                onClick = {
                    // FIX 2: Check for real conflicts FIRST — zero dialog if no conflicts exist!
                    prepareAndResolveConflictsBeforeTransfer(videos, folders, dirFile, isMove, isVault)
                }
            )
        )
    }

    showPremiumMenuDialog(
        headerIcon = if (isMove) "✂️" else "📋",
        title = getString(titleRes),
        subtitle = getString(R.string.selection_paste_sub),
        items = items,
        showHeaderVersionBadge = false
    )
}

private data class PlannedTransferItem(
    val video: VideoItem,
    val fileName: String,
    val isFolderConflict: Boolean = false
)

/**
 * FIX 2: Only shows a conflict dialog when a file/folder with the same name ACTUALLY exists
 * in the destination. Supports per-item resolution + "Apply to all remaining conflicts" checkbox.
 */
private fun MainActivity.prepareAndResolveConflictsBeforeTransfer(
    videos: List<VideoItem>,
    folders: List<VideoFolder>,
    destDir: File,
    isMove: Boolean,
    isVault: Boolean
) {
    val allPlanned = mutableListOf<PlannedTransferItem>()
    val conflicts = mutableListOf<PlannedTransferItem>()

    // Expand videos + folders (guarding against moving a folder into itself!)
    val expandedVideos = mutableListOf<VideoItem>()
    expandedVideos.addAll(videos)
    for (f in folders) {
        val sampleParent = f.videos.firstOrNull { it.path.isNotBlank() }?.path?.let { File(it).parentFile }
        if (sampleParent != null && isMove && VaultFileOperations.isMovingFolderIntoItself(sampleParent, destDir)) {
            continue
        }
        expandedVideos.addAll(f.videos)
    }

    val autoPolicies = mutableMapOf<String, DuplicateResolution>()
    for (v in expandedVideos) {
        val srcFile = if (v.path.isNotBlank()) File(v.path) else null
        val fileName = srcFile?.name?.takeIf { it.isNotBlank() } ?: "${v.title}.mp4"
        val isSameDir = srcFile != null && VaultFileOperations.isSameDirectory(srcFile, destDir)

        if (isSameDir) {
            if (isMove) {
                // Moving to same folder -> do nothing silently (no dialog)
                continue
            } else {
                // Copying to same folder -> auto-create "name (1).ext" (no dialog)
                autoPolicies[v.uri.toString()] = DuplicateResolution.KEEP_BOTH
                allPlanned.add(PlannedTransferItem(v, fileName))
                continue
            }
        }

        val item = PlannedTransferItem(v, fileName)
        allPlanned.add(item)
        if (File(destDir, fileName).exists()) {
            conflicts.add(item)
        }
    }

    if (allPlanned.isEmpty()) {
        selectionViewModel.exitSelection()
        return
    }

    if (conflicts.isEmpty()) {
        // ZERO conflicts -> run silently with progress indicator only, NO dialog!
        executeAtomicCopyOrMoveBatch(allPlanned, autoPolicies, destDir, isMove, isVault)
    } else {
        promptNextConflict(conflicts, 0, allPlanned, autoPolicies, destDir, isMove, isVault)
    }
}

private fun MainActivity.promptNextConflict(
    conflicts: List<PlannedTransferItem>,
    index: Int,
    allPlanned: List<PlannedTransferItem>,
    policies: MutableMap<String, DuplicateResolution>,
    destDir: File,
    isMove: Boolean,
    isVault: Boolean
) {
    if (index >= conflicts.size) {
        executeAtomicCopyOrMoveBatch(allPlanned, policies, destDir, isMove, isVault)
        return
    }
    val current = conflicts[index]
    val remainingCount = conflicts.size - index
    var applyToAll = false

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(8))
        addView(TextView(this@promptNextConflict).apply {
            text = getString(R.string.conflict_dialog_message, current.fileName, destDir.name)
            textSize = 14.5f
        })
        if (remainingCount > 1) {
            addView(android.widget.CheckBox(this@promptNextConflict).apply {
                text = getString(R.string.conflict_apply_all, remainingCount - 1)
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, checked -> applyToAll = checked }
            })
        }
    }

    fun choose(res: DuplicateResolution) {
        if (applyToAll) {
            for (i in index until conflicts.size) {
                policies[conflicts[i].video.uri.toString()] = res
            }
            executeAtomicCopyOrMoveBatch(allPlanned, policies, destDir, isMove, isVault)
        } else {
            policies[current.video.uri.toString()] = res
            promptNextConflict(conflicts, index + 1, allPlanned, policies, destDir, isMove, isVault)
        }
    }

    val replaceLabel = if (current.isFolderConflict) getString(R.string.dup_policy_merge) else getString(R.string.dup_policy_replace)
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.conflict_dialog_title))
        .setView(box)
        .setPositiveButton(replaceLabel) { _, _ -> choose(DuplicateResolution.REPLACE) }
        .setNeutralButton(getString(R.string.dup_policy_keep_both)) { _, _ -> choose(DuplicateResolution.KEEP_BOTH) }
        .setNegativeButton(getString(R.string.dup_policy_skip)) { _, _ -> choose(DuplicateResolution.SKIP) }
        .setCancelable(true)
        .show()
}

private fun MainActivity.executeAtomicCopyOrMoveBatch(
    plannedItems: List<PlannedTransferItem>,
    policies: Map<String, DuplicateResolution>,
    destDir: File,
    isMove: Boolean,
    isVault: Boolean
) {
    val progressDlg = createSimpleProgressDialog(
        if (isMove) getString(R.string.selection_action_move) else getString(R.string.selection_action_copy)
    )
    progressDlg.show()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            if (!destDir.exists()) destDir.mkdirs()
            if (isVault) VaultFileOperations.ensureNoMediaMarker(destDir)

            var ok = 0
            var skipped = 0
            var failed = 0
            val scanPaths = mutableListOf<String>()

            for (item in plannedItems) {
                val v = item.video
                val policy = policies[v.uri.toString()] ?: DuplicateResolution.KEEP_BOTH
                val srcFile = if (v.path.isNotBlank()) File(v.path) else null
                val targetFile = VaultFileOperations.resolveDestinationFile(destDir, item.fileName, policy)
                if (targetFile == null) {
                    skipped++
                    continue
                }
                val success = if (srcFile != null && srcFile.exists() && srcFile.canRead()) {
                    val moved = VaultFileOperations.atomicCopyOrMoveFile(srcFile, targetFile, deleteSourceAfterVerify = isMove)
                    if (moved && isMove) {
                        runCatching { contentResolver.delete(v.uri, null, null) }
                        scanPaths.add(srcFile.absolutePath)
                    }
                    moved
                } else {
                    val copied = VaultFileOperations.copyFromUriToFile(this@executeAtomicCopyOrMoveBatch, v.uri, targetFile)
                    if (copied && isMove) {
                        runCatching { contentResolver.delete(v.uri, null, null) }
                    }
                    copied
                }
                if (success) {
                    ok++
                    if (!isVault) scanPaths.add(targetFile.absolutePath)
                } else {
                    failed++
                }
            }
            if (scanPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    scanPaths.toTypedArray(),
                    null,
                    null
                )
            }
            BatchFileOpResult(succeeded = ok, skipped = skipped, failed = failed)
        }
        progressDlg.dismiss()
        selectionViewModel.exitSelection()
        Toast.makeText(
            this@executeAtomicCopyOrMoveBatch,
            getString(R.string.batch_file_op_result_toast, result.succeeded, result.skipped, result.failed),
            Toast.LENGTH_LONG
        ).show()
        videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false, isUserPullRefresh = false)
    }
}

private fun MainActivity.createSimpleProgressDialog(title: String): AlertDialog {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(ProgressBar(this@createSimpleProgressDialog))
        addView(TextView(this@createSimpleProgressDialog).apply {
            text = getString(R.string.loading_media_label)
            setPadding(dp(16), 0, 0, 0)
            textSize = 15f
        })
    }
    return AlertDialog.Builder(this)
        .setTitle(title)
        .setView(container)
        .setCancelable(false)
        .create()
}

internal fun MainActivity.updateVaultScreenSecurityFlag(inVault: Boolean) {
    if (inVault) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
