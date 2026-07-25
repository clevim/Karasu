# KOReader shelf sync

Karasu pushes chapters as CBZ files to a self-hosted container ("the shelf") and reads back which
of them were finished on the e-reader. Inspired by [KoInsight](https://github.com/georgesg/koinsight),
which does the same round trip for reading statistics and ships its KOReader plugin as a ZIP
downloadable from its own web UI.

This document is the contract. The Karasu side is implemented; **the container and the KOReader
plugin live in a separate repository** and only have to satisfy what is written here.

## Shape of the system

```
┌──────────┐   CBZ + metadata (POST)   ┌───────────┐   plugin downloads CBZ   ┌──────────┐
│  Karasu  │ ────────────────────────► │   shelf   │ ───────────────────────► │ KOReader │
│ (Android)│ ◄──────────────────────── │(container)│ ◄─────────────────────── │ (plugin) │
└──────────┘   read state (GET)        └───────────┘   "finished" is reported  └──────────┘
```

Karasu never talks to KOReader directly, and the plugin never talks to Karasu. The shelf is the
only shared state, which is what lets the tablet be offline whenever Karasu happens to sync.

## What Karasu decides

All of this is Karasu-side and the container does not need to know about it:

- **Which manga.** Everything in the categories chosen under *Settings → KOReader*. Categories were
  reused instead of a per-manga flag so this needs no database column and matches how "download new
  chapters" already scopes itself.
- **Which chapters.** The next *N* unread chapters of each manga, lowest chapter number first.
- **How much.** At most *M* manga, ordered by most recently updated, times *N* chapters each.
- **Where the file comes from.** The already-downloaded `.cbz` in the download directory. Karasu
  does not build a second archive: a chapter that is not downloaded, or was downloaded as loose
  images, is queued with the downloader and picked up on the next sync. This means
  **"Save chapters as CBZ" must be enabled** for the feature to send anything.

A sync run is a reconcile, not a queue. Karasu asks the shelf what it holds, uploads what is
missing, and deletes what is no longer wanted. Read state is pulled *before* the upload pass, so a
chapter finished on the device frees its slot in the same run.

## HTTP contract

Base URL is whatever the user typed, trailing slash tolerated. Every request carries
`Authorization: Bearer <api key>` when a key is configured; the container may accept unauthenticated
requests if the user leaves the key blank.

### `GET /api/health`

Liveness check for the "Test connection" button. Any `2xx` counts as reachable; the body is shown
to the user as-is (first 200 characters), so returning something like
`{"name":"shelf","version":"1"}` is useful but not required.

### `GET /api/shelf`

Everything the shelf currently holds.

```json
{
  "entries": [
    { "chapterId": 4821, "chapterUrl": "/manga/one-piece/chapter-1090", "read": true },
    { "chapterId": 4822, "chapterUrl": "/manga/one-piece/chapter-1091", "read": false }
  ]
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `chapterId` | int64 | Karasu's own chapter row id, echoed back exactly as uploaded. The shelf is keyed by this. |
| `chapterUrl` | string | Echoed back exactly as uploaded. See *Identity* below. |
| `read` | bool | `true` once the plugin reports the chapter finished. |

Unknown fields are ignored, so the container is free to return extra data (page counts, timestamps,
progress percentages) for its own UI.

### `POST /api/shelf`

`multipart/form-data` with exactly two parts:

- `metadata` — JSON:

  ```json
  {
    "chapterId": 4821,
    "chapterUrl": "/manga/one-piece/chapter-1090",
    "mangaTitle": "One Piece",
    "chapterName": "Chapter 1090",
    "chapterNumber": 1090.0,
    "sourceId": 2499283573021220255
  }
  ```

- `file` — the CBZ, `Content-Type: application/vnd.comicbook+zip`, streamed. Chapters are routinely
  tens of megabytes; the container should not assume it fits in memory.

Uploading a `chapterId` that already exists should replace it rather than error.

### `DELETE /api/shelf/{chapterId}`

Removes one entry and its file. Called when a chapter drops out of the wanted set — read, or pushed
out by the manga/chapter limits. Deleting something that is not there should succeed.

## Identity, and why `chapterUrl` is in the payload

The shelf is keyed by `chapterId`, which is a local SQLite row id. That id is **not stable across a
backup restore** — restoring renumbers chapters, so id 4821 can come back meaning a completely
different chapter.

So `chapterUrl` travels with every entry purely as a check. When Karasu pulls read state it looks up
the chapter by id and **only marks it read if the stored url matches what the shelf sent back**. A
mismatch is logged and skipped. Without that guard, a restore followed by a sync would silently mark
unrelated chapters as read.

Practical consequence for the container: **echo `chapterUrl` back byte for byte**. Do not normalise
it, resolve it, or make it absolute.

## What the plugin has to do

Not implemented here, but this is what the Karasu side assumes:

1. List and download CBZ files from the shelf (endpoints for that are the container's own business —
   Karasu never calls them).
2. Detect that a book was finished, and tell the shelf, so that the entry's `read` flips to `true`.
3. Let the user set the shelf URL, the same way KoInsight's plugin does.

Whether the plugin reports partial progress is up to the container. Karasu only reads the boolean;
anything richer is the shelf's own feature.

## Settings reference

*Settings → KOReader*

| Setting | Default | Notes |
| --- | --- | --- |
| Shelf address | *(blank)* | Blank disables the feature entirely; no job is scheduled. |
| API key | *(blank)* | Sent as a bearer token when set. |
| Categories | *(none)* | No categories selected means nothing is sent. |
| Chapters per manga | 3 | |
| Manga on the shelf | 10 | Most recently updated first. |
| Mark chapters read from KOReader | on | Turning this off makes the sync push-only. |
| Sync automatically | every 12 h | `Manual` cancels the periodic job; "Sync now" still works. |
| Only sync over Wi-Fi | on | Becomes the WorkManager network constraint. |

## Karasu-side files

| File | Role |
| --- | --- |
| `karasu/domain/koreader/KoreaderPreferences.kt` | Settings. |
| `karasu/domain/koreader/models/KoreaderShelfEntry.kt` | Wire models. |
| `karasu/data/koreader/KoreaderApi.kt` | The four HTTP calls above. |
| `karasu/domain/koreader/interactor/SyncKoreaderShelf.kt` | The reconcile: pull, upload, prune. |
| `eu/kanade/tachiyomi/data/koreader/KoreaderSyncJob.kt` | Periodic and manual WorkManager job. |
| `eu/kanade/tachiyomi/ui/setting/controllers/SettingsKoreaderController.kt` | Settings screen. |

## Known limits

- Only downloaded CBZ chapters are sent. Loose-image downloads are skipped, not zipped on the fly.
- Read state is a boolean; page-level progress does not come back into Karasu.
- After restoring a backup the shelf still holds the old ids. Those entries stop matching and are
  ignored on pull, then pruned and re-uploaded under the new ids on the next sync.
