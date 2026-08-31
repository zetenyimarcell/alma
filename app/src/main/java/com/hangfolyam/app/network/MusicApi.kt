package com.hangfolyam.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object MusicApi {
    private val client = OkHttpClient()
    private const val PIPED_INSTANCE = "https://pipedapi.kavin.rocks"

    suspend fun searchMusicFromInternetFull(query: String): List<LiveSong> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<LiveSong>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$PIPED_INSTANCE/search?q=$encodedQuery&filter=music_songs")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: JSONArray()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("type") == "stream") {
                        val url = item.optString("url") // pl. /watch?v=VIDEO_ID
                        val videoId = url.removePrefix("/watch?v=")
                        val title = item.optString("title")
                        val uploader = item.optString("uploaderName")
                        val thumbnail = item.optString("thumbnail")

                        songs.add(
                            LiveSong(
                                id = videoId,
                                title = title,
                                artist = uploader,
                                coverUrl = thumbnail,
                                streamUrl = "yt:$videoId"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        songs
    }

    suspend fun getYouTubeAudioStream(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$PIPED_INSTANCE/streams/$videoId")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val audioStreams = json.optJSONArray("audioStreams") ?: return@withContext null

                for (i in 0 until audioStreams.length()) {
                    val stream = audioStreams.optJSONObject(i) ?: continue
                    val url = stream.optString("url")
                    if (url.isNotEmpty()) {
                        return@withContext url
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

// Globalus segédfüggvények átirányítása a MainActivity-ben felhasznált nevekhez
suspend fun searchMusicFromInternetFull(query: String) = MusicApi.searchMusicFromInternetFull(query)
suspend fun getYouTubeAudioStream(videoId: String) = MusicApi.getYouTubeAudioStream(videoId)
