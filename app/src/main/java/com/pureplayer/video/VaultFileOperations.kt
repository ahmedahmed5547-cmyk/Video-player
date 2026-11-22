package com.pureplayer.video

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32

enum class DuplicateResolution {
    SKIP,
    REPLACE,
    KEEP_BOTH
}

data class BatchFileOpResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val errorMessage: String? = null
)

/**
 * Atomic file operations engine for Copy, Move, and Vault transfers.
 * Guarantees no data loss if killed mid-operation: writes to .vexo_tmp -> verifies byte length + CRC32 -> renames -> deletes source.
 */
object VaultFileOperations {

    fun isMovingFolderIntoItself(sourceFolder: File, destDir: File): Boolean {
        return try {
            val srcCanon = sourceFolder.canonicalPath.trimEnd(File.separatorChar)
            val dstCanon = destDir.canonicalPath.trimEnd(File.separatorChar)
            dstCanon == srcCanon || dstCanon.startsWith(srcCanon + File.separatorChar)
        } catch (_: Exception) {
            false
        }
    }

    fun resolveDestinationFile(destDir: File, desiredName: String, policy: DuplicateResolution): File? {
        val candidate = File(destDir, desiredName)
        if (!candidate.exists()) return candidate
        return when (policy) {
            DuplicateResolution.SKIP -> null
            DuplicateResolution.REPLACE -> candidate
            DuplicateResolution.KEEP_BOTH -> {
                val dotIdx = desiredName.lastIndexOf('.')
                val base = if (dotIdx > 0) desiredName.substring(0, dotIdx) else desiredName
                val ext = if (dotIdx > 0) desiredName.substring(dotIdx) else ""
                var counter = 1
                var unique = File(destDir, "${base}_($counter)$ext")
                while (unique.exists()) {
                    counter++
                    unique = File(destDir, "${base}_($counter)$ext")
                }
                unique
            }
        }
    }

    fun computeCrc32(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var read = fis.read(buf)
            while (read > 0) {
                crc.update(buf, 0, read)
                read = fis.read(buf)
            }
        }
        return crc.value
    }

    /**
     * Atomically copies [sourceFile] into [targetFile] via a temporary file, verifying both byte length
     * and CRC32 checksum before committing. If [deleteSourceAfterVerify] is true, removes [sourceFile]
     * ONLY after the destination passes verification.
     */
    fun atomicCopyOrMoveFile(
        sourceFile: File,
        targetFile: File,
        deleteSourceAfterVerify: Boolean
    ): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile) return false
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists()) parent.mkdirs()

        // Check available space before writing
        val neededBytes = sourceFile.length() + (1024L * 1024L)
        if (parent.usableSpace in 1L until neededBytes) {
            return false
        }

        val tmpFile = File(parent, ".vexo_tmp_${System.nanoTime()}_${targetFile.name}")
        return try {
            val srcCrc = CRC32()
            val buf = ByteArray(64 * 1024)
            FileInputStream(sourceFile).use { fis ->
                FileOutputStream(tmpFile).use { fos ->
                    var n = fis.read(buf)
                    while (n > 0) {
                        srcCrc.update(buf, 0, n)
                        fos.write(buf, 0, n)
                        n = fis.read(buf)
                    }
                    fos.fd.sync()
                }
            }
            if (tmpFile.length() != sourceFile.length()) {
                tmpFile.delete()
                return false
            }
            val dstCrcVal = computeCrc32(tmpFile)
            if (dstCrcVal != srcCrc.value) {
                tmpFile.delete()
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            val renamed = tmpFile.renameTo(targetFile)
            if (!renamed) {
                tmpFile.delete()
                return false
            }
            if (deleteSourceAfterVerify) {
                sourceFile.delete()
            }
            true
        } catch (_: Exception) {
            tmpFile.delete()
            false
        }
    }

    /**
     * Ensures a .nomedia marker exists in [dir] so hidden Vault files never appear in Gallery or MediaStore.
     */
    fun ensureNoMediaMarker(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        } catch (_: Exception) {}
    }

    /**
     * Collects deduplicated MediaStore URIs for a mixed batch of selected videos and folders
     * so Android 11+ MediaStore.createDeleteRequest triggers AT MOST ONE system permission dialog.
     */
    fun collectUniqueDeleteUris(
        selectedVideos: List<VideoItem>,
        selectedFolders: List<VideoFolder>
    ): List<Uri> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<Uri>()
        fun addVideo(v: VideoItem) {
            if (seen.add(v.uri.toString())) {
                result.add(v.uri)
            }
        }
        selectedVideos.forEach { addVideo(it) }
        selectedFolders.forEach { f -> f.videos.forEach { addVideo(it) } }
        return result
    }

    fun copyFromUriToFile(context: Context, uri: Uri, targetFile: File): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists()) parent.mkdirs()
        val tmpFile = File(parent, ".vexo_tmp_${System.nanoTime()}_${targetFile.name}")
        return try {
            var totalBytes = 0L
            val buf = ByteArray(64 * 1024)
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmpFile).use { fos ->
                    var n = ins.read(buf)
                    while (n > 0) {
                        fos.write(buf, 0, n)
                        totalBytes += n
                        n = ins.read(buf)
                    }
                    fos.fd.sync()
                }
            } ?: return false
            if (totalBytes <= 0L) {
                tmpFile.delete()
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.delete()
                return false
            }
            true
        } catch (_: Exception) {
            tmpFile.delete()
            false
        }
    }
}
