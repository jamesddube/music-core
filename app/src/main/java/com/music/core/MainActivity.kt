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

import android.content.ComponentName
import android.os.Bundle
import android.os.RemoteException
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var mediaBrowser: MediaBrowserCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MusicProvider.retrieveMediaAsync(this, object : MusicProvider.Callback {
            override fun onMusicCatalogReady(catalog: TreeMap<String, MediaMetadataCompat>) {
                println("Media catalog is ready !" + catalog.toList().toString())

                val catalogList = catalog.toList()

                listView.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1,
                    catalogList
                )

                listView.onItemClickListener = OnItemClickListener { parent, view, position, id ->

                    //Set Playing Queue
                    playingQueueMetadata = catalog

                    val selectedMediaId = catalog.toList()[position].second.description.mediaId

                    MediaControllerCompat.getMediaController(this@MainActivity)
                        .transportControls
                        .playFromMediaId(selectedMediaId, null)
                }
            }
        }).execute()


        btn_shuffle.setOnClickListener {
            MediaControllerCompat.getMediaController(this@MainActivity)
                .transportControls
                .setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }


    public override fun onStart() {
        super.onStart()
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallback, null
        )
        mediaBrowser.connect()
    }

    public override fun onStop() {
        super.onStop()
        if (mediaBrowser.isConnected) mediaBrowser.unsubscribe("root")
        mediaBrowser.disconnect()
    }


    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            try {
                val mediaController = MediaControllerCompat(
                    this@MainActivity, mediaBrowser.sessionToken
                )
                MediaControllerCompat.setMediaController(
                    this@MainActivity, mediaController
                )
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }
}
