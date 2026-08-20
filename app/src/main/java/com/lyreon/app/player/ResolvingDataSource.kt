package com.lyreon.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lyreon.app.yt.LyreonHttp
import com.lyreon.app.yt.YouTubeRepository
import java.io.IOException

/**
 * DataSource yang menyelesaikan skema `lyreon://audio/{videoId}` menjadi:
 *  1) file lokal (jika sudah diunduh), atau
 *  2) URL stream audio YouTube (dengan cache ±6 jam).
 *
 * Dipanggil di thread loader ExoPlayer — pola yang sama dengan InnerTune/Metrolist,
 * sehingga auto-advance antrean & prefetch bekerja tanpa UI memediasi.
 */
class ResolvingDataSource(
    private val youtube: YouTubeRepository,
    private val downloadFileLookup: (String) -> String?,
    private val upstream: DataSource,
) : BaseDataSource(true) {

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        var fallbackUri: Uri? = null
        var resolvableVideoId: String? = null
        val resolvedSpec: DataSpec = if (uri.scheme == LYREON_SCHEME) {
            val videoId = uri.lastPathSegment
                ?: throw IOException("URI lyreon tidak valid: $uri")
            resolvableVideoId = videoId

            val local = downloadFileLookup(videoId)
            val target = when {
                !local.isNullOrBlank() && java.io.File(local).exists() ->
                    Uri.fromFile(java.io.File(local))
                else -> try {
                    val resolved = youtube.resolveCachedBlocking(videoId)
                    resolved.fallbackUrl
                        ?.takeUnless { it.isBlank() || it == resolved.url }
                        ?.let { fallbackUri = Uri.parse(it) }
                    Uri.parse(resolved.url)
                } catch (e: Exception) {
                    throw IOException("Tidak bisa menyelesaikan stream $videoId: ${e.message}", e)
                }
            }
            dataSpec.withUri(target)
        } else {
            dataSpec
        }

        val length = try {
            upstream.open(resolvedSpec)
        } catch (first: Exception) {
            val backup = fallbackUri
            if (backup != null) {
                // STREAM CADANGAN: URL utama tak tersedia (403/diblokir) —
                // langsung coba kandidat ke-2 dari keluarga format lain
                // (m4a ↔ webm/opus) sebelum menyerah.
                runCatching { upstream.close() }
                try {
                    upstream.open(resolvedSpec.withUri(backup))
                } catch (second: Exception) {
                    resolvableVideoId?.let { youtube.invalidate(it) }
                    throw second
                }
            } else {
                // Jika URL expired (403), invalidasi cache sekali lalu lempar —
                // PlayerManager akan retry dengan URL baru.
                resolvableVideoId?.let { youtube.invalidate(it) }
                throw first
            }
        }
        opened = true
        transferStarted(resolvedSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val context: Context,
        private val youtube: YouTubeRepository,
        private val downloadFileLookup: (String) -> String?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val okHttpFactory = OkHttpDataSource.Factory(LyreonHttp.streamClient)
            val defaultFactory = DefaultDataSource.Factory(context, okHttpFactory)
            return ResolvingDataSource(youtube, downloadFileLookup, defaultFactory.createDataSource())
        }
    }

    companion object {
        const val LYREON_SCHEME = "lyreon"
        fun uriOf(videoId: String): Uri = Uri.parse("$LYREON_SCHEME://audio/$videoId")
    }
}
