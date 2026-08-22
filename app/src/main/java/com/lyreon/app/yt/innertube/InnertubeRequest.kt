package com.lyreon.app.yt.innertube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Bangun permintaan InnerTube (youtubei/v1) yang siap dikirim ke Google. */
internal object InnertubeRequest {

    private val JSON = "application/json".toMediaType()

    enum class Client(
        val clientName: String,
        val version: String,
        val id: Int,
        val isEmbedded: Boolean = false,
    ) {
        ANDROID_VR("ANDROID_VR", "1.60.19", 28),
        ANDROID_TESTSUITE("ANDROID_TESTSUITE", "1.9", 119),
        TV_EMBEDDED("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", 85, isEmbedded = true),
        IOS("IOS", "19.45.4", 5),
        WEB_REMIX("WEB_REMIX", "1.20260818.01.00", 67),
        ANDROID("ANDROID", "19.45.38", 3),
        WEB("WEB", "2.20260818.01.00", 1),
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
        if (client == Client.ANDROID || client == Client.ANDROID_VR || client == Client.ANDROID_TESTSUITE) {
            clientJson.put("androidSdkVersion", 34)
            clientJson.put("osName", "Android")
            clientJson.put("osVersion", "14")
        }
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        if (client.isEmbedded) {
            context.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=$videoId"))
        }

        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS"),
                ),
            )
        val url = "https://www.youtube.com/youtubei/v1/player?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
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
        val url = "https://www.youtube.com/youtubei/v1/search?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
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
        val url = "https://www.youtube.com/youtubei/v1/browse?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
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
        val url = "https://www.youtube.com/youtubei/v1/next?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
        return build(url, payload.toString(), client)
    }

    private fun build(url: String, body: String, client: Client): Request {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(client))
            .header("X-Youtube-Client-Name", client.id.toString())
            .header("X-Youtube-Client-Version", client.version)
            .header("Accept-Language", "en-US,en;q=0.9")

        when (client) {
            Client.WEB_REMIX -> {
                req.header("Origin", "https://music.youtube.com")
                req.header("Referer", "https://music.youtube.com/")
            }
            Client.WEB, Client.TV_EMBEDDED -> {
                req.header("Origin", "https://www.youtube.com")
                req.header("Referer", "https://www.youtube.com/")
            }
            else -> {
                // Android & iOS clients typically do not pass browser Origin/Referer
            }
        }

        return req.post(body.toRequestBody(JSON)).build()
    }

    private fun userAgent(client: Client): String = when (client) {
        Client.ANDROID_VR ->
            "com.google.android.apps.youtube.vr/1.60.19 (Linux; U; Android 14) gzip"
        Client.ANDROID_TESTSUITE ->
            "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip"
        Client.TV_EMBEDDED ->
            "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        Client.IOS ->
            "com.google.ios.youtube/19.45.4 (iPhone14,5; U; CPU iOS 17_6 like Mac OS X)"
        Client.WEB_REMIX ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        Client.ANDROID ->
            "com.google.android.youtube/19.45.38 (Linux; U; Android 14; Pixel 8 Pro) gzip"
        Client.WEB ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}
