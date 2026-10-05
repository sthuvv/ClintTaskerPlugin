package com.clinttasker.plugin.core

import org.json.JSONArray
import org.json.JSONObject

object Ranking {

    private fun score(m: DetectedMedia): Long {
        val h = (m.height ?: 0).toLong()
        val bw = m.bandwidthBitsPerSec ?: 0L
        val size = m.sizeBytes ?: 0L
        return h * 1_000_000_000_000L + (bw / 1000) * 1_000_000L + (size / 1024)
    }

    /** Meilleure vidéo : on exclut les lives si possible, puis résolution > bitrate > taille. */
    fun best(items: List<DetectedMedia>): DetectedMedia? {
        val videos = items.filter { it.kind == MediaKind.VIDEO }
        val pool = videos.filter { !it.isLive }.ifEmpty { videos }
        return pool.maxByOrNull { score(it) }
    }

    fun worst(items: List<DetectedMedia>): DetectedMedia? {
        val videos = items.filter { it.kind == MediaKind.VIDEO }
        val pool = videos.filter { !it.isLive }.ifEmpty { videos }
        return pool.minByOrNull { score(it) }
    }

    /** Piste audio séparée associée à une variante HLS (groupe AUDIO), si elle existe. */
    fun audioFor(video: DetectedMedia, items: List<DetectedMedia>): DetectedMedia? {
        val ref = video.audioGroupRef ?: return null
        val candidates = items.filter { it.kind == MediaKind.AUDIO && it.mediaGroupId == ref }
        return candidates.firstOrNull { it.isDefaultTrack } ?: candidates.firstOrNull()
    }

    fun sortedVideos(items: List<DetectedMedia>): List<DetectedMedia> =
        items.filter { it.kind == MediaKind.VIDEO }.sortedByDescending { score(it) }

    fun quality(m: DetectedMedia): String = when {
        m.height != null -> "${m.height}p"
        m.bandwidthBitsPerSec != null -> "${m.bandwidthBitsPerSec / 1000} kbps"
        else -> ""
    }

    fun headersToLines(h: Map<String, String>): String = h.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    fun toJson(items: List<DetectedMedia>, all: List<DetectedMedia> = items): String {
        val arr = JSONArray()
        for (m in items) {
            val o = JSONObject()
            o.put("url", m.url)
            o.put("kind", m.kind.name)
            o.put("format", m.format)
            o.put("mime", m.mimeType ?: "")
            o.put("width", m.width ?: 0)
            o.put("height", m.height ?: 0)
            o.put("bandwidth", m.bandwidthBitsPerSec ?: 0L)
            o.put("duration", m.durationSeconds ?: 0.0)
            o.put("size", m.sizeBytes ?: 0L)
            o.put("live", m.isLive)
            o.put("label", m.label ?: "")
            o.put("language", m.language ?: "")
            o.put("master", m.groupUrl ?: "")
            o.put("audio_url", if (m.kind == MediaKind.VIDEO) audioFor(m, all)?.url ?: "" else "")
            val hdr = JSONObject()
            m.requestHeaders.forEach { (k, v) -> hdr.put(k, v) }
            o.put("headers", hdr)
            arr.put(o)
        }
        return arr.toString()
    }
}
