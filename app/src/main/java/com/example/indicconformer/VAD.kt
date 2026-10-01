package com.example.indicconformer

import kotlin.math.sqrt

class VAD(
    private var speechStartThreshold: Float = 0.015f,
    private var silenceDurationMs: Long = 800L,
    private var minSpeechDurationMs: Long = 200L,
    private var maxUtteranceDurationMs: Long = 15000L
) {
    var isSpeaking = false
        private set

    private var speechStartTimeMs = 0L
    private var lastSpeechTimeMs = 0L

    fun processAudio(shortBuffer: ShortArray, readResult: Int, currentTimeMs: Long): FloatArray {
        val floatBuffer = FloatArray(readResult)
        var sumSq = 0f
        for (i in 0 until readResult) {
            val f = shortBuffer[i] / 32768.0f
            floatBuffer[i] = f
            sumSq += f * f
        }
        val rms = sqrt(sumSq / readResult)

        if (rms > speechStartThreshold) {
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = currentTimeMs
            }
            lastSpeechTimeMs = currentTimeMs
        }

        return floatBuffer
    }

    fun checkSilence(currentTimeMs: Long): Boolean {
        if (!isSpeaking) return false
        
        val duration = lastSpeechTimeMs - speechStartTimeMs
        val isSilentForTooLong = currentTimeMs - lastSpeechTimeMs > silenceDurationMs
        val isUtteranceTooLong = currentTimeMs - speechStartTimeMs > maxUtteranceDurationMs

        if (isSilentForTooLong || isUtteranceTooLong) {
            isSpeaking = false
            return duration > minSpeechDurationMs
        }
        return false
    }

    fun forceStop(): Boolean {
        if (!isSpeaking) return false
        isSpeaking = false
        val duration = lastSpeechTimeMs - speechStartTimeMs
        return duration > minSpeechDurationMs
    }
}
