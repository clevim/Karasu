# Yatagarasu Companion v2 (Unified Sync, Suwayomi Bridge, Downloads, Translation & Backups)

Yatagarasu v2 extends the KOReader shelf (`docs/koreader-sync.md`) into a unified self-hosted companion for Karasu.
Every v1 endpoint (`/api/health`, `/api/shelf`, `/api/shelf/{chapterId}`, `/api/shelf/{chapterId}/file`, `/api/shelf/{chapterId}/read`, `/plugin.zip`) remains unchanged and backwards-compatible.

## Architecture

```
┌──────────────────────┐   HTTP/JSON + CBZ stream (LAN / Tailscale)   ┌──────────────────────────────────┐
│   Karasu (Android)   │ ◄──────────────────────────────────────────► │      Yatagarasu (Go, :3080)      │
│ • Dual-Origin client │                                              │ • KOReader Shelf (/api/shelf)    │
│ • Offline-first DB   │                                              │ • Sync Hub (/api/sync/reconcile) │
│ • Smart Downloader   │                                              │ • CBZ Proxy (/api/chapters/*)    │
│ • Translation Engine │                                              │ • LLM Translator (/api/trans*)   │
└──────────────────────┘                                              │ • Backups (/api/backups)         │
                                                                      └───────────────┬──────────────────┘
                                                                                      │ optional (--profile suwayomi)
                                                                                      ▼
                                                                      ┌──────────────────────────────────┐
                                                                      │   Suwayomi-Server (official)     │
                                                                      │ • Runs Mihon/Tachiyomi APKs      │
                                                                      │ • Downloads chapters 24/7        │
                                                                      │ • Optional FlareSolverr          │
                                                                      └──────────────────────────────────┘
```

## Authentication & Dual-Origin

- All `/api/*` endpoints use `Authorization: Bearer <apiKey>` when an API key is configured.
- Karasu supports a **Primary URL (LAN)** and an optional **Fallback URL (Tailscale/VPN/public)**. On connection attempts, Karasu probes the primary URL with a short timeout and falls back automatically to the secondary URL.

## HTTP Contract

### 1. `GET /api/health`

Backwards-compatible extension of v1 health check:

```json
{
  "name": "yatagarasu",
  "version": "2",
  "entries": 12,
  "capabilities": ["shelf", "sync", "backups", "translate", "downloads", "suwayomi"],
  "suwayomi": {
    "configured": true,
    "reachable": true,
    "version": "v2.1.1867"
  },
  "translate": {
    "configured": true,
    "provider": "openai",
    "model": "google/gemma-4-31b-it:free"
  }
}
```

### 2. `POST /api/sync/reconcile`

Bidirectional reconciliation of library, categories, read progress, merged sources, extension repos, and installed extensions.
Entities are matched by natural keys: manga by `(sourceId, url)`, chapter by `url` (fallback `chapterNumber` + `scanlator`), category by `name`.

Request & Response (`SyncPayload`):

```json
{
  "deviceId": "pixel-9",
  "lastSyncAt": 1730000000000,
  "serverTime": 1730000123456,
  "categories": [
    { "name": "Reading", "order": 1, "flags": 0 }
  ],
  "mangas": [
    {
      "sourceId": 2499283573021220255,
      "url": "/manga/one-piece",
      "title": "One Piece",
      "favorite": true,
      "categories": ["Reading"],
      "updatedAt": 1730000000000,
      "mergedSources": [
        { "sourceId": 123456789, "url": "/series/one-piece", "priority": 0, "updatesEnabled": true }
      ],
      "chapters": [
        {
          "url": "/manga/one-piece/ch-1090",
          "name": "Chapter 1090",
          "chapterNumber": 1090.0,
          "scanlator": "",
          "read": true,
          "bookmark": false,
          "lastPageRead": 18,
          "updatedAt": 1730000000000
        }
      ]
    }
  ],
  "deletedMangaKeys": [
    { "sourceId": 2499283573021220255, "url": "/manga/dropped-title" }
  ],
  "extensionRepos": ["https://raw.githubusercontent.com/.../index.min.json"],
  "installedExtensions": [
    { "pkgName": "eu.kanade.tachiyomi.extension.en.mangadex", "name": "MangaDex", "lang": "en", "versionCode": 42 }
  ],
  "missingExtensions": []
}
```

