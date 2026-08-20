package com.lyreon.app.yt.innertube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Bangun permintaan InnerTube (youtubei/v1) yang siap dikirim ke Google. */
internal object InnertubeRequest {

    private val JSON = "application/json".toMediaType()

    /** Kunci konteks client yang paling mudah menyediakan stream langsung. */
    enum class Client(val clientName: String, val version: String) {
        IOS("IOS", "19.09.4"),
        ANDROID("ANDROID", "19.09.37"),
        WEB("WEB", "2.20260804.01.00"),
        TV("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0"),
    }

    fun baseContextJson(webVersion: String): String {
        val client = JSONObject()
            .put("clientName", Client.WEB.clientName)
            .put("clientVersion", webVersion)
            .put("hl", "en")
            .put("gl", "US")
        val context = JSONObject().put("client", client)
        return JSONObject().put("context", context).toString()
    }

    /** Endpoint + payload untuk sebuah permintaan player. */
    fun player(client: Client, version: String, videoId: String, visitorData: String?): Pair<String, Request> {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val url = "https://www.youtube.com/youtubei/v1/player?key=${InnertubeConfig.apiKey()}"
        return url to build(url, payload.toString(), client)
    }

    fun search(client: Client, version: String, query: String, params: String?, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("query", query)
        params?.let { payload.put("params", it) }
        val url = "https://www.youtube.com/youtubei/v1/search?key=${InnertubeConfig.apiKey()}"
        return build(url, payload.toString(), client)
    }

    fun playlist(client: Client, version: String, playlistId: String, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("browseId", "VL$playlistId")
        val url = "https://www.youtube.com/youtubei/v1/browse?key=${InnertubeConfig.apiKey()}"
        return build(url, payload.toString(), client)
    }

    fun next(client: Client, version: String, videoId: String, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
        val url = "https://www.youtube.com/youtubei/v1/next?key=${InnertubeConfig.apiKey()}"
        return build(url, payload.toString(), client)
    }

    private fun build(url: String, body: String, client: Client): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(client))
            .header("Origin", "https://www.youtube.com")
            .header("X-Youtube-Client-Name", clientNameId(client).toString())
            .header("X-Youtube-Client-Version", client.version)
            .post(body.toRequestBody(JSON))
            .build()

    private fun clientNameId(client: Client): Int = when (client) {
        Client.IOS -> 5
        Client.ANDROID -> 3
        Client.WEB -> 1
        Client.TV -> 87
    }

    private fun userAgent(client: Client): String = when (client) {
        Client.IOS ->
            "com.google.ios.youtube/${InnertubeConfig.androidClientVersion()} " +
                "(16.5; U; CPU iOS 16_5 like Mac OS X)"
        Client.ANDROID ->
            "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"
        Client.TV ->
            "Mozilla/5.0 (Chromium; Linux; Mobile) AppleWebKit/537.36 (KHTML, like Gecko)"
        Client.WEB ->
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
