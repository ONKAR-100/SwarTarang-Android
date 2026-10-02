package com.example.indicconformer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList

private const val TAG = "AudioPlaybackManager"
private const val PREFS_NAME = "audio_playback_prefs"
private const val KEY_AUTO_PLAY = "key_auto_play_received_messages"

data class PlaybackItem(
    val messageId: String,
    val text: String,
    val languageCode: String,
    val priority: MessagePriority = MessagePriority.NORMAL
)

class AudioPlaybackManager(
    private val context: Context,
    val ttsManager: OfflineTTSManager,
    private val scope: CoroutineScope
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isAutoPlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PLAY, true))
    val isAutoPlayEnabled: StateFlow<Boolean> = _isAutoPlayEnabled.asStateFlow()

    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId.asStateFlow()

    private val _playbackStates = MutableStateFlow<Map<String, PlaybackState>>(emptyMap())
    val playbackStates: StateFlow<Map<String, PlaybackState>> = _playbackStates.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val queue = LinkedList<PlaybackItem>()
    private var currentItem: PlaybackItem? = null

    private val audioFocusManager: AudioFocusManager = AudioFocusManager(
        context = context,
        onFocusLost = { isTransient ->
            if (currentItem?.priority == MessagePriority.ALERT) {
                Log.w(TAG, "Audio focus lost signal ignored: Active EMERGENCY ALERT is non-interruptible!")
            } else {
                Log.d(TAG, "Audio focus lost (transient=$isTransient). Stopping current playback.")
                scope.launch(Dispatchers.Main) {
                    stopCurrent()
                }
            }
        },
        onFocusGained = {
            Log.d(TAG, "Audio focus regained.")
        }
    )

    fun setAutoPlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PLAY, enabled).apply()
        _isAutoPlayEnabled.value = enabled
        Log.d(TAG, "Auto-play set to: $enabled")
    }

    fun getPlaybackState(messageId: String): PlaybackState {
        return _playbackStates.value[messageId] ?: PlaybackState.IDLE
    }

    private fun updateState(messageId: String, state: PlaybackState) {
        val current = _playbackStates.value.toMutableMap()
        current[messageId] = state
        _playbackStates.value = current
    }

    /**
     * Enqueues an incoming received message.
     * If autoPlay is enabled, plays or queues it.
     */
    @Synchronized
    fun onMessageReceived(
        messageId: String,
        text: String,
        languageCode: String,
        priority: MessagePriority = MessagePriority.NORMAL
    ) {
        if (text.isBlank()) return

        if (!_isAutoPlayEnabled.value && priority != MessagePriority.ALERT) {
            Log.d(TAG, "Auto-play is OFF; message $messageId not queued automatically.")
            updateState(messageId, PlaybackState.IDLE)
            return
        }

        val item = PlaybackItem(messageId, text, languageCode, priority)

        // Emergency Alert Priority Handling
        if (priority == MessagePriority.ALERT) {
            Log.w(TAG, "EMERGENCY ALERT received ($messageId): interrupting normal queue!")
            // Stop current playback if it's normal priority
            if (currentItem != null && currentItem?.priority != MessagePriority.ALERT) {
                val interrupted = currentItem
                ttsManager.stop()
                interrupted?.let {
                    updateState(it.messageId, PlaybackState.QUEUED)
                    queue.add(0, it)
                }
                currentItem = null
            }
            queue.add(0, item)
            updateState(messageId, PlaybackState.QUEUED)
            _queueSize.value = queue.size
            processNext()
            return
        }

        // Normal / High message
        if (currentItem == null) {
            queue.add(item)
            updateState(messageId, PlaybackState.QUEUED)
            _queueSize.value = queue.size
            processNext()
        } else {
            queue.add(item)
            updateState(messageId, PlaybackState.QUEUED)
            _queueSize.value = queue.size
            Log.d(TAG, "Message $messageId queued behind current playback. Queue size: ${queue.size}")
        }
    }

    /**
     * Manually requested playback for a specific voice note card.
     */
    @Synchronized
    fun play(
        messageId: String,
        text: String,
        languageCode: String,
        priority: MessagePriority = MessagePriority.NORMAL
    ) {
        if (currentItem?.messageId == messageId && getPlaybackState(messageId) == PlaybackState.PLAYING) {
            // Already playing
            return
        }

        // Stop current if playing something else
        stopCurrent()

        val item = PlaybackItem(messageId, text, languageCode, priority)
        queue.add(0, item)
        _queueSize.value = queue.size
        updateState(messageId, PlaybackState.QUEUED)
        processNext()
    }

    /**
     * Replays a message from the beginning.
     */
    fun replay(messageId: String, text: String, languageCode: String) {
        play(messageId, text, languageCode)
    }

    /**
     * Pauses/stops currently active playback.
     */
    @Synchronized
    fun pause() {
        if (currentItem?.priority == MessagePriority.ALERT) {
            Log.w(TAG, "Cannot pause active EMERGENCY ALERT: Non-interruptible high priority!")
            return
        }
        currentItem?.let { item ->
            ttsManager.stop()
            audioFocusManager.abandonFocus()
            updateState(item.messageId, PlaybackState.PAUSED)
            _currentlyPlayingId.value = null
            currentItem = null
        }
    }

    /**
     * Stops current playback without clearing rest of queue.
     */
    @Synchronized
    fun stopCurrent() {
        currentItem?.let { item ->
            ttsManager.stop()
            audioFocusManager.abandonFocus()
            updateState(item.messageId, PlaybackState.IDLE)
            _currentlyPlayingId.value = null
            currentItem = null
        }
    }

    /**
     * Clears all pending messages from the queue and halts current playback.
     */
    @Synchronized
    fun clearQueue() {
        stopCurrent()
        while (queue.isNotEmpty()) {
            val item = queue.poll()
            item?.let { updateState(it.messageId, PlaybackState.IDLE) }
        }
        _queueSize.value = 0
        audioFocusManager.abandonFocus()
        Log.d(TAG, "Playback queue cleared")
    }

    /**
     * Processes the next item in the playback queue sequentially.
     */
    @Synchronized
    private fun processNext() {
        if (currentItem != null) {
            Log.d(TAG, "processNext called but item ${currentItem?.messageId} is already playing.")
            return
        }

        if (queue.isEmpty()) {
            _currentlyPlayingId.value = null
            _queueSize.value = 0
            audioFocusManager.abandonFocus()
            Log.d(TAG, "Queue is empty. Audio playback idle.")
            return
        }

        val nextItem = queue.poll() ?: return
        _queueSize.value = queue.size
        currentItem = nextItem
        val msgId = nextItem.messageId
        _currentlyPlayingId.value = msgId

        // Request audio focus
        val isAlert = nextItem.priority == MessagePriority.ALERT
        val focusGranted = audioFocusManager.requestFocus(isAlert)
        if (!focusGranted) {
            Log.w(TAG, "Audio focus request rejected for message $msgId. Marking failed and skipping.")
            updateState(msgId, PlaybackState.FAILED)
            currentItem = null
            _currentlyPlayingId.value = null
            processNext()
            return
        }

        updateState(msgId, PlaybackState.PLAYING)
        Log.d(TAG, "Starting offline TTS for message $msgId in ${nextItem.languageCode}: \"${nextItem.text}\" (isAlert=$isAlert)")

        val started = ttsManager.speak(
            text = nextItem.text,
            languageCode = nextItem.languageCode,
            utteranceId = msgId,
            isAlert = isAlert,
            onStart = {
                scope.launch(Dispatchers.Main) {
                    updateState(msgId, PlaybackState.PLAYING)
                    _currentlyPlayingId.value = msgId
                }
            },
            onDone = {
                scope.launch(Dispatchers.Main) {
                    Log.d(TAG, "TTS playback finished for message $msgId")
                    updateState(msgId, PlaybackState.COMPLETED)
                    if (currentItem?.messageId == msgId) {
                        currentItem = null
                    }
                    processNext()
                }
            },
            onError = { error ->
                scope.launch(Dispatchers.Main) {
                    Log.e(TAG, "TTS playback error for message $msgId: $error")
                    updateState(msgId, PlaybackState.FAILED)
                    if (currentItem?.messageId == msgId) {
                        currentItem = null
                    }
                    processNext()
                }
            }
        )

        if (!started) {
            Log.e(TAG, "ttsManager.speak returned false for message $msgId")
            updateState(msgId, PlaybackState.FAILED)
            currentItem = null
            _currentlyPlayingId.value = null
            processNext()
        }
    }

    /**
     * Clean shutdown on Activity destroy.
     */
    fun release() {
        clearQueue()
        ttsManager.shutdown()
        Log.d(TAG, "AudioPlaybackManager released")
    }
}
