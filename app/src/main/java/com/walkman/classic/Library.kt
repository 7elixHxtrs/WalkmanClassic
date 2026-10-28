package com.walkman.classic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Song(val id: Long, val title: String, val artist: String, val album: String,
                val albumId: Long, val duration: Long, val uri: Uri) {
    fun item(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString()).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setArtworkUri(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))
            .build())
        .build()
}

fun fmt(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val M = MediaStore.Audio.Media
    val proj = arrayOf(M._ID, M.TITLE, M.ARTIST, M.ALBUM, M.ALBUM_ID, M.DURATION)
    ctx.contentResolver.query(M.EXTERNAL_CONTENT_URI, proj,
        "${M.IS_MUSIC} != 0 AND ${M.DURATION} > 30000", null, "${M.TITLE} COLLATE NOCASE")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Song(id, c.getString(1) ?: "Desconocido",
                c.getString(2)?.takeIf { it != "<unknown>" } ?: "Artista desconocido",
                c.getString(3) ?: "", c.getLong(4), c.getLong(5),
                ContentUris.withAppendedId(M.EXTERNAL_CONTENT_URI, id))
        }
    }
    return out
}
