# Attribution

Ce projet est un fork partiel de **Clint Browser** (https://github.com/jhaiian/ClintBrowser, GPL-3.0).
Il est distribué sous la même licence (GPL-3.0, voir `LICENSE`).

Code repris de Clint (adapté) :
- `MediaManifestParser.kt`  ← mediacapture/MediaManifestParser.kt
- `HlsPlaylistFetcher.kt`, `StreamDownloadModels.kt`, `StreamTrackDownloader.kt`, `HlsAesDecryptor.kt` ← mediacapture/download/
- `MediaDetector.kt`  ← mediacapture/MediaCaptureDetector.kt + MediaCaptureStore.kt (logique de détection, anti-spam de segments, fusion des variantes HLS)
- `Models.kt` ← mediacapture/MediaCaptureModels.kt

Modifications : remplacement de ClintDownloadManager par un client OkHttp local, OkHttp 4.x, état par session au lieu d'état par onglet, suppression du SpeedLimiter et des sondes MediaExtractor.
