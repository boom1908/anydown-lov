# Anydown: background download service + YouTube playlist support

Scope covers Feature 1 (foreground download service), Feature 2 (YouTube playlist selection flow), and the keystore housekeeping. The Spotify features (3 and 4) are not part of this plan.

Important caveat: this workspace is a web project with no Android SDK, Gradle, or emulator. The Kotlin/Gradle files will be written as source only — they cannot be compiled, linted, or run here. Verification happens through the existing `.github/workflows/build-debug.yml` GitHub Actions debug build, which stays untouched.

## Step 0 — Import the project

Extract `anydown-native-master.zip` into the repository root (excluding any `.git` metadata and the `.idea/` folder). The 15 MB ffmpeg `.so` files and the `.jks` keystore come along as-is since the Actions build needs them.

## Feature 1 — Background download service

Downloads currently run in `viewModelScope` inside `AnydownViewModel`, so they die when the app is backgrounded. They move into a foreground service that owns the queue.

New `DownloadService` (started foreground service, also bindable):

- Holds a serial queue (`Channel` consumed by a single coroutine on `Dispatchers.IO`) so exactly one item downloads at a time.
- Each queue item carries: id, source URL, title, thumbnail, and format id (`full` / `audio` / `fast`) — the same ids `downloader.py` already accepts, so the Python layer needs no changes.
- Calls the existing `downloader.fetch_video(...)` through Chaquopy with the same `ProgressCallback` bridge used today.
- Exposes a `StateFlow<QueueState>` (current item, per-item progress, position in queue, total, full item list) from a singleton object so the ViewModel can collect it without tight coupling to binding lifecycle.
- On failure: log via `CrashLogger`, mark that item `FAILED` in history, continue to the next item.
- On queue drain: post the final "All downloads complete" notification state and call `stopSelf()`.

Notification:

- Dedicated low-importance channel `anydown_downloads`, created in `AnydownApp.onCreate`.
- Ongoing notification showing `Downloading: {title}` with `item N of M` and a determinate progress bar for the current item; throttled to roughly one update per 500 ms so the notification isn't spammed.
- Tapping it opens `MainActivity`.

Manifest changes:

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `POST_NOTIFICATIONS` permissions.
- `<service android:name=".service.DownloadService" android:foregroundServiceType="dataSync" android:exported="false" />`.

On foreground service type: Android 14+ requires a declared type, and `dataSync` is the correct one for app-initiated file transfer to storage. `mediaProcessing` is scoped to transcoding/processing of media already on the device, not network downloads. `dataSync` on Android 15+ carries a 6-hour-per-day cumulative runtime cap, which is comfortably above any realistic download queue; the service must still handle `onTimeout` by stopping cleanly and marking in-flight items failed.

Runtime notification permission: on Android 13+ `MainActivity` requests `POST_NOTIFICATIONS` on first launch. If denied, downloads still run — only the notification is missing.

ViewModel changes:

- `onFormatSelected` no longer launches a download job. It creates the `DownloadedItem` history entry and enqueues a single-item batch into the service — the single-video path becomes a queue of one, exactly as requested.
- `downloads` list is driven by the service's state flow plus `HistoryManager`, keeping the Downloads screen visually identical.
- Cancel stays supported: cancelling the active item trips the existing `isCancelled` callback; cancelling a pending item just removes it from the queue.

## Feature 2 — YouTube playlist support

Python (`downloader.py`), additive only:

- `is_playlist(url)` — true when the URL has a `list=` parameter and is not a single-video watch page without one.
- `fetch_playlist_info(url)` — yt-dlp with `extract_flat: 'in_playlist'` for a fast, shallow fetch returning playlist title plus a list of entries (video id, url, title, thumbnail, duration text). No per-video deep extraction.

Models:

- `PlaylistEntry(id, url, title, thumbnailUrl, durationText)`
- `PlaylistResult(sourceUrl, title, entries)`
- `HomeUiState.PlaylistResult(playlist)` added alongside the existing `Result`.

Flow:

- `fetchVideo()` branches on `is_playlist`. Single-video links take the existing path unchanged.
- Playlist links land on the same `ResultsScreen`, but the three format cards become tappable sections instead of instant-download buttons. Each shows a count badge, e.g. `Audio Only (5 selected)`.
- New `PlaylistSelectionScreen`: checklist of every entry with thumbnail, title, duration, and a checkbox; a `Select All` header button that flips to `Deselect All` once everything is ticked; a `Select` confirm button returning to results.
- Selection state lives in the ViewModel as `Map<formatId, Set<entryId>>`, so the three sections are independent and preserved across navigation.
- A `Download Selected` button at the bottom of the playlist results screen gathers every selection across all three sections into one batch, fires the existing aura animation, and enqueues the batch into the Feature 1 service.

All new UI reuses `BrutalistModifiers`, `Colors`, and `Type` so the neo-brutalist terminal look is unchanged.

## Housekeeping — signing credentials

- Add `keystore.properties` (git-ignored) holding `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- `app/build.gradle` reads it via a `Properties` load and only registers the `release` signing config when the file exists, so the debug CI build keeps working without it.
- Add `keystore.properties` to `.gitignore`. Also flag: `app/anydown-release.jks` and the password `anydown2026` are already in the uploaded repo's history — after this change, that key should be treated as compromised and a new one generated.

## Dependencies

Only `androidx.core:core-ktx` (for `NotificationCompat`) and `androidx.lifecycle:lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`) are added to `app/build.gradle`. No WorkManager and no HTTP client are needed — the service model covers Feature 1, and Feature 2 goes through the existing Python layer.
