package com.hangfolyam.app

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class PlayerViewModel : ViewModel() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: MediaController? = null
        private set

    // Pending URL: ha a UI play-et hívja, de a MediaController még nem készen áll
    private var pendingUrl: String? = null

    // Ez kapcsolódik rá a háttérben futó MusicService-re
    fun initPlayer(context: Context) {
        // Idempotens: ha már van controllerFuture vagy player, ne inicializáljuk újra
        if (controllerFuture != null || player != null) return

        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener(
            {
                try {
                    player = controllerFuture?.get()
                    // Ha volt soron álló URL, játsszuk le most
                    pendingUrl?.let { url ->
                        try {
                            val mediaItem = MediaItem.fromUri(url)
                            player?.setMediaItem(mediaItem)
                            player?.prepare()
                            player?.play()
                        } catch (e: Exception) {
                            // Hibát csak naplózzuk — UI kezelheti a lejátszás hiányát
                            e.printStackTrace()
                        } finally {
                            pendingUrl = null
                        }
                    }
                } catch (e: Exception) {
                    // Hibakezelés: ha nem jött létre a controller, töröljük a future-t és engedjük, hogy a UI újrapróbálkozzon
                    e.printStackTrace()
                    controllerFuture = null
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    // EZ INDÍTJA EL A LEJÁTSZÁST!
    // Ha a player még nem készen áll, eltároljuk a kérést (pendingUrl)
    fun playAudio(url: String) {
        player?.let {
            val mediaItem = MediaItem.fromUri(url)
            it.setMediaItem(mediaItem)
            it.prepare()
            it.play() // Lejátszás indítása
        } ?: run {
            pendingUrl = url
        }
    }

    fun pauseAudio() {
        player?.pause()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            // Release player instance, ha van
            player?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            player = null
        }

        controllerFuture?.let {
            try {
                MediaController.releaseFuture(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            controllerFuture = null
        }
    }
}
