package com.example.indicconformer

import android.os.SystemClock
import android.util.Log

class MetricsManager {
    var modelFileSize: Long = 0
    var modelLoadingTimeMs: Long = 0
    var timeToFirstPartialMs: Long = 0
    var speechEndToFinalLatencyMs: Long = 0
    var totalInferenceTimeMs: Long = 0
    var totalAudioDurationMs: Long = 0
    var peakRamMb: Long = 0
    var appStartupTimeMs: Long = 0

    private val runtime = Runtime.getRuntime()

    fun recordAppStartup() {
        // Assume recorded outside or track here
    }

    fun startUtterance() {
        timeToFirstPartialMs = 0
        speechEndToFinalLatencyMs = 0
        totalInferenceTimeMs = 0
        totalAudioDurationMs = 0
    }

    fun addInference(durationMs: Long, audioMs: Long) {
        totalInferenceTimeMs += durationMs
        totalAudioDurationMs += audioMs
        updatePeakRam()
    }

    fun recordFirstPartialLatency(latency: Long) {
        if (timeToFirstPartialMs == 0L) {
            timeToFirstPartialMs = latency
        }
    }

    fun getRTF(): Float {
        return if (totalAudioDurationMs > 0) {
            totalInferenceTimeMs.toFloat() / totalAudioDurationMs.toFloat()
        } else {
            0f
        }
    }

    fun updatePeakRam() {
        val usedRam = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        if (usedRam > peakRamMb) {
            peakRamMb = usedRam
        }
    }

    fun getMetricsString(): String {
        return "RTF: ${String.format("%.2f", getRTF())} | Inf: ${totalInferenceTimeMs}ms | " +
               "Audio: ${totalAudioDurationMs / 1000f}s | RAM: ${peakRamMb}MB | " +
               "1st Part: ${timeToFirstPartialMs}ms | Latency: ${speechEndToFinalLatencyMs}ms"
    }

    fun logUtteranceMetrics(text: String) {
        Log.i("MetricsManager", "Utterance: '$text'")
        Log.i("MetricsManager", getMetricsString())
    }
}
