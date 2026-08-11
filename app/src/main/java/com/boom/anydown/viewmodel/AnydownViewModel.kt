package com.boom.anydown.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boom.anydown.model.*
import com.boom.anydown.service.DownloadQueue
import com.boom.anydown.util.*
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AnydownViewModel(application: Application) : AndroidViewModel(application) {
    var homeState by mutableStateOf<HomeUiState>(HomeUiState.Idle())
        private set

    /**
     * The download list now lives in the background service's queue, not in the
     * ViewModel — the UI just observes it, so downloads keep running when this
     * screen (or the whole app) goes away.
     */
    val downloads: StateFlow<List<DownloadedItem>> = DownloadQueue.items

    /**
     * Playlist selections, keyed by format id ("full" | "audio" | "fast").
     * Each section keeps its own independent set of chosen video ids.
     */
    var playlistSelections by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    private var loadingJob: Job? = null

    init {
        DownloadQueue.ensureLoaded(application)
    }

    fun onLinkChanged(text: String) {
        val idle = homeState as? HomeUiState.Idle ?: return
        homeState = idle.copy(linkInput = text)
    }
    fun onClipboardLinkDetected(link: String) {
        val idle = homeState as? HomeUiState.Idle ?: return
        if (idle.linkInput.isBlank()) homeState = idle.copy(clipboardSuggestion = link)
    }
    fun acceptClipboardSuggestion() {
        val idle = homeState as? HomeUiState.Idle ?: return
        val link = idle.clipboardSuggestion ?: return
        homeState = idle.copy(linkInput = link, clipboardSuggestion = null)
    }
    fun dismissClipboardSuggestion() {
        val idle = homeState as? HomeUiState.Idle ?: return
        homeState = idle.copy(clipboardSuggestion = null)
    }

    fun fetchVideo() {
        val idle = homeState as? HomeUiState.Idle ?: return
        if (idle.linkInput.isBlank() || idle.isLoading) return

        homeState = idle.copy(isLoading = true, loadingStatusText = "Waking up Python engine…")
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val py = Python.getInstance()
                val downloader = py.getModule("downloader")
                val isPlaylist = downloader.callAttr("is_playlist", idle.linkInput).toBoolean()

                if (isPlaylist) {
                    val res = downloader.callAttr("fetch_playlist_info", idle.linkInput).asMap()
                    val entries = res[PyObject.fromJava("entries")]!!.asList().map { e ->
                        val m = e.asMap()
                        PlaylistEntry(
                            id = m[PyObject.fromJava("id")].toString(),
                            url = m[PyObject.fromJava("url")].toString(),
                            title = m[PyObject.fromJava("title")].toString(),
                            thumbnailUrl = m[PyObject.fromJava("thumbnailUrl")].toString(),
                            durationText = m[PyObject.fromJava("durationText")].toString()
                        )
                    }
                    val playlist = PlaylistResult(
                        sourceUrl = idle.linkInput,
                        title = res[PyObject.fromJava("title")].toString(),
                        entries = entries,
                        formats = defaultFormats()
                    )
                    withContext(Dispatchers.Main) {
                        playlistSelections = emptyMap()
                        homeState = HomeUiState.Playlist(playlist)
                    }
                    return@launch
                }

                // Single video — unchanged from before.
                val res = downloader.callAttr("fetch_video_info", idle.linkInput).asMap()
                val formatsRaw = res[PyObject.fromJava("formats")]!!.asList()
                val formats = formatsRaw.map { f ->
                    val m = f.asMap()
                    DownloadFormat(
                        id = m[PyObject.fromJava("id")].toString(),
                        label = m[PyObject.fromJava("label")].toString(),
                        subtitle = m[PyObject.fromJava("subtitle")].toString(),
                        sizeText = m[PyObject.fromJava("sizeText")].toString()
                    )
                }
                val video = VideoResult(
                    sourceUrl = idle.linkInput,
                    title = res[PyObject.fromJava("title")].toString(),
                    thumbnailUrl = res[PyObject.fromJava("thumbnailUrl")].toString(),
                    durationText = res[PyObject.fromJava("durationText")].toString(),
                    formats = formats
                )
                withContext(Dispatchers.Main) { homeState = HomeUiState.Result(video) }
            } catch (e: PyException) {
                CrashLogger.log("PYTHON ERROR (fetchVideo): ${e.message}")
                withContext(Dispatchers.Main) { homeState = HomeUiState.Idle(linkInput = idle.linkInput) }
            }
        }
    }

    /** Single-video download — now just a batch of one on the background queue. */
    fun onFormatSelected(format: DownloadFormat, video: VideoResult, context: Context) {
        DownloadQueue.enqueue(
            context,
            listOf(
                DownloadRequest(
                    id = UUID.randomUUID().toString(),
                    url = video.sourceUrl,
                    title = video.title,
                    thumbnailUrl = video.thumbnailUrl,
                    formatId = format.id
                )
            )
        )
    }

    // --- playlist selection ------------------------------------------------

    fun selectedIds(formatId: String): Set<String> = playlistSelections[formatId].orEmpty()

    fun selectedCount(formatId: String): Int = selectedIds(formatId).size

    fun toggleSelection(formatId: String, entryId: String) {
        val current = selectedIds(formatId)
        val next = if (current.contains(entryId)) current - entryId else current + entryId
        playlistSelections = playlistSelections + (formatId to next)
    }

    fun setSelection(formatId: String, ids: Set<String>) {
        playlistSelections = playlistSelections + (formatId to ids)
    }

    fun totalSelected(): Int = playlistSelections.values.sumOf { it.size }

    /**
     * Gathers every selection across all three sections — each in its own
     * quality — and hands the whole batch to the background queue.
     */
    fun downloadSelectedPlaylistItems(context: Context) {
        val state = homeState as? HomeUiState.Playlist ?: return
        val byId = state.playlist.entries.associateBy { it.id }

        val requests = mutableListOf<DownloadRequest>()
        state.playlist.formats.forEach { format ->
            selectedIds(format.id).forEach { entryId ->
                val entry = byId[entryId] ?: return@forEach
                requests.add(
                    DownloadRequest(
                        id = UUID.randomUUID().toString(),
                        url = entry.url,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        formatId = format.id
                    )
                )
            }
        }
        if (requests.isEmpty()) return
        DownloadQueue.enqueue(context, requests)
        playlistSelections = emptyMap()
    }

    // --- downloads list actions -------------------------------------------

    fun cancelDownload(id: String) {
        DownloadQueue.cancel(getApplication(), id)
    }

    fun deleteDownload(id: String, context: Context) {
        DownloadQueue.remove(context, id)
    }

    fun grabAnother() {
        loadingJob?.cancel()
        playlistSelections = emptyMap()
        homeState = HomeUiState.Idle()
    }

    private fun defaultFormats() = listOf(
        DownloadFormat("full", "Video + Audio (Best Quality)", "Best available · MP4", "Size depends on quality"),
        DownloadFormat("audio", "Audio Only", "M4A", "5-10 MB each"),
        DownloadFormat("fast", "Fast Download", "720p · MP4", "<50 MB each")
    )
}
