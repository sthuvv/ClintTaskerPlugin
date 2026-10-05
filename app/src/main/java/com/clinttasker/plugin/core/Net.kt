package com.clinttasker.plugin.core

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .build()

    val probeClient: OkHttpClient by lazy { client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    val manifestClient: OkHttpClient by lazy { client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }

    fun getText(url: String, headers: Map<String, String>): String? = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        client.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) null else r.body!!.string() }
    } catch (_: Exception) {
        null
    }
}
