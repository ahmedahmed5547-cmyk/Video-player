package com.pureplayer.video

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AudioTabSection {
    ARTISTS, ALBUMS, TRACKS, GENRES, PLAYLISTS
}

enum class AudioTrackSort {
    TITLE_ASC, TITLE_DESC, ARTIST_ASC, DURATION_DESC, DATE_ADDED_DESC
}

data class AudioTrackItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri,
    val genre: String = "Unknown",
    val dateAddedSec: Long = 0L
)

data class AudioArtistGroup(
    val name: String,
    val albumCount: Int,
    val tracks: List<AudioTrackItem>
)

data class AudioAlbumGroup(
    val albumId: Long,
    val title: String,
    val artist: String,
    val albumArtUri: Uri,
    val tracks: List<AudioTrackItem>
)

data class AudioGenreGroup(
    val name: String,
    val tracks: List<AudioTrackItem>
)

data class AudioPlaylistGroup(
    val id: Long,
    val name: String,
    val tracks: List<AudioTrackItem>
)

sealed class AudioLibraryUiState {
    object Loading : AudioLibraryUiState()
    object PermissionDenied : AudioLibraryUiState()
    object Empty : AudioLibraryUiState()
    data class Success(
        val tracks: List<AudioTrackItem>,
        val artists: List<AudioArtistGroup>,
        val albums: List<AudioAlbumGroup>,
        val genres: List<AudioGenreGroup>,
        val playlists: List<AudioPlaylistGroup>
    ) : AudioLibraryUiState()
}

class AudioLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow<AudioLibraryUiState>(AudioLibraryUiState.Loading)
    val uiState: StateFlow<AudioLibraryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var observerJob: Job? = null
    private var isObserverRegistered = false

    private val audioObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            observerJob?.cancel()
            observerJob = viewModelScope.launch {
                delay(500L)
                if (hasAudioPermission()) refreshAudioLibrary()
            }
        }
    }

    fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureObserver() {
        if (isObserverRegistered) return
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                audioObserver
            )
            isObserverRegistered = true
        }
    }

    fun refreshAudioLibrary() {
        if (!hasAudioPermission()) {
            _uiState.value = AudioLibraryUiState.PermissionDenied
            return
        }
        ensureObserver()
        loadJob?.cancel()
        if (_uiState.value !is AudioLibraryUiState.Success) {
            _uiState.value = AudioLibraryUiState.Loading
        }
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { queryAudioLibrary() }
            if (result.tracks.isEmpty() && result.playlists.isEmpty()) {
                _uiState.value = AudioLibraryUiState.Empty
            } else {
                _uiState.value = result
            }
        }
    }

    fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dao = IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
            val now = System.currentTimeMillis()
            dao.insertPlaylist(AudioPlaylistEntity(name = clean, createdMs = now, updatedMs = now))
            refreshAudioLibrary()
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
                .renamePlaylist(playlistId, clean, System.currentTimeMillis())
            refreshAudioLibrary()
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
            dao.deleteTracksInPlaylist(playlistId)
            dao.deletePlaylist(playlistId)
            refreshAudioLibrary()
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: AudioTrackItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
            val nextPos = dao.countTracksInPlaylist(playlistId)
            dao.insertPlaylistTrack(
                AudioPlaylistTrackEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    contentUri = track.uri.toString(),
                    durationMs = track.durationMs,
                    albumArtUri = track.albumArtUri.toString(),
                    position = nextPos
                )
            )
            refreshAudioLibrary()
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
                .deleteTrackFromPlaylist(playlistId, trackId)
            refreshAudioLibrary()
        }
    }

    fun reorderPlaylistTrack(playlistId: Long, orderedTrackIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
            orderedTrackIds.forEachIndexed { idx, trackId ->
                dao.updateTrackPosition(playlistId, trackId, idx)
            }
            refreshAudioLibrary()
        }
    }

    private fun queryAudioLibrary(): AudioLibraryUiState.Success {
        val tracks = mutableListOf<AudioTrackItem>()
        val cr = appContext.contentResolver
        val artworkBaseUri = Uri.parse("content://media/external/audio/albumart")

        val showHidden = PlaybackStore.isShowHiddenFolders(appContext)
        val nomediaCache = HashMap<String, Boolean>()
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA
            )
        }
        runCatching {
            cr.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 5000",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    if (!showHidden) {
                        val filePath = if (dataCol != -1) c.getString(dataCol).orEmpty() else ""
                        val relPath = if (relPathCol != -1) c.getString(relPathCol).orEmpty() else ""
                        val combinedPath = filePath.ifBlank { relPath }
                        val isHiddenAudio = HiddenMediaAndPlaylistHelper.isHiddenMedia(
                            path = combinedPath,
                            folderName = "",
                            title = title,
                            hasNomediaAncestor = filePath.isNotBlank() && HiddenMediaAndPlaylistHelper.hasNomediaInParentChain(filePath, nomediaCache)
                        )
                        if (isHiddenAudio) continue
                    }
                    val artist = (if (artistCol != -1) c.getString(artistCol) else null)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                    val album = (if (albumCol != -1) c.getString(albumCol) else null)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
                    val albumId = if (albumIdCol != -1) c.getLong(albumIdCol) else 0L
                    val dur = if (durCol != -1) c.getLong(durCol) else 0L
                    val dateSec = if (dateCol != -1) c.getLong(dateCol) else 0L
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val artUri = ContentUris.withAppendedId(artworkBaseUri, albumId)
                    tracks.add(
                        AudioTrackItem(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = albumId,
                            durationMs = dur,
                            uri = uri,
                            albumArtUri = artUri,
                            genre = "Music",
                            dateAddedSec = dateSec
                        )
                    )
                }
            }
        }

        val artists = tracks.groupBy { it.artist }.map { (artistName, list) ->
            AudioArtistGroup(
                name = artistName,
                albumCount = list.map { it.albumId }.distinct().size,
                tracks = list
            )
        }.sortedBy { it.name.lowercase() }

        val albums = tracks.groupBy { it.albumId to it.album }.map { (key, list) ->
            val first = list.first()
            AudioAlbumGroup(
                albumId = key.first,
                title = key.second,
                artist = first.artist,
                albumArtUri = first.albumArtUri,
                tracks = list
            )
        }.sortedBy { it.title.lowercase() }

        val genres = tracks.groupBy { it.genre }.map { (gName, list) ->
            AudioGenreGroup(gName, list)
        }.sortedBy { it.name.lowercase() }

        val playlists = runCatching {
            val dao = IptvRoomDatabase.getInstance(appContext).audioPlaylistDao()
            dao.getAllPlaylists().map { pl ->
                val plTracks = dao.getTracksForPlaylist(pl.id).map { t ->
                    AudioTrackItem(
                        id = t.trackId,
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        albumId = 0L,
                        durationMs = t.durationMs,
                        uri = Uri.parse(t.contentUri),
                        albumArtUri = Uri.parse(t.albumArtUri)
                    )
                }
                AudioPlaylistGroup(pl.id, pl.name, plTracks)
            }
        }.getOrDefault(emptyList())

        return AudioLibraryUiState.Success(tracks, artists, albums, genres, playlists)
    }

    override fun onCleared() {
        loadJob?.cancel()
        observerJob?.cancel()
        if (isObserverRegistered) {
            runCatching { appContext.contentResolver.unregisterContentObserver(audioObserver) }
            isObserverRegistered = false
        }
        super.onCleared()
    }
}
