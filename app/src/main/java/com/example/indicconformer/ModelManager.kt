package com.example.indicconformer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ModelManager"

class ModelManager(private val context: Context) {

    /**
     * Backward-compatible helper to extract bundled Marathi model from APK assets.
     */
    fun prepareModelFiles(modelName: String, tokensName: String, expectedSize: Long): Pair<File, File> {
        val modelDir = File(context.filesDir, "model")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        val modelFile = File(modelDir, modelName)
        val tokensFile = File(modelDir, tokensName)

        if (!modelFile.exists() || modelFile.length() != expectedSize) {
            context.assets.open("model/$modelName").use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        }

        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            context.assets.open("model/$tokensName").use { input ->
                FileOutputStream(tokensFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        }

        return Pair(modelFile, tokensFile)
    }

    fun getActiveLanguage(): Language = ModelRegistry.getActiveLanguage(context)

    fun setActiveLanguage(language: Language) = ModelRegistry.setActiveLanguage(context, language)

    fun isLanguageInstalled(langCode: String): Boolean = ModelRegistry.isModelInstalled(context, langCode)

    fun isFirstLaunch(): Boolean = ModelRegistry.isFirstLaunch(context)

    fun setFirstLaunchDone() = ModelRegistry.setFirstLaunchDone(context)

    /**
     * Dynamically loads a model into STTEngine.
     * Safely unloads any previous model from RAM first, ensuring strict 1-model RAM isolation.
     */
    fun loadLanguageModel(langCode: String, sttEngine: STTEngine): Boolean {
        Log.d(TAG, "Request to switch active STT model to $langCode")
        
        // 1. Fully release previous model from RAM
        sttEngine.unload()

        return try {
            val (modelFile, tokensFile) = if (langCode.equals("mr", ignoreCase = true)) {
                prepareModelFiles("model.int8.onnx", "tokens.txt", 197595593L)
            } else {
                ModelRegistry.getModelFiles(context, langCode)
                    ?: throw IllegalStateException("Model for $langCode is not installed")
            }

            Log.d(TAG, "Loading $langCode model from: ${modelFile.absolutePath}")
            sttEngine.loadModel(modelFile.absolutePath, tokensFile.absolutePath)
            setActiveLanguage(Language.fromCode(langCode))
            Log.d(TAG, "Successfully loaded $langCode STT model into memory")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model for $langCode: ${e.message}", e)
            // Fallback to verified bundled Marathi model
            try {
                Log.w(TAG, "Falling back to bundled Marathi model...")
                val (modelFile, tokensFile) = prepareModelFiles("model.int8.onnx", "tokens.txt", 197475306L)
                sttEngine.loadModel(modelFile.absolutePath, tokensFile.absolutePath)
                setActiveLanguage(Language.MARATHI)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Critical: Fallback to Marathi also failed: ${fallbackError.message}")
            }
            false
        }
    }
}
