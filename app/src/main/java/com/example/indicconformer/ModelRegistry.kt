package com.example.indicconformer

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Metadata for on-demand Hugging Face model distribution.
 */
data class ModelMetadata(
    val languageCode: String,
    val modelName: String,
    val version: String = "1.0",
    val source: String = "Hugging Face (AI4Bharat IndicConformer)",
    val modelUrl: String,
    val tokensUrl: String,
    val modelFileName: String = "model.int8.onnx",
    val tokensFileName: String = "tokens.txt",
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val license: String = "MIT",
    val runtime: String = "sherpa-onnx"
) {
    val formattedSize: String get() = "${expectedSizeBytes / (1024 * 1024)} MB"
}

object ModelRegistry {
    private const val PREFS_NAME = "marathi_voice_prefs"
    private const val KEY_ACTIVE_LANG = "active_language_code"
    private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"

    private const val HF_BASE_URL = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main"

    val REGISTRY: Map<String, ModelMetadata> = mapOf(
        "mr" to ModelMetadata(
            languageCode = "mr",
            modelName = "indicconformer_stt_mr_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/mr/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595593L,
            expectedSha256 = "1ea81e55c4b9b12624c9d02a5b9c1b6f7c871c78a55ff52d333f81cb5136eaf2"
        ),
        "hi" to ModelMetadata(
            languageCode = "hi",
            modelName = "indicconformer_stt_hi_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/hi/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595593L,
            expectedSha256 = "915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa"
        ),
        "gu" to ModelMetadata(
            languageCode = "gu",
            modelName = "indicconformer_stt_gu_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/gu/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595461L,
            expectedSha256 = "822ed7f0b809bbd479275bf91c913d05564b88c0d082bbcba2f37999b88cb598"
        ),
        "kn" to ModelMetadata(
            languageCode = "kn",
            modelName = "indicconformer_stt_kn_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/kn/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595728L,
            expectedSha256 = "b226ce7e4ea35b0dd66991964bd00e011b6b14b0fcdf4f7d1cccd777781c94dc"
        ),
        "ml" to ModelMetadata(
            languageCode = "ml",
            modelName = "indicconformer_stt_ml_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/ml/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595555L,
            expectedSha256 = "dcbdfa9f773db910508b40b703cb76c5974e8d4c6f123ea81265b40853c3f0c2"
        ),
        "ta" to ModelMetadata(
            languageCode = "ta",
            modelName = "indicconformer_stt_ta_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/ta/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595513L,
            expectedSha256 = "abb7b59d706b8d27ba3fb5e5e3db7671c9e1a09bf7bc6122de507c60030e65fb"
        ),
        "te" to ModelMetadata(
            languageCode = "te",
            modelName = "indicconformer_stt_te_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/te/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595693L,
            expectedSha256 = "710f176e33080f1be84b4f38bb5f33be3f522dcf4f191bf4d5cc89f7a85924f3"
        ),
        "or" to ModelMetadata(
            languageCode = "or",
            modelName = "indicconformer_stt_or_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/or/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197584928L,
            expectedSha256 = "31730e06bd186bca5c3214003c2a0b5eeb3234d079b5b81aa72547adbe1c9be7"
        ),
        "bn" to ModelMetadata(
            languageCode = "bn",
            modelName = "indicconformer_stt_bn_hybrid_ctc_rnnt_large",
            modelUrl = "$HF_BASE_URL/bn/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/tokens.txt",
            expectedSizeBytes = 197595578L,
            expectedSha256 = "e9120a534f69df065314be468bf15579f1b92a4cd8c07ad119b80b69244718a8"
        ),
        "en" to ModelMetadata(
            languageCode = "en",
            modelName = "sherpa_onnx_nemo_ctc_en_conformer",
            modelUrl = "$HF_BASE_URL/en/model.int8.onnx",
            tokensUrl = "$HF_BASE_URL/en/tokens.txt",
            expectedSizeBytes = 174610057L,
            expectedSha256 = "28b9261a53028a7c99ff0799f44fb53f19c78b68cc4cf40637ac9c16cb1fbc6f"
        )
    )

    fun getModelDir(context: Context, langCode: String): File {
        val base = File(context.filesDir, "models")
        return File(base, langCode.lowercase()).apply { if (!exists()) mkdirs() }
    }

    fun isModelInstalled(context: Context, langCode: String): Boolean {
        // Special case: Marathi is bundled in APK assets and unpacked into storage
        if (langCode.equals("mr", ignoreCase = true)) {
            val mrDir = File(context.filesDir, "model")
            val legacyModel = File(mrDir, "model.int8.onnx")
            val newDir = getModelDir(context, "mr")
            val newModel = File(newDir, "model.int8.onnx")
            return (legacyModel.exists() && legacyModel.length() > 10_000_000L) ||
                   (newModel.exists() && newModel.length() > 10_000_000L)
        }

        val dir = getModelDir(context, langCode)
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        return model.exists() && model.length() > 10_000_000L && tokens.exists() && tokens.length() > 100L
    }

    fun getModelFiles(context: Context, langCode: String): Pair<File, File>? {
        if (langCode.equals("mr", ignoreCase = true)) {
            val mrDir = File(context.filesDir, "model")
            val legacyModel = File(mrDir, "model.int8.onnx")
            val legacyTokens = File(mrDir, "tokens.txt")
            if (legacyModel.exists() && legacyTokens.exists()) {
                return Pair(legacyModel, legacyTokens)
            }
        }
        val dir = getModelDir(context, langCode)
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        return if (model.exists() && tokens.exists()) Pair(model, tokens) else null
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getActiveLanguage(context: Context): Language {
        val code = getPrefs(context).getString(KEY_ACTIVE_LANG, "mr") ?: "mr"
        return Language.fromCode(code)
    }

    fun setActiveLanguage(context: Context, language: Language) {
        getPrefs(context).edit().putString(KEY_ACTIVE_LANG, language.code).apply()
    }

    fun isFirstLaunch(context: Context): Boolean {
        return !getPrefs(context).getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }

    fun setFirstLaunchDone(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }
}
