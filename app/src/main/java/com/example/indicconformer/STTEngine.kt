package com.example.indicconformer

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

class STTEngine {
    private var recognizer: OfflineRecognizer? = null
    var isLoaded = false
        private set

    fun loadModel(modelPath: String, tokensPath: String, sampleRate: Int = 16000) {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = sampleRate,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = modelPath
                ),
                tokens = tokensPath,
                numThreads = 2,
                debug = false,
                modelType = "nemo_ctc"
            ),
            decodingMethod = "greedy_search"
        )
        recognizer = OfflineRecognizer(config = config)
        isLoaded = true
    }

    fun decodeBuffer(samples: FloatArray, sampleRate: Int = 16000): Pair<String, Long> {
        val rec = recognizer ?: return Pair("", 0L)
        val t0 = SystemClock.uptimeMillis()
        val stream = rec.createStream()
        stream.acceptWaveform(samples, sampleRate)
        rec.decode(stream)
        val t1 = SystemClock.uptimeMillis()
        val text = rec.getResult(stream).text
        stream.release()
        return Pair(text, t1 - t0)
    }

    fun unload() {
        recognizer?.release()
        recognizer = null
        isLoaded = false
    }
}
