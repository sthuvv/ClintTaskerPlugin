package com.clinttasker.plugin.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

data class FindOptions(
    val url: String,
    val timeoutMs: Long = 25_000L,
    val settleMs: Long = 2_500L,
    val userAgent: String? = null,
    val desktop: Boolean = false,
    val blockImages: Boolean = true
)

data class FindResult(val all: List<DetectedMedia>) {
    val best: DetectedMedia? get() = Ranking.best(all)
    val bestAudio: DetectedMedia? get() = best?.let { Ranking.audioFor(it, all) }
}

/**
 * Charge la page dans une WebView invisible et laisse MediaDetector observer chaque requête
 * (shouldInterceptRequest), exactement comme Clint le fait dans ClintWebViewClient.
 */
object Finder {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Tente de lancer la lecture et remonte les URL http(s) trouvées dans le DOM.
    private const val KICK_JS = """
(function(){
  var out=[];
  try{
    document.querySelectorAll('video,audio').forEach(function(v){
      try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}
      if(v.currentSrc)out.push(v.currentSrc);
      if(v.src)out.push(v.src);
      v.querySelectorAll('source').forEach(function(s){if(s.src)out.push(s.src);});
    });
    ['.vjs-big-play-button','.ytp-large-play-button','.jw-icon-display','.plyr__control--overlaid',
     'button[aria-label*="lay"]','[class*="play-button"]','[class*="playButton"]','.play-btn','.btn-play'
    ].forEach(function(q){try{var el=document.querySelector(q);if(el)el.click();}catch(e){}});
  }catch(e){}
  return JSON.stringify(out);
})()
"""

    suspend fun find(context: Context, opts: FindOptions): FindResult {
        val detector = MediaDetector(opts.url)
        try {
            // 1) URL directe (mp4, m3u8, mpd, ...) : pas besoin de WebView.
            detector.onRequest(opts.url, "GET", mapOf("User-Agent" to (opts.userAgent ?: DESKTOP_UA)))
            val directDeadline = SystemClock.elapsedRealtime() + 8_000L
            while (SystemClock.elapsedRealtime() < directDeadline && detector.inFlight.get() > 0) delay(200)
            if (detector.snapshot().any { it.kind == MediaKind.VIDEO }) {
                delay(300)
                return FindResult(detector.snapshot())
            }
            // 2) Page web : on la charge réellement.
            sniffWithWebView(context.applicationContext, opts, detector)
            return FindResult(detector.snapshot())
        } finally {
            detector.close()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffWithWebView(ctx: Context, opts: FindOptions, detector: MediaDetector) {
        var webView: WebView? = null
        val pageFinished = AtomicBoolean(false)

        fun feedDomResult(raw: String?) {
            if (raw.isNullOrBlank() || raw == "null") return
            runCatching {
                val inner = JSONTokener(raw).nextValue() as? String ?: return
                val arr = JSONArray(inner)
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i)
                    if (u.startsWith("http://") || u.startsWith("https://")) detector.onRequest(u, "GET", null)
                }
            }
        }

        withContext(Dispatchers.Main) {
            val wv = WebView(ctx)
            webView = wv
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                blockNetworkImages = opts.blockImages
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            val ua = when {
                !opts.userAgent.isNullOrBlank() -> opts.userAgent
                opts.desktop -> DESKTOP_UA
                else -> null
            }
            if (ua != null) wv.settings.userAgentString = ua
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request != null) {
                        detector.onRequest(request.url.toString(), request.method, request.requestHeaders)
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageFinished.set(true)
                    view?.evaluateJavascript(KICK_JS) { feedDomResult(it) }
                }
            }
            wv.loadUrl(opts.url)
        }

        try {
            val start = SystemClock.elapsedRealtime()
            var lastKick = start
            while (true) {
                delay(300)
                val now = SystemClock.elapsedRealtime()
                if (now - start >= opts.timeoutMs) break
                if (pageFinished.get() && now - lastKick >= 3_000L) {
                    lastKick = now
                    withContext(Dispatchers.Main) {
                        webView?.evaluateJavascript(KICK_JS) { feedDomResult(it) }
                    }
                }
                val foundVideo = detector.snapshot().any { it.kind == MediaKind.VIDEO }
                val idle = detector.inFlight.get() == 0 &&
                    System.currentTimeMillis() - detector.lastActivityMillis >= opts.settleMs
                if (foundVideo && idle) break
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
        }
    }
}
