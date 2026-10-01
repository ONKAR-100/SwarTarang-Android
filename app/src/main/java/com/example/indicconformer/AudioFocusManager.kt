package com.example.indicconformer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

private const val TAG = "AudioFocusManager"

class AudioFocusManager(
    private val context: Context,
    private val onFocusLost: (isTransient: Boolean) -> Unit,
    private val onFocusGained: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    // Alert Volume Override & Non-Interruptibility tracking
    private var isAlertActive = false
    private var originalMusicVolume: Int? = null
    private var originalAlarmVolume: Int? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange (isAlertActive=$isAlertActive)")
        
        // Non-interruptible: If an emergency alert is actively playing, ignore third-party audio interruptions!
        if (isAlertActive) {
            Log.w(TAG, "Audio focus changed ($focusChange) during EMERGENCY ALERT — Non-interruptible: ignoring interruption!")
            return@OnAudioFocusChangeListener
        }

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                onFocusLost(false) // Permanent loss, stop playback
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                onFocusLost(true) // Transient loss (e.g. phone call notification), pause/stop safely
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onFocusGained()
            }
        }
    }

    /**
     * Checks if an emergency alert is currently playing in non-interruptible mode.
     */
    fun isAlertActive(): Boolean = isAlertActive

    /**
     * Requests audio focus prior to speech synthesis/playback.
     * @param isAlert If true, requests exclusive transient focus for emergency alert, overrides volume to maximum, and locks non-interruptibility.
     */
    fun requestFocus(isAlert: Boolean = false): Boolean {
        val am = audioManager ?: return false
        if (hasFocus && (!isAlert || isAlertActive)) return true

        val focusGain = if (isAlert) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        if (isAlert) {
            isAlertActive = true
            overrideVolumeToMaximum(am)
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val usage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                if (isAlert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC,
                focusGain
            )
        }

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d(TAG, "Audio focus requested (isAlert=$isAlert). Granted=$hasFocus")
        return hasFocus
    }

    /**
     * Overrides system stream volumes to MAX for high-priority emergency alerts, caching original volumes.
     */
    private fun overrideVolumeToMaximum(am: AudioManager) {
        try {
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (originalMusicVolume == null) originalMusicVolume = currMusic

            val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (originalAlarmVolume == null) originalAlarmVolume = currAlarm

            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            Log.d(TAG, "Emergency Alert: Volume overridden to MAX (Music: $currMusic->$maxMusic, Alarm: $currAlarm->$maxAlarm)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not override volume to max for alert: ${e.message}")
        }
    }

    /**
     * Restores original volume levels if they were overridden for an emergency alert.
     */
    private fun restoreVolumeLevels(am: AudioManager) {
        try {
            originalMusicVolume?.let { orig ->
                am.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
                Log.d(TAG, "Emergency Alert concluded: Restored Music volume to $orig")
            }
            originalAlarmVolume?.let { orig ->
                am.setStreamVolume(AudioManager.STREAM_ALARM, orig, 0)
                Log.d(TAG, "Emergency Alert concluded: Restored Alarm volume to $orig")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore volume levels: ${e.message}")
        } finally {
            originalMusicVolume = null
            originalAlarmVolume = null
            isAlertActive = false
        }
    }

    /**
     * Abandons audio focus cleanly once playback completes or stops.
     */
    fun abandonFocus() {
        val am = audioManager ?: return
        if (isAlertActive) {
            restoreVolumeLevels(am)
        }

        if (!hasFocus && focusRequest == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let {
                    am.abandonAudioFocusRequest(it)
                    focusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while abandoning audio focus: ${e.message}", e)
        } finally {
            hasFocus = false
            Log.d(TAG, "Audio focus abandoned")
        }
    }
}