Conflict resolution rules:
- **Chapters:** `read = true` wins when timestamps tie; otherwise higher `updatedAt` wins. When both are unread, `max(lastPageRead)` wins.
- **Manga favorite & deletions:** explicit `deletedMangaKeys` with a timestamp newer than the remote `updatedAt` removes/unfavorites the manga rather than resurrecting it.
- **Categories & Extension Repos:** union merge by name/URL.
- **Suwayomi bridge:** when Suwayomi is configured, Yatagarasu also reconciles library entries, categories, chapter read states, and extension repos with Suwayomi's GraphQL API (`/api/graphql`).

### 3. Smart CBZ Downloads & Server-Side Acquisition

- `GET /api/chapters/locate?sourceId=<id>&mangaUrl=<url>&chapterUrl=<url>`
  - Returns `{ "available": true, "source": "suwayomi"|"local"|"none", "suwayomiChapterId": 123, "size": 15728640 }`.
- `GET /api/chapters/cbz?sourceId=<id>&mangaUrl=<url>&chapterUrl=<url>&acquire=true|false`
  - Streams the chapter `.cbz` (`Content-Type: application/vnd.comicbook+zip`).
  - Checks local cache (`/data/downloads/`) first, then Suwayomi.
  - If not yet downloaded on Suwayomi and `acquire=true`, enqueues the download on Suwayomi, waits until ready (bounded timeout), and streams the resulting CBZ.
  - Returns `404` when unavailable so Karasu falls back transparently to web download.

### 4. Server-Side Manga Translation & Shared Cache

- `GET /api/translations/chapter?sourceId=<id>&mangaUrl=<url>&chapterUrl=<url>&toLang=<lang>`
  - Returns the stored `Map<String, PageTranslation>` JSON (`200`) or `404`.
- `PUT /api/translations/chapter?sourceId=<id>&mangaUrl=<url>&chapterUrl=<url>&toLang=<lang>`
  - Persists a chapter's `Map<String, PageTranslation>` JSON so any connected device gets it without re-translating.
- `POST /api/translations/translate`
  - Translates OCR blocks using Yatagarasu's shared line cache (`/data/translations/cache.json`) and configured server-side LLM (OpenAI-compatible endpoint such as OpenRouter, DeepSeek, Ollama/vLLM, or Google Gemini).
  - Request:
    ```json
    {
      "fromLang": "en",
      "toLang": "pt-BR",
      "seriesNotes": { "notes": "", "glossary": {} },
      "pages": { "001.jpg": { "blocks": [...], "imgWidth": 1000, "imgHeight": 1500 } }
    }
    ```
  - Response:
    ```json
    {
      "pages": { "001.jpg": { "blocks": [...], "imgWidth": 1000, "imgHeight": 1500 } },
      "learned": { "Nakama": "Companheiros" }
    }
    ```
- `GET /api/translations/context?sourceId=<id>&mangaUrl=<url>` & `PUT /api/translations/context?sourceId=<id>&mangaUrl=<url>`
  - Syncs per-series `SeriesNotes` (notes + glossary).

### 5. Remote `.tachibk` Backups

- `GET /api/backups` — Lists backups: `{ "backups": [{ "id": "...", "name": "...", "size": 12345, "createdAt": 1730000000 }] }`.
- `POST /api/backups` — Raw binary or `multipart/form-data` upload (`X-Backup-Filename` header or `file` field). Streams directly to `/data/backups/`.
- `GET /api/backups/{id}` — Streams the `.tachibk` archive for restoration.
- `DELETE /api/backups/{id}` — Deletes a backup.
