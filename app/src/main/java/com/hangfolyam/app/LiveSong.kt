package com.hangfolyam.app.network

// Adatmodell egy zeneszámhoz
data class LiveSong(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val coverUrl: String = "",
    val streamUrl: String = "",
    val durationMs: Long = 0L
)
