package com.example.indicconformer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCapture(private val sampleRate: Int = 16000) {
    private var audioRecord: AudioRecord? = null
    var isRecording = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error creating AudioRecord", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCapture", "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            return true
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error starting recording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    fun read(shortBuffer: ShortArray): Int {
        return audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
    }

    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping recording", e)
        }
        audioRecord?.release()
        audioRecord = null
    }
}
