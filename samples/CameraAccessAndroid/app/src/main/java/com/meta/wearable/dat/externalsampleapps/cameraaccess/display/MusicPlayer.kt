/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.display

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager as SystemAudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays a music or podcast URL (e.g. an Eleven Labs track returned by OpenClaw) via
 * [MediaPlayer]. The DAT SDK has no glasses audio-playback API, so playback uses
 * Android audio, which routes to Ray-Ban Meta glasses over Bluetooth when connected.
 */
object MusicPlayer {
    private const val TAG = "MusicPlayer"

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private var appContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isPlaying(url: String): Boolean = _isPlaying.value && _currentUrl.value == url

    fun play(url: String) {
        val context = appContext ?: return
        if (_currentUrl.value != url || mediaPlayer == null) {
            releasePlayer()
            _currentUrl.value = url
            mediaPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    setOnPreparedListener {
                        requestAudioFocus(context)
                        start()
                        _isPlaying.value = true
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        abandonAudioFocus(context)
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "Playback error what=$what extra=$extra")
                        _isPlaying.value = false
                        abandonAudioFocus(context)
                        true
                    }
                    try {
                        setDataSource(url)
                        prepareAsync()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load $url", e)
                        releasePlayer()
                    }
                }
        } else {
            requestAudioFocus(context)
            mediaPlayer?.start()
            _isPlaying.value = true
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        appContext?.let { abandonAudioFocus(it) }
    }

    fun toggle(url: String) {
        if (isPlaying(url)) pause() else play(url)
    }

    fun stop() {
        releasePlayer()
        _isPlaying.value = false
        _currentUrl.value = null
    }

    private fun releasePlayer() {
        appContext?.let { abandonAudioFocus(it) }
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun requestAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager
        val request =
            AudioFocusRequest.Builder(SystemAudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus(context: Context) {
        val request = audioFocusRequest ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager
        audioManager.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }
}
