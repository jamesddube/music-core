/*
 * Copyright (C) 2019  Kavan Mevada.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.music.core

import android.content.Context
import android.os.AsyncTask
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import java.lang.ref.WeakReference
import java.util.*

object MusicProvider {

    private val mFavoriteTracks = hashSetOf<String>()

    fun isFavorite(musicId: String?) = mFavoriteTracks.contains(musicId)
    fun setFavorite(mediaId: String?, favorite: Boolean) {
        if (mediaId != null && favorite) {
            mFavoriteTracks.add(mediaId)
            println("added to favourite")
        } else {
            mFavoriteTracks.remove(mediaId)
            println("remove from to favourite")
        }
    }

    // Interfaces
    interface Callback {
        fun onMusicCatalogReady(catalog: TreeMap<String, MediaMetadataCompat>)
    }


    class retrieveMediaAsync(context: Context /* TODO ("Fix this memory leak") */, val callback: Callback) :
        AsyncTask<Void, Void, TreeMap<String, MediaMetadataCompat>>() {
        val contextReference = WeakReference<Context>(context)
        val retrievedList = TreeMap<String, MediaMetadataCompat>()
        override fun doInBackground(vararg params: Void?): TreeMap<String, MediaMetadataCompat> {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
            )

            val cursor = contextReference.get()?.contentResolver?.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null, null
            )

            var index = 0L
            cursor?.let {
                while (cursor.moveToNext()) {
                    val sArtworkData = "content://media/external/audio/albumart/${cursor.getString(7)}"

                    val mediaMetadataCompat = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, cursor.getString(0))
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, cursor.getString(1))
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, cursor.getString(2))
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, cursor.getString(3))
                        .putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Jazz")
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, cursor.getLong(4) * 1000)
                        .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, cursor.getLong(5))
                        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, cursor.getString(6))
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, sArtworkData)
                        .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, sArtworkData)
                        .build()

                    retrievedList[cursor.getString(0)] = mediaMetadataCompat
                }
            }
            cursor?.close()
            return retrievedList
        }

        override fun onPostExecute(result: TreeMap<String, MediaMetadataCompat>) {
            super.onPostExecute(result)
            //Update UI
            callback.onMusicCatalogReady(result)
        }
    }


}