# Stream Miner

I'm importing an existing native Android app called Anydown (a YouTube/video downloader built with Kotlin, Jetpack Compose, and a Python engine running inside the app via Chaquopy, using the yt-dlp library). I need you to add several new features to it. Please read through the existing code structure first before making changes, and preserve the existing visual style (a "neo-brutalist" terminal/console look — bold borders, monospace fonts, flat colors).

Existing structure (for context)

app/src/main/java/com/boom/anydown/model/Models.kt — data models (DownloadFormat, VideoResult, DownloadedItem, DownloadStatus, HomeUiState)

app/src/main/java/com/boom/anydown/viewmodel/AnydownViewModel.kt — main app logic, currently runs downloads inside the ViewModel's coroutine scope

app/src/main/java/com/boom/anydown/ui/ResultsScreen.kt — shows video info + 3 format cards (Video+Audio / Audio Only / Fast Download)

app/src/main/java/com/boom/anydown/ui/downloads/DownloadsScreen.kt — shows download history/progress list

app/src/main/python/downloader.py — Python code using yt-dlp to fetch video info and download

app/src/main/java/com/boom/anydown/util/HistoryManager.kt — saves/loads download history to disk

No background service currently exists — all downloads stop if the app is closed or backgrounded for too long, because they run in viewModelScope, which is tied to the screen's lifecycle.

Feature 1: Background Download Service (applies to ALL downloads — single videos, playlists, everything)

Replace the current in-ViewModel download logic with a proper Android Foreground Service so downloads survive the app being backgrounded, minimized, or the screen being locked.

Requirements:

Create a DownloadService (foreground service) that owns the actual download queue and calls into the existing Python downloader.py module.

Show a persistent, live-updating notification while downloads are active — e.g. "Downloading: {title} — item 3 of 12", with the overall progress percentage of the current item.

The notification should update as each item completes and disappear (or show "All downloads complete") when the queue is empty.

The queue processes one download at a time, sequentially — not in parallel. This is intentional, for battery/network stability on mobile.

If a download fails, log it, mark that item FAILED in history, and continue to the next item in the queue rather than stopping the whole queue.

The UI (ViewModel + screens) should observe the service's state (e.g. via a bound service, LiveData/StateFlow, or broadcast) rather than driving the download logic directly.

Add the required FOREGROUND_SERVICE permission and notification channel setup to AndroidManifest.xml. Target Android's foreground service type appropriately (dataSync or mediaProcessing, whichever is most correct for a file download use case per current Android requirements — please check current Android foreground service type rules since these have gotten stricter in recent Android versions).

Existing single-video downloads should be migrated to use this same service/queue (queue of 1), not kept as a separate code path.

Feature 2: YouTube Playlist Support

When a user pastes a YouTube playlist URL (as opposed to a single video URL):

Detect that it's a playlist link and fetch the list of videos in it (use a fast/flat extraction — just titles, thumbnails, durations — not deep info per video, to keep it quick).

Reuse the existing results screen (the one with the 3 format cards: "Video + Audio", "Audio Only", "Fast Download") — but for a playlist, each of those 3 cards becomes a section you tap into rather than an immediate download button.

Tapping a section opens a checklist screen: every video in the playlist listed with a thumbnail, title, duration, and a checkbox.

At the top of this checklist screen, add a "Select All" button (and ideally it toggles to "Deselect All" once all are selected).

After selecting videos in that section, a "Select" / confirm button returns the user to the results screen.

The user can repeat this for the other 2 sections too — e.g. some videos selected as Audio Only, others as Fast Download, others as full quality — selections across sections should be independent and preserved when switching between sections.

Each section on the results screen should show a small badge/count of how many videos are currently selected in it, e.g. "Audio Only (5 selected)".

At the bottom of the results screen, add a "Download Selected" button that gathers every selected video (across all 3 sections, in their respective qualities) and sends the whole batch into the background download queue from Feature 1. Trigger the existing "aura" animation when this kicks off.

If it's a single-video link (not a playlist), everything should behave exactly as it does today — no change to that flow.

Feature 3: Spotify Single Track Support

When a user pastes a Spotify track URL (a link containing open.spotify.com/track/):

Detect it's a Spotify track link.

Fetch the track's title using Spotify's public oEmbed endpoint: https://open.spotify.com/oembed?url={track_url} — this is a free, public JSON endpoint that requires no API key, no developer account, and no login of any kind. It returns a title field and a thumbnail_url. Do not use the official Spotify Web API / Developer Dashboard for this — it's unnecessary here and (as of 2026) requires a Premium account just to register an app, which we want to avoid entirely for this feature.

The oEmbed response only gives the track title, not the artist separately, so also fetch the public track page HTML (https://open.spotify.com/track/{id}) and pull the artist name out of its <meta> tags (e.g. og:description or similar — inspect the actual page response to find the right tag, since Spotify doesn't formally document this part). If artist extraction fails for some reason, fall back to searching YouTube using just the track title — still usually finds the right song.

Use the title (+ artist if found) to run an automatic YouTube search and pick the best matching video result.

Show a confirmation card: the matched YouTube video's thumbnail, title, and channel, with a "Proceed" button (not an automatic download — the user should be able to see and confirm the match first).

Tapping "Proceed" should send that matched YouTube video straight into the app's normal single-video download flow — but skip the 3-option results screen entirely and go straight to downloading Audio Only (since the whole point of a Spotify link is getting the song's audio). It should still go into the Feature 1 background queue like everything else.

No API keys, config files, or credentials are needed anywhere for this feature — keep it fully self-contained.

Feature 4: Spotify Playlist / Album Support

When a user pastes a Spotify playlist or album URL (open.spotify.com/playlist/ or open.spotify.com/album/):

Detect it and show a popup/dialog instead of attempting an automatic download.

The popup should have:

A heading like "Spotify Playlist Detected 🎵"

Body text explaining, in plain language: Anydown can match individual tracks automatically, but across a whole playlist a few tracks can occasionally come back wrong (remix, live version, cover version). For accurate results, they should convert the playlist to a YouTube playlist first using a free tool, so they can review and fix any wrong matches before downloading.

A button that opens https://www.tunemymusic.com/ in the browser

A note that once they have the converted YouTube playlist link, they can paste it back into Anydown and it'll work like Feature 2.

This popup has only the "Convert Playlist" button — no download or "Proceed" option here. Reading a full playlist's track list would require the official Spotify Web API (which needs a Premium developer account to set up), and we're deliberately not using that anywhere in this app. So Spotify playlists always route through this popup and nowhere else.

Housekeeping / please also do this

Move the release signing credentials out of app/build.gradle (currently hardcoded as plain text: store/key password anydown2026) into a local keystore.properties file that is added to .gitignore, with the gradle file reading from it instead. This avoids committing secrets to the repo.

Do not modify or remove the existing .github/workflows/build-debug.yml file — it's already set up to build a debug APK via GitHub Actions on every push and upload it as a downloadable artifact. Leave it working.

Please don't change the existing single-video download flow, the visual theme/style, or the overall navigation structure beyond what's needed for these features.

If any part of this requires a new Gradle dependency (e.g. for notifications, WorkManager, or an HTTP client for the Spotify API), please add it properly to app/build.gradle rather than reinventing it

This project was built with [Lovable](https://lovable.dev).

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/299bcb63-e609-401c-863e-9fbcfed41ce1).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```
