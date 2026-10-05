package com.clinttasker.plugin.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.Request

/** Destination d'écriture (MediaStore/Downloads par défaut, ou un dossier choisi). */
class Sink(
    val stream: OutputStream,
    val path: String,
    private val onFinish: () -> Unit,
    private val onAbort: () -> Unit
) {
    fun finish() {
        runCatching { stream.flush() }
        runCatching { stream.close() }
        onFinish()
    }

    fun abort() {
        runCatching { stream.close() }
        onAbort()
    }
}

object OutputFactory {

    private const val SUBDIR = "ClintTasker"

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun open(ctx: Context, dir: String?, fileName: String, ext: String): Sink {
        val name = "$fileName.$ext"
        if (!dir.isNullOrBlank()) {
            val folder = File(dir)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Dossier inaccessible : $dir")
            val file = uniqueFile(folder, name)
            return Sink(file.outputStream().buffered(), file.absolutePath, {}, { file.delete() })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(ext))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Impossible de créer le fichier dans Téléchargements")
            val os = resolver.openOutputStream(uri) ?: throw IOException("Impossible d'ouvrir le fichier")
            val shown = "/storage/emulated/0/${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$name"
            return Sink(
                os.buffered(),
                shown,
                {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                },
                { runCatching { resolver.delete(uri, null, null) } }
            )
        }
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        folder.mkdirs()
        val file = uniqueFile(folder, name)
        return Sink(file.outputStream().buffered(), file.absolutePath, {}, { file.delete() })
    }
}

data class DownloadResult(val path: String, val bytes: Long, val audioPath: String? = null)

object Downloader {

    private val BAD_CHARS = Regex("""[\\/:*?"<>|\r\n\t]""")

    fun safeName(raw: String?): String {
        val cleaned = (raw ?: "").replace(BAD_CHARS, "_").trim().trim('.')
        if (cleaned.isNotEmpty()) return cleaned.take(120)
        return "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private fun splitHeaders(h: Map<String, String>): Triple<String, String, Map<String, String>> {
        val ua = h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: ""
        val ref = h.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: ""
        val extra = h.filterKeys {
            !it.equals("User-Agent", true) && !it.equals("Referer", true) && !it.equals("Cookie", true)
        }
        return Triple(ua, ref, extra)
    }

    private fun extFromUrl(url: String, format: String?): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi", "flv", "mp3", "m4a", "aac", "ogg", "wav", "flac")) return fromUrl
        val f = format?.lowercase()
        if (f != null && f.matches(Regex("[a-z0-9]{2,4}"))) return f
        return "mp4"
    }

    /** Télécharge un média détecté (fichier direct ou HLS). DASH n'est volontairement pas supporté. */
    fun download(
        ctx: Context,
        media: DetectedMedia,
        allDetected: List<DetectedMedia>,
        dir: String?,
        fileName: String?,
        withSeparateAudio: Boolean
    ): DownloadResult {
        val base = safeName(fileName)
        val headers = media.requestHeaders
        val fmt = media.format.uppercase()
        return when (fmt) {
            "DASH" -> throw IOException("DASH (.mpd) : téléchargement non supporté (détection seulement)")
            "HLS" -> {
                if (media.isLive) throw IOException("Flux live : non supporté")
                val audio = if (withSeparateAudio) Ranking.audioFor(media, allDetected) else null
                val videoPath = downloadHls(ctx, media.url, media.pageUrl, headers, dir, base, null)
                val audioPath = audio?.let {
                    runCatching {
                        downloadHls(ctx, it.url, media.pageUrl, headers, dir, base + "_audio", "m4a")
                    }.getOrNull()
                }
                DownloadResult(videoPath.first, videoPath.second, audioPath?.first)
            }
            else -> {
                val ext = extFromUrl(media.url, media.format)
                val sink = OutputFactory.open(ctx, dir, base, ext)
                try {
                    val n = downloadDirect(media.url, media.pageUrl, headers, sink.stream)
                    sink.finish()
                    DownloadResult(sink.path, n)
                } catch (e: Exception) {
                    sink.abort()
                    throw e
                }
            }
        }
    }

    private fun downloadDirect(url: String, pageUrl: String, headers: Map<String, String>, out: OutputStream): Long {
        val (ua, ref, extra) = splitHeaders(headers)
        val map = StreamRequestHeaders.buildMap(url, ref.ifBlank { pageUrl }, "", ua, extra)
        val b = Request.Builder().url(url)
        map.forEach { (k, v) -> runCatching { b.header(k, v) } }
        return Net.client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body!!.byteStream().use { it.copyTo(out) }
        }
    }

    /**
     * @return (chemin, octets). Si [url] est un master playlist, la meilleure variante est choisie.
     */
    private fun downloadHls(
        ctx: Context,
        url: String,
        pageUrl: String,
        headers: Map<String, String>,
        dir: String?,
        base: String,
        forcedExt: String?
    ): Pair<String, Long> {
        val (ua, ref, extra) = splitHeaders(headers)
        val referer = ref.ifBlank { pageUrl }
        var track = HlsPlaylistFetcher.fetch(url, referer, "", ua, TrackKind.VIDEO, extra)
        if (track == null) {
            // Probablement un master playlist : on choisit la meilleure variante.
            val text = Net.getText(url, StreamRequestHeaders.buildMap(url, referer, "", ua, extra))
                ?: throw IOException("Playlist HLS inaccessible")
            val entries = MediaManifestParser.parseHls(text, url)
            val best = Ranking.best(entries.filter { it.url != url })
                ?: throw IOException("Aucune variante HLS exploitable")
            track = HlsPlaylistFetcher.fetch(best.url, referer, "", ua, TrackKind.VIDEO, extra)
                ?: throw IOException("Playlist de la variante illisible")
        }
        val resolved: ResolvedTrack = track ?: throw IOException("Playlist HLS illisible")
        if (resolved.isLive) throw IOException("Flux live : non supporté")

        val ext = forcedExt ?: if (resolved.containerHintExtension == "mp4") "mp4" else "ts"
        val workDir = File(ctx.cacheDir, "hls_${System.nanoTime()}")
        val sink = OutputFactory.open(ctx, dir, base, ext)
        try {
            val downloaded = kotlinx.coroutines.runBlocking {
                StreamTrackDownloader.downloadSegments(
                    track = resolved,
                    workDir = workDir,
                    referer = referer,
                    cookies = "",
                    userAgent = ua,
                    isCancelled = { false },
                    extraHeaders = extra,
                    onProgress = { _, _, _ -> }
                )
            }
            val ordered = downloaded.toSortedMap().values.toList()
            StreamTrackDownloader.decryptSegments(ordered, referer, "", ua, { false }, extra) { _, _ -> }
            val total = ordered.sumOf { it.file.length() }
            StreamTrackDownloader.concatenateTo(ordered, sink.stream)
            StreamTrackDownloader.cleanup(ordered)
            sink.finish()
            return sink.path to total
        } catch (e: Exception) {
            sink.abort()
            throw e
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }
}
