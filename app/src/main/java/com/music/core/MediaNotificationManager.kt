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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class MediaNotificationManager(val musicService: MusicService) : BroadcastReceiver() {

    private val notificationManager: NotificationManager =
        musicService.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var isServiceStarted: Boolean = false

    private val mPlayAction: NotificationCompat.Action
    private val mPauseAction: NotificationCompat.Action
    private val mNextAction: NotificationCompat.Action
    private val mPrevAction: NotificationCompat.Action

    override fun onReceive(context: Context?, intent: Intent) {
        val action = intent.action
        when (action) {
            ACTION_PAUSE -> musicService.mediaSessionCompatCallback.onPause()
            ACTION_PLAY -> musicService.mediaSessionCompatCallback.onPlay()
            ACTION_NEXT -> musicService.mediaSessionCompatCallback.onSkipToNext()
            ACTION_PREV -> musicService.mediaSessionCompatCallback.onSkipToPrevious()
            ACTION_FAVOURITE -> musicService.mediaSessionCompatCallback.onCustomAction("thumbs_up", null)
        }
    }

    private val NotificationManager.createChannel: NotificationManager
        get() = this.apply {
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESC
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
        }


    init {

        val pkg = musicService.packageName
        val playIntent = PendingIntent.getBroadcast(
            musicService, REQUEST_CODE,
            Intent(ACTION_PLAY).setPackage(pkg),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getBroadcast(
            musicService, REQUEST_CODE,
            Intent(ACTION_PAUSE).setPackage(pkg),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getBroadcast(
            musicService, REQUEST_CODE,
            Intent(ACTION_NEXT).setPackage(pkg),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevIntent = PendingIntent.getBroadcast(
            musicService, REQUEST_CODE,
            Intent(ACTION_PREV).setPackage(pkg),
            PendingIntent.FLAG_UPDATE_CURRENT
        )


        mPlayAction = NotificationCompat.Action(
            R.drawable.ic_play_arrow_24px,
            "Play", playIntent
        )
        mPauseAction = NotificationCompat.Action(
            R.drawable.ic_pause_24px,
            "Pause", pauseIntent
        )
        mNextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next_24px,
            "Next", nextIntent
        )
        mPrevAction = NotificationCompat.Action(
            R.drawable.ic_skip_previous_24px,
            "Previous", prevIntent
        )


        notificationManager.createChannel

        // Cancel all notifications to handle the case where the Service was killed and
        // restarted by the system.
        notificationManager.cancelAll()
    }


    fun updateNotification(
        mMediaSession: MediaSessionCompat,
        currentMediaMetadata: MediaMetadataCompat
    ) {
        val playbackState = mMediaSession.controller.playbackState
        val sessionToken = mMediaSession.controller.sessionToken


        val isPlaying = playbackState.state == PlaybackStateCompat.STATE_PLAYING


        val coverUri = currentMediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
        val cover = MediaStore.Images.Media.getBitmap(musicService.contentResolver, Uri.parse(coverUri))
        mMediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover).build()
        )


        val notificationBuilder = NotificationCompat
            .Builder(musicService, CHANNEL_ID)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2, 3)
            )
            .setColorized(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(createContentIntent())
            .setContentTitle(currentMediaMetadata.description.title)
            .setContentText(currentMediaMetadata.description.subtitle)
            .setSubText(currentMediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM))
            .setLargeIcon(cover)
            .setOngoing(isPlaying)
            .setWhen(if (isPlaying) System.currentTimeMillis() - playbackState.position else 0)
            .setShowWhen(isPlaying)


        // If skip to next action is enabled
        if (playbackState.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) {
            notificationBuilder.addAction(mPrevAction)
        }
        notificationBuilder.addAction(if (isPlaying) mPauseAction else mPlayAction)
        // If skip to prev action is enabled
        if (playbackState.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) {
            notificationBuilder.addAction(mNextAction)
        }


        val favIcon = if (MusicProvider.isFavorite(currentMediaMetadata.description.mediaId)) R.drawable.ic_star_24px
        else R.drawable.ic_star_border_24px
        notificationBuilder.addAction(
            NotificationCompat.Action(
                favIcon,
                "Favourite", PendingIntent.getBroadcast(
                    musicService, REQUEST_CODE,
                    Intent(ACTION_FAVOURITE).setPackage(musicService.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        )


        val notification = notificationBuilder.build()




        if (isPlaying && !isServiceStarted) {
            musicService.startService(Intent(musicService.applicationContext, MusicService::class.java))
            musicService.startForeground(NOTIFICATION_ID, notification)
            musicService.registerReceiver(this, IntentFilter().apply {
                addAction(ACTION_NEXT)
                addAction(ACTION_PAUSE)
                addAction(ACTION_PLAY)
                addAction(ACTION_PREV)
                addAction(ACTION_FAVOURITE)
            })
            isServiceStarted = true
        } else {
            if (!isPlaying) {
                musicService.stopForeground(false)
                isServiceStarted = false
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }


    internal fun stopNotification() {
        musicService.stopForeground(true)
        try {
            musicService.unregisterReceiver(this)
        } catch (ex: Exception) {
            // ignore receiver not registered
        }

        musicService.stopSelf()
    }


    private fun createContentIntent(): PendingIntent {
        val openUI = Intent(musicService, MainActivity::class.java)
        openUI.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            musicService, REQUEST_CODE, openUI, PendingIntent.FLAG_CANCEL_CURRENT
        )
    }


    companion object {
        private const val REQUEST_CODE = 100
        private const val NOTIFICATION_ID = 412
        private const val CHANNEL_ID = "media_playback_channel"
        private const val CHANNEL_NAME = "Media playback"
        private const val CHANNEL_DESC = "Media playback controls"

        private const val ACTION_PAUSE = "com.example.android.musicplayercodelab.pause"
        private const val ACTION_PLAY = "com.example.android.musicplayercodelab.play"
        private const val ACTION_NEXT = "com.example.android.musicplayercodelab.next"
        private const val ACTION_PREV = "com.example.android.musicplayercodelab.prev"
        private const val ACTION_FAVOURITE = "com.example.android.musicplayercodelab.favourite"
    }
}