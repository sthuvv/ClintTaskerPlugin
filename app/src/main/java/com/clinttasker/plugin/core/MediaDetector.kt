package com.clinttasker.plugin.core

import android.net.Uri
import android.webkit.CookieManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Port de MediaCaptureDetector + MediaCaptureStore de Clint Browser (GPLv3).
 * Différences : une instance = une session de détection (pas d'état global par onglet),
 * l'entrée est une simple URL + headers (pas un WebResourceRequest), et il n'y a pas
 * d'enrichissement MediaExtractor (durée/résolution des fichiers directs).
 */
class MediaDetector(private val pageUrl: String) {

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "3g2", "avi", "flv", "wmv")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "weba")
        val SUBTITLE_EXTENSIONS = setOf("vtt", "srt")
        val MANIFEST_EXTENSIONS = setOf("m3u8", "mpd")
        val NON_MEDIA_DESTS = setOf(
            "document", "iframe", "script", "style", "image", "font",
            "worker", "sharedworker", "manifest", "report", "object", "embed", "xslt"
        )
        val TRANSPORT_STREAM_CONTENT_TYPES = setOf("video/mp2t", "video/vnd.dlna.mpeg-tts")
        const val MAX_MANIFEST_BYTES = 3L * 1024 * 1024
        const val MAX_MANIFEST_CHARS = MAX_MANIFEST_BYTES.toInt()
        const val MIN_EXTENSIONLESS_MEDIA_BYTES = 20_000L
        const val SEGMENT_SPAM_WINDOW_MILLIS = 15_000L
        const val SEGMENT_SPAM_THRESHOLD = 5
        val EXCLUDED_HEADERS = setOf(
            "range", "if-range", "if-modified-since", "if-none-match",
            "accept-encoding", "connection", "content-length", "host", "cookie"
        )
        val FILENAME_REGEX = Regex("""filename\*?=(?:UTF-8''|"|')?([^";']+)""", RegexOption.IGNORE_CASE)
    }

    private data class TemplateWindow(val windowStartMillis: Long, val count: Int)
    private data class HeadResult(val contentType: String?, val contentLength: Long?, val contentDisposition: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seenUrls = ConcurrentHashMap.newKeySet<String>()
    private val templateCounts = ConcurrentHashMap<String, TemplateWindow>()
    private val items = ArrayList<DetectedMedia>()

    /** Nombre de sondes réseau en cours. */
    val inFlight = AtomicInteger(0)

    /** Horodatage (ms) de la dernière activité de détection (résultat ajouté ou sonde terminée). */
    @Volatile
    var lastActivityMillis: Long = System.currentTimeMillis()
        private set

    fun snapshot(): List<DetectedMedia> = synchronized(items) { items.toList() }

    fun close() {
        scope.cancel()
    }

    private fun touch() {
        lastActivityMillis = System.currentTimeMillis()
    }

    private fun track(block: suspend () -> Unit) {
        inFlight.incrementAndGet()
        scope.launch {
            try {
                block()
            } finally {
                inFlight.decrementAndGet()
                touch()
            }
        }
    }

    // ---------- helpers URL ----------

    private fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        return headers.filterKeys { it.lowercase() !in EXCLUDED_HEADERS }
    }

    private fun knownMediaExtension(ext: String): Boolean =
        ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS || ext in SUBTITLE_EXTENSIONS || ext in MANIFEST_EXTENSIONS

    private fun safeQueryParamNames(uri: Uri): Set<String> =
        runCatching { uri.queryParameterNames }.getOrNull() ?: emptySet()

    private fun extensionFromQuery(uri: Uri): String? {
        for (name in safeQueryParamNames(uri)) {
            val value = runCatching { uri.getQueryParameter(name) }.getOrNull() ?: continue
            val candidate = value.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (candidate.isNotEmpty() && knownMediaExtension(candidate)) return candidate
        }
        return null
    }

    private fun segmentTemplateKey(uri: Uri): String {
        val normalizedPath = (uri.path ?: "").replace(Regex("\\d+"), "#")
        val queryKeys = safeQueryParamNames(uri).sorted().joinToString(",")
        return "${uri.scheme}://${uri.host}$normalizedPath?$queryKeys"
    }

    // ---------- point d'entrée ----------

    /** Équivalent de MediaCaptureDetector.onRequestObserved. Appelable depuis n'importe quel thread. */
    fun onRequest(url: String, method: String?, headers: Map<String, String>?) {
        if (method != null && !method.equals("GET", ignoreCase = true)) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return

        if (!seenUrls.add(url)) return

        val path = uri.path?.lowercase() ?: ""
        var ext = path.substringAfterLast('.', "")
        if (!knownMediaExtension(ext)) {
            extensionFromQuery(uri)?.let { ext = it }
        }
        val dest = headers?.entries
            ?.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value?.lowercase() ?: ""
        val accept = headers?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase() ?: ""
        val looksLikeMediaAccept = accept.contains("video/") || accept.contains("audio/")
        val looksLikeNonMediaAccept = accept.contains("application/json") || accept.contains("text/html")
        val now = System.currentTimeMillis()

        fun probeUnlessSpam(kind: MediaKind?, format: String?) {
            if (registerAndCheckSpam(segmentTemplateKey(uri), now)) return
            probeAndRecord(url, headers, kind, format)
        }

        when {
            ext == "m3u8" -> fetchAndParseManifest(url, ManifestType.HLS, headers)
            ext == "mpd" -> fetchAndParseManifest(url, ManifestType.DASH, headers)
            ext in VIDEO_EXTENSIONS -> probeUnlessSpam(MediaKind.VIDEO, ext.uppercase())
            ext in AUDIO_EXTENSIONS -> probeUnlessSpam(MediaKind.AUDIO, ext.uppercase())
            ext in SUBTITLE_EXTENSIONS -> add(
                DetectedMedia(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    kind = MediaKind.SUBTITLE,
                    format = ext.uppercase(),
                    pageUrl = pageUrl,
                    detectedAtMillis = now,
                    requestHeaders = sanitizeHeaders(headers)
                )
            )
            ext.isEmpty() && (looksLikeMediaAccept || (dest !in NON_MEDIA_DESTS && !looksLikeNonMediaAccept)) ->
                probeUnlessSpam(null, null)
            else -> Unit
        }
    }

    // ---------- anti-spam segments + store ----------

    private fun registerAndCheckSpam(templateKey: String, nowMillis: Long): Boolean {
        var isSpam = false
        templateCounts.compute(templateKey) { _, existing ->
            val window = if (existing == null || nowMillis - existing.windowStartMillis > SEGMENT_SPAM_WINDOW_MILLIS) {
                TemplateWindow(nowMillis, 1)
            } else {
                TemplateWindow(existing.windowStartMillis, existing.count + 1)
            }
            isSpam = window.count > SEGMENT_SPAM_THRESHOLD
            window
        }
        return isSpam
    }

    private fun dedupeKey(media: DetectedMedia): String =
        if (media.groupUrl != null && media.url == media.groupUrl) {
            "${media.url}#${media.width}x${media.height}#${media.bandwidthBitsPerSec}#${media.mimeType}"
        } else {
            media.url
        }

    private fun isDirectHlsPlaylist(media: DetectedMedia): Boolean =
        media.kind == MediaKind.VIDEO && media.format.equals("HLS", ignoreCase = true) &&
            media.groupUrl != null && media.url == media.groupUrl

    private fun isHlsVariant(media: DetectedMedia): Boolean =
        media.kind == MediaKind.VIDEO && media.format.equals("HLS", ignoreCase = true) &&
            media.groupUrl != null && media.url != media.groupUrl

    private fun add(media: DetectedMedia) {
        synchronized(items) {
            val key = dedupeKey(media)
            when {
                items.any { dedupeKey(it) == key } -> Unit
                isDirectHlsPlaylist(media) && items.any { isHlsVariant(it) && it.url == media.url } -> Unit
                isHlsVariant(media) -> {
                    val direct = items.firstOrNull { isDirectHlsPlaylist(it) && it.url == media.url }
                    if (direct == null) {
                        items.add(media)
                    } else {
                        val merged = media.copy(
                            width = media.width ?: direct.width,
                            height = media.height ?: direct.height,
                            durationSeconds = media.durationSeconds ?: direct.durationSeconds,
                            isLive = media.isLive || direct.isLive
                        )
                        val idx = items.indexOfFirst { it.id == direct.id }
                        if (idx >= 0) items[idx] = merged else items.add(merged)
                    }
                }
                else -> items.add(media)
            }
        }
        touch()
    }

    // ---------- HTTP ----------

    private fun applyHeaders(builder: Request.Builder, original: Map<String, String>?, url: String) {
        original?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept-Encoding", ignoreCase = true)) {
                runCatching { builder.header(key, value) }
            }
        }
        val hasCookie = original?.keys?.any { it.equals("Cookie", ignoreCase = true) } == true
        if (!hasCookie) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookie.isNullOrBlank()) runCatching { builder.header("Cookie", cookie) }
        }
        val hasReferer = original?.keys?.any { it.equals("Referer", ignoreCase = true) } == true
        if (!hasReferer && pageUrl.isNotBlank()) {
            runCatching { builder.header("Referer", pageUrl) }
        }
    }

    private fun performHead(url: String, headers: Map<String, String>?): HeadResult? = try {
        val builder = Request.Builder().url(url).head()
        applyHeaders(builder, headers, url)
        Net.probeClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else HeadResult(
                resp.header("Content-Type"),
                resp.header("Content-Length")?.toLongOrNull(),
                resp.header("Content-Disposition")
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun performRangedGet(url: String, headers: Map<String, String>?): HeadResult? = try {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-0")
        applyHeaders(builder, headers, url)
        Net.probeClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else {
                val size = if (resp.code == 206) {
                    resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                } else {
                    resp.header("Content-Length")?.toLongOrNull()
                }
                HeadResult(resp.header("Content-Type"), size, resp.header("Content-Disposition"))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun performProbe(url: String, headers: Map<String, String>?): HeadResult? {
        val head = performHead(url, headers)
        if (head != null && (head.contentType != null || head.contentLength != null)) return head
        return performRangedGet(url, headers) ?: head
    }

    private fun kindFromContentType(contentType: String?): MediaKind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            ct.startsWith("video/") -> MediaKind.VIDEO
            ct.startsWith("audio/") -> MediaKind.AUDIO
            ct.contains("vtt") || ct == "application/x-subrip" -> MediaKind.SUBTITLE
            else -> null
        }
    }

    private fun formatFromContentType(contentType: String?): String? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return ct.substringAfter('/', "").takeIf { it.isNotEmpty() }?.uppercase()
    }

    private fun extensionFromContentDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val name = FILENAME_REGEX.find(value)?.groupValues?.get(1)?.trim()?.trim('"') ?: return null
        return name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
    }

    private fun kindFromExtension(ext: String?): MediaKind? = when (ext) {
        null -> null
        in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        in SUBTITLE_EXTENSIONS -> MediaKind.SUBTITLE
        else -> null
    }

    private fun sniffLeadingBytes(url: String, headers: Map<String, String>?): ByteArray? = try {
        val builder = Request.Builder().url(url).header("Range", "bytes=0-15")
        applyHeaders(builder, headers, url)
        Net.probeClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null
            else {
                val buffer = ByteArray(16)
                var total = 0
                val stream = resp.body!!.byteStream()
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                if (total == 0) null else buffer.copyOf(total)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || offset + length > bytes.size) return null
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun isHlsPlaylist(bytes: ByteArray): Boolean {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return asciiAt(bytes, if (hasBom) 3 else 0, 7) == "#EXTM3U"
    }

    private fun kindAndFormatFromMagicBytes(bytes: ByteArray): Pair<MediaKind, String>? {
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) return MediaKind.VIDEO to "WEBM"
        if (asciiAt(bytes, 4, 4) == "ftyp") {
            val brand = asciiAt(bytes, 8, 4) ?: ""
            return if (brand.startsWith("M4A") || brand.startsWith("M4B")) MediaKind.AUDIO to "M4A" else MediaKind.VIDEO to "MP4"
        }
        if (asciiAt(bytes, 0, 3) == "ID3") return MediaKind.AUDIO to "MP3"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return MediaKind.AUDIO to "MP3"
        if (asciiAt(bytes, 0, 4) == "OggS") return MediaKind.AUDIO to "OGG"
        if (asciiAt(bytes, 0, 4) == "fLaC") return MediaKind.AUDIO to "FLAC"
        if (asciiAt(bytes, 0, 3) == "FLV") return MediaKind.VIDEO to "FLV"
        if (asciiAt(bytes, 0, 4) == "RIFF") {
            val subtype = asciiAt(bytes, 8, 4) ?: ""
            return when {
                subtype == "WAVE" -> MediaKind.AUDIO to "WAV"
                subtype.startsWith("AVI") -> MediaKind.VIDEO to "AVI"
                else -> null
            }
        }
        return null
    }

    // ---------- sonde fichier ----------

    private fun probeAndRecord(url: String, headers: Map<String, String>?, extKind: MediaKind?, extFormat: String?) {
        track {
            val head = performProbe(url, headers)
            val contentType = head?.contentType?.substringBefore(';')?.trim()?.lowercase()
            when {
                contentType != null && (contentType.contains("mpegurl") || contentType.contains("x-mpegurl")) ->
                    fetchAndParseManifestNow(url, ManifestType.HLS, headers)
                contentType == "application/dash+xml" ->
                    fetchAndParseManifestNow(url, ManifestType.DASH, headers)
                else -> {
                    if (extKind == null && contentType != null && contentType in TRANSPORT_STREAM_CONTENT_TYPES) return@track
                    val cdExt = extensionFromContentDisposition(head?.contentDisposition)
                    var kind = extKind ?: kindFromContentType(contentType) ?: kindFromExtension(cdExt)
                    var format = extFormat ?: cdExt?.uppercase() ?: formatFromContentType(contentType)
                    if (kind == null) {
                        val leading = sniffLeadingBytes(url, headers)
                        if (leading != null && isHlsPlaylist(leading)) {
                            fetchAndParseManifestNow(url, ManifestType.HLS, headers)
                            return@track
                        }
                        val magic = leading?.let(::kindAndFormatFromMagicBytes)
                        if (magic != null) {
                            kind = magic.first
                            format = format ?: magic.second
                        }
                    }
                    if (kind == null) return@track
                    if (extKind == null && extFormat == null) {
                        val size = head?.contentLength
                        if (size != null && size < MIN_EXTENSIONLESS_MEDIA_BYTES) return@track
                    }
                    add(
                        DetectedMedia(
                            id = UUID.randomUUID().toString(),
                            url = url,
                            kind = kind,
                            format = format ?: "UNKNOWN",
                            mimeType = head?.contentType,
                            sizeBytes = head?.contentLength,
                            pageUrl = pageUrl,
                            detectedAtMillis = System.currentTimeMillis(),
                            requestHeaders = sanitizeHeaders(headers)
                        )
                    )
                }
            }
        }
    }

    // ---------- manifestes ----------

    private fun requestManifestBody(manifestUrl: String, headers: Map<String, String>?, useRange: Boolean): String? = try {
        val builder = Request.Builder().url(manifestUrl)
        if (useRange) builder.header("Range", "bytes=0-${MAX_MANIFEST_BYTES - 1}")
        applyHeaders(builder, headers, manifestUrl)
        Net.manifestClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body!!.byteStream().bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                val sb = StringBuilder()
                var total = 0
                while (total < MAX_MANIFEST_CHARS) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    sb.append(buffer, 0, read)
                    total += read
                }
                sb.toString()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchAndParseManifest(manifestUrl: String, type: ManifestType, headers: Map<String, String>?) {
        track { fetchAndParseManifestNow(manifestUrl, type, headers) }
    }

    private fun fetchAndParseManifestNow(manifestUrl: String, type: ManifestType, headers: Map<String, String>?) {
        val text = requestManifestBody(manifestUrl, headers, useRange = true)
            ?: requestManifestBody(manifestUrl, headers, useRange = false)
            ?: return
        val entries = try {
            when (type) {
                ManifestType.HLS -> MediaManifestParser.parseHls(text, manifestUrl)
                ManifestType.DASH -> MediaManifestParser.parseDash(text, manifestUrl)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val sanitized = sanitizeHeaders(headers)
        entries.forEach { add(it.copy(pageUrl = pageUrl, detectedAtMillis = now, requestHeaders = sanitized)) }
    }
}
