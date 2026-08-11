package com.boom.anydown.model

data class DownloadFormat(
    val id: String,
    val label: String,
    val subtitle: String,
    val sizeText: String = "Size unknown",
    val approxSizeMb: Int? = null
)

data class VideoResult(
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String,
    val durationText: String,
    val formats: List<DownloadFormat>
)

/** One video inside a playlist, from the fast/flat extraction. */
data class PlaylistEntry(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String,
    val durationText: String
)

data class PlaylistResult(
    val sourceUrl: String,
    val title: String,
    val entries: List<PlaylistEntry>,
    val formats: List<DownloadFormat>
)

enum class DownloadStatus { QUEUED, DOWNLOADING, PROCESSING, COMPLETED, CANCELLED, FAILED }

data class DownloadedItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val sizeMb: Int,
    val filePath: String,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val progress: Int = 0
)

/**
 * A single unit of work handed to the background download service.
 * `formatId` is one of "full" | "audio" | "fast" — the same ids downloader.py accepts.
 */
data class DownloadRequest(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String,
    val formatId: String
)

sealed class HomeUiState {
    data class Idle(
        val linkInput: String = "",
        val clipboardSuggestion: String? = null,
        val isLoading: Boolean = false,
        val loadingStatusText: String = ""
    ) : HomeUiState()
    data class Result(val video: VideoResult) : HomeUiState()
    data class Playlist(val playlist: PlaylistResult) : HomeUiState()
}
