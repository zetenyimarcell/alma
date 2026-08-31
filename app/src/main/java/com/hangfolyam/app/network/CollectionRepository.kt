package com.hangfolyam.app.network

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class CollectionRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val userId: String
        get() = auth.currentUser?.uid ?: "test_user"

    suspend fun getAll(): List<LiveSong> {
        return try {
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("collection")
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val title = doc.getString("title") ?: ""
                val artist = doc.getString("artist") ?: ""
                val coverUrl = doc.getString("coverUrl") ?: ""
                val streamUrl = doc.getString("streamUrl") ?: ""
                LiveSong(id, title, artist, coverUrl, streamUrl)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun add(song: LiveSong) {
        try {
            firestore.collection("users")
                .document(userId)
                .collection("collection")
                .document(song.id)
                .set(song)
                .await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
