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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import java.io.IOException
import java.util.*


// Internal Global Variables
internal lateinit var playingQueueMetadata: TreeMap<String, MediaMetadataCompat> // <uniqueId, Metadata>
internal var currentQueueIndex: Int = 0

class MusicService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var mediaPlayer: MediaPlayer


    private var playbackState = PlaybackStateCompat.STATE_NONE

    private lateinit var mediaNotificationManager: MediaNotification

    // Create Playing Queue from playingQueueMetadata
    private val playingQueue: List<MediaSessionCompat.QueueItem>
        get() {
            if (!::playingQueueMetadata.isInitialized || playingQueueMetadata.isEmpty()) // Error Handler
                throw Error("playingQueueMetadata is empty!")

            val queue = mutableListOf<MediaSessionCompat.QueueItem>()
            playingQueueMetadata.values.forEachIndexed { index, metadata ->
                val queueItem = MediaSessionCompat
                    .QueueItem(metadata.description, index.toLong())
                queue.add(queueItem)
            }
            return queue
        }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Start a new MediaSession.
        mediaSession = MediaSessionCompat(this, "MediaPlaybackService")
        mediaSession.setCallback(mediaSessionCompatCallback)
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        sessionToken = mediaSession.sessionToken

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mediaNotificationManager = MediaNotification(this)
    }

    override fun onDestroy() {
        mediaSessionCompatCallback.onStop()
        mediaNotificationManager.stopNotification()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSessionCompatCallback.onStop()
        mediaNotificationManager.stopNotification()
    }

    internal val mediaSessionCompatCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playFromMediaId(mediaId)
        override fun onPlay() = tryToGetAudioFocus()
        override fun onPause() {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                audioManager.abandonAudioFocus(afChangeListener)
            }
            playbackState = PlaybackStateCompat.STATE_PAUSED
            updatePlaybackState()
        }

        override fun onStop() {
            playbackState = PlaybackStateCompat.STATE_STOPPED
            // Give up Audio focus
            audioManager.abandonAudioFocus(afChangeListener)

            // Relax all resources
            if (::mediaPlayer.isInitialized) mediaPlayer.release()
        }

        override fun onSkipToNext() {
            currentQueueIndex =
                if (currentQueueIndex + 1 >= mediaSession.controller.queue.size) 0
                else currentQueueIndex + 1

            playbackState = PlaybackStateCompat.STATE_PLAYING
            playCurrentSong()
        }

        override fun onSkipToPrevious() {
            currentQueueIndex = if (currentQueueIndex - 1 < 0) 0
            else currentQueueIndex - 1

            playbackState = PlaybackStateCompat.STATE_PLAYING
            playCurrentSong()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            super.onCustomAction(action, extras)
            if (action == "thumbs_up") {
                val mediaId = mediaSession.controller.queue[currentQueueIndex].description.mediaId
                MusicProvider.setFavorite(mediaId, !MusicProvider.isFavorite(mediaId))
                updatePlaybackState()
            } else if (action == "toggle_play") {
                if (mediaPlayer.isPlaying) onPause() else onPlay()
            }
        }

        override fun onSetRepeatMode(repeatMode: Int) {
            super.onSetRepeatMode(repeatMode)
            mediaSession.setRepeatMode(repeatMode)
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            super.onSetShuffleMode(shuffleMode)
            mediaSession.setShuffleMode(shuffleMode)
            if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL) {
                mediaSession.setQueue(playingQueue.shuffled())
            } else if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                mediaSession.setQueue(playingQueue)
            }
            mediaSession.setQueueTitle("Playing Queue") // TODO ("Put String into Res XML")
            currentQueueIndex = 0
            playbackState = PlaybackStateCompat.STATE_PLAYING
            playCurrentSong()
        }
    }

    private fun getMusicIndexFromMediaIdOnQueue(mediaId: String?): Int {
        var index = 0
        if (!mediaSession.controller.queue.isNullOrEmpty()) {
            mediaSession.controller.queue.forEachIndexed { pos, queueItem ->
                if (queueItem.description.mediaId == mediaId) index = pos
            }
        }
        return index
    }


    // MediaPlayer Controller Functions
    fun playFromMediaId(mediaId: String) {
        mediaSession.setQueue(playingQueue)
        mediaSession.setQueueTitle("Playing Queue") // TODO ("Put String into Res XML")

        if (!mediaSession.controller.queue.isNullOrEmpty()) {
            currentQueueIndex = getMusicIndexFromMediaIdOnQueue(mediaId)

            // play the music
            playCurrentSong()
        }
    }

    /**
     * Handle a request to play music
     */
    private fun playCurrentSong() {
        try {
            createMediaPlayerIfNeeded()

            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mediaPlayer.setDataSource(
                applicationContext,
                mediaSession.controller.queue[currentQueueIndex].description.mediaUri!!
            )

            mediaPlayer.prepareAsync()

        } catch (ex: IOException) {
            println("IOException playing song : $ex")
        }

    }


    private fun createMediaPlayerIfNeeded() {
        // Check MediaPlayer is initialized or not
        if (!::mediaPlayer.isInitialized) {
            mediaPlayer = MediaPlayer()
            // we want the media player to notify us when it's ready preparing,
            // and when it's done playing:
            mediaPlayer.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer.setOnCompletionListener(completionListener)
            mediaPlayer.setOnPreparedListener(preparedListener)
        } else {
            mediaPlayer.reset()
        }
    }


    private fun tryToGetAudioFocus() {
        updatePlaybackState()
        val mAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val focusRequest = audioManager
            .requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(mAudioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(afChangeListener)
                    .build()
            )

        if (focusRequest == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Start playback
            mediaPlayer.start()
            playbackState = PlaybackStateCompat.STATE_PLAYING
            updatePlaybackState()
        }
    }


    private val completionListener = MediaPlayer.OnCompletionListener {
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        if (!playingQueueMetadata.isEmpty()) {

            when (mediaSession.controller.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> {
                    playbackState = PlaybackStateCompat.STATE_PLAYING
                    playCurrentSong()
                }
                PlaybackStateCompat.REPEAT_MODE_ALL -> mediaSessionCompatCallback.onSkipToNext()
                PlaybackStateCompat.REPEAT_MODE_NONE -> if (currentQueueIndex < mediaSession.controller.queue.size - 1) {
                    mediaSessionCompatCallback.onSkipToNext()
                } else mediaSessionCompatCallback.onStop()
            }

        } else {
            mediaSessionCompatCallback.onStop()
        }
    }

    private val afChangeListener = (AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer.setVolume(1f, 1f)
                mediaPlayer.start()
                playbackState = PlaybackStateCompat.STATE_PLAYING
                updatePlaybackState()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // We have lost focus. If we can duck (low playback volume), we can keep playing.
                // Otherwise, we need to pause the playback.
                mediaPlayer.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                mediaPlayer.pause()
                playbackState = PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState()
            }
        }
    })

    private fun updatePlaybackState() {
        val currentMediaMetadata =
            playingQueueMetadata[mediaSession.controller.queue[currentQueueIndex].description.mediaId]!!

        val stateBuilder = PlaybackStateCompat.Builder().setActions(availableActions)

        val state = stateBuilder
            .setState(playbackState, mediaPlayer.currentPosition.toLong(), 1.0f, SystemClock.elapsedRealtime())
            .build()

        mediaSession.setPlaybackState(state)

        mediaNotificationManager.updateNotification(mediaSession, currentMediaMetadata)
    }


    private val availableActions: Long
        get() {
            var actions = (PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                    or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            if (mediaPlayer.isPlaying) actions = actions or PlaybackStateCompat.ACTION_PAUSE
            return actions
        }


    // Listeners
    private val preparedListener = MediaPlayer.OnPreparedListener {
        tryToGetAudioFocus()
        if (!mediaSession.isActive) mediaSession.isActive = true
    }
}