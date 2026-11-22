package com.pureplayer.video

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SelectionState(
    val isActive: Boolean = false,
    val isVaultScope: Boolean = false,
    val selectedKeys: Set<String> = emptySet()
) {
    val count: Int get() = selectedKeys.size
}

/**
 * Rotation-safe ViewModel holding multi-selection state for both the main Video library
 * and the Private Vault. Keys use stable prefixes: "v:<uri>" for VideoItem, "f:<folderName>" for VideoFolder.
 */
class SelectionViewModel : ViewModel() {

    companion object {
        fun keyForVideo(video: VideoItem): String = "v:${video.uri}"
        fun keyForFolder(folder: VideoFolder): String = "f:${folder.name}"
        fun isVideoKey(key: String): Boolean = key.startsWith("v:")
        fun isFolderKey(key: String): Boolean = key.startsWith("f:")
    }

    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    fun isSelectionActive(): Boolean = _state.value.isActive

    fun isSelected(key: String): Boolean = _state.value.selectedKeys.contains(key)

    fun enterSelectionWith(key: String, isVault: Boolean) {
        _state.value = SelectionState(
            isActive = true,
            isVaultScope = isVault,
            selectedKeys = setOf(key)
        )
    }

    fun toggleKey(key: String, isVault: Boolean) {
        val cur = _state.value
        if (!cur.isActive) {
            enterSelectionWith(key, isVault)
            return
        }
        val next = cur.selectedKeys.toMutableSet()
        if (!next.add(key)) {
            next.remove(key)
        }
        if (next.isEmpty()) {
            exitSelection()
        } else {
            _state.value = cur.copy(selectedKeys = next)
        }
    }

    fun selectAll(allKeys: Collection<String>, isVault: Boolean) {
        if (allKeys.isEmpty()) return
        val cur = _state.value
        val targetSet = allKeys.toSet()
        if (cur.isActive && cur.selectedKeys.size == targetSet.size && cur.selectedKeys.containsAll(targetSet)) {
            exitSelection()
        } else {
            _state.value = SelectionState(
                isActive = true,
                isVaultScope = isVault,
                selectedKeys = targetSet
            )
        }
    }

    fun exitSelection() {
        _state.value = SelectionState()
    }
}
