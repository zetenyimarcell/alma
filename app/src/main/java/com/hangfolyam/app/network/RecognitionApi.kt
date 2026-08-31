package com.hangfolyam.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

object RecognitionApi {
    private val client = OkHttpClient()
    // Itt a működő API kulcsod
    private const val API_TOKEN = "3c3ef271303bbfad486351e6b66e49dd"

    suspend fun recognize(audioFile: File): RecognitionResult? = withContext(Dispatchers.IO) {
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", API_TOKEN)
                .addFormDataPart("return", "spotify")
                // Megadjuk a fájlt
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url("https://api.audd.io/")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("RecognitionApi", "Hálózati hiba: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                if (json.optString("status") != "success") {
                    Log.e("RecognitionApi", "API Hiba: ${json.optJSONObject("error")?.optString("error_message")}")
                    return@withContext null
                }
                
                val result = json.optJSONObject("result") ?: return@withContext null
                
                RecognitionResult(
                    title = result.optString("title", "Ismeretlen cím"),
                    artist = result.optString("artist", "Ismeretlen előadó"),
                    album = result.optString("album", "Ismeretlen album"),
                    spotifyUrl = result.optJSONObject("spotify")
                        ?.optJSONObject("external_urls")
                        ?.optString("spotify")
                )
            }
        } catch (e: Exception) {
            // Ha megszakad a net, ide fut be, és az app NEM omlik össze!
            Log.e("RecognitionApi", "Kivétel történt: ${e.localizedMessage}")
            null
        }
    }
}

data class RecognitionResult(
    val title: String, 
    val artist: String, 
    val album: String, 
    val spotifyUrl: String?
)
