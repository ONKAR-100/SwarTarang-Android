package com.example.indicconformer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val TAG = "OfflineTTSManager"

enum class TTSResult {
    AVAILABLE,
    NOT_AVAILABLE,
    ERROR
}

sealed interface TTSStatus {
    object Uninitialized : TTSStatus
    object Initializing : TTSStatus
    data class Ready(val availableLocales: Set<String>) : TTSStatus
    data class Error(val message: String) : TTSStatus
}

/**
 * 100% Offline Text-to-Speech manager utilizing Android's local TTS engine (e.g. Google TTS).
 * Never uses cloud APIs or internet connections.
 * Completely fault-tolerant: failure to initialize or synthesize will never crash the app.
 */
class OfflineTTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val _status = MutableStateFlow<TTSStatus>(TTSStatus.Uninitialized)
    val status: StateFlow<TTSStatus> = _status.asStateFlow()

    private val callbackMap = mutableMapOf<String, UtteranceCallbacks>()
    private var activeUtteranceId: String? = null

    data class UtteranceCallbacks(
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit
    )

    init {
        initialize()
    }

    @Synchronized
    fun initialize() {
        if (_status.value is TTSStatus.Initializing || _status.value is TTSStatus.Ready) return
        _status.value = TTSStatus.Initializing
        try {
            Log.d(TAG, "Initializing Android local TTS engine...")
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech: ${e.message}", e)
            _status.value = TTSStatus.Error("Failed to instantiate TTS: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                setupProgressListener(engine)
                val supportedLocales = mutableSetOf<String>()
                listOf("mr", "hi", "en", "gu", "kn", "ml", "ta", "te", "bn", "or").forEach { code ->
                    val locale = getLocaleForCode(code)
                    val avail = engine.isLanguageAvailable(locale)
                    if (avail >= TextToSpeech.LANG_AVAILABLE) {
                        supportedLocales.add(code)
                    }
                }
                Log.d(TAG, "TTS initialized successfully. Verified local languages: $supportedLocales")
                _status.value = TTSStatus.Ready(supportedLocales)
            } else {
                _status.value = TTSStatus.Error("TTS instance was null onInit")
            }
        } else {
            Log.w(TAG, "TextToSpeech init returned error code: $status")
            _status.value = TTSStatus.Error("TTS initialization returned code $status")
        }
    }

    private fun setupProgressListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart: $utteranceId")
                utteranceId?.let { id ->
                    callbackMap[id]?.onStart?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone: $utteranceId")
                utteranceId?.let { id ->
                    callbackMap.remove(id)?.onDone?.invoke()
                    if (activeUtteranceId == id) {
                        activeUtteranceId = null
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS onError: $utteranceId code=$errorCode")
                utteranceId?.let { id ->
                    callbackMap.remove(id)?.onError?.invoke("TTS error code: $errorCode")
                    if (activeUtteranceId == id) {
                        activeUtteranceId = null
                    }
                }
            }
        })
    }

    /**
     * Resolves language code to appropriate Locale.
     */
    fun getLocaleForCode(languageCode: String): Locale {
        return when (languageCode.lowercase()) {
            "mr" -> Locale("mr", "IN")
            "hi" -> Locale("hi", "IN")
            "en" -> Locale("en", "IN")
            "gu" -> Locale("gu", "IN")
            "kn" -> Locale("kn", "IN")
            "ml" -> Locale("ml", "IN")
            "ta" -> Locale("ta", "IN")
            "te" -> Locale("te", "IN")
            "bn" -> Locale("bn", "IN")
            "or" -> Locale("or", "IN")
            else -> Locale(languageCode, "IN")
        }
    }

    /**
     * Sets TTS language and checks availability.
     */
    fun setLanguage(languageCode: String): TTSResult {
        val engine = tts ?: return TTSResult.ERROR
        return try {
            val locale = getLocaleForCode(languageCode)
            val result = engine.isLanguageAvailable(locale)
            when {
                result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA -> {
                    Log.w(TAG, "TTS language $languageCode ($locale) not available: code=$result")
                    TTSResult.NOT_AVAILABLE
                }
                result >= TextToSpeech.LANG_AVAILABLE -> {
                    engine.language = locale
                    TTSResult.AVAILABLE
                }
                else -> TTSResult.NOT_AVAILABLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS language to $languageCode: ${e.message}", e)
            TTSResult.ERROR
        }
    }

    /**
     * Synthesizes and speaks text completely offline using Android's local TTS engine.
     */
    @Synchronized
    fun speak(
        text: String,
        languageCode: String,
        utteranceId: String,
        isAlert: Boolean = false,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val engine = tts
        if (engine == null) {
            onError("TTS engine not initialized")
            return false
        }

        return try {
            // Apply language
            val locale = getLocaleForCode(languageCode)
            val langResult = engine.isLanguageAvailable(locale)
            if (langResult >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = locale
            } else {
                Log.w(TAG, "Locale $locale not fully supported (code=$langResult). Falling back to current TTS language.")
            }

            // If alert, route directly to USAGE_ALARM on supported Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }

            callbackMap[utteranceId] = UtteranceCallbacks(onStart, onDone, onError)
            activeUtteranceId = utteranceId

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) // Maximum TTS synthesis volume
                if (isAlert) {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                }
            }

            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                true
            } else {
                callbackMap.remove(utteranceId)
                activeUtteranceId = null
                onError("TTS speak call returned error code: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during TTS speak: ${e.message}", e)
            callbackMap.remove(utteranceId)
            activeUtteranceId = null
            onError(e.message ?: "Unknown TTS exception")
            false
        }
    }

    /**
     * Stops current playback safely.
     */
    @Synchronized
    fun stop() {
        try {
            activeUtteranceId?.let { id ->
                callbackMap.remove(id)?.onDone?.invoke()
                activeUtteranceId = null
            }
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Exception while stopping TTS: ${e.message}", e)
        }
    }

    /**
     * Releases all TTS native and Android resources.
     */
    @Synchronized
    fun shutdown() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            _status.value = TTSStatus.Uninitialized
            callbackMap.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during TTS shutdown: ${e.message}", e)
        }
    }
}
