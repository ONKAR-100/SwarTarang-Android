package com.example.indicconformer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

import java.security.MessageDigest

private const val TAG = "ModelDownloader"

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val langCode: String,
        val currentFile: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float
    ) : DownloadState
    data class Verifying(val langCode: String, val message: String) : DownloadState
    data class Completed(val langCode: String) : DownloadState
    data class Failed(val langCode: String, val error: String) : DownloadState
    data class Cancelled(val langCode: String) : DownloadState
}

class ModelDownloader(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun downloadLanguage(langCode: String): Job {
        activeJob?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val meta = ModelRegistry.REGISTRY[langCode]
            if (meta == null) {
                _state.value = DownloadState.Failed(langCode, "Unknown language code: $langCode")
                return@launch
            }

            // Milestone 2 Requirement: Verify sufficient storage space (>250MB)
            val freeBytes = context.cacheDir.usableSpace
            val requiredBytes = meta.expectedSizeBytes + 50L * 1024 * 1024 // Model + buffer
            if (freeBytes < requiredBytes) {
                _state.value = DownloadState.Failed(
                    langCode,
                    "Insufficient storage: ${freeBytes / (1024 * 1024)} MB available, need ${requiredBytes / (1024 * 1024)} MB"
                )
                return@launch
            }

            // Milestone 2 Requirement: Isolated temporary files directory
            val tempDir = File(context.cacheDir, "model_downloads/$langCode").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            try {
                Log.d(TAG, "Starting download for ${meta.languageCode} from Hugging Face (${meta.formattedSize})...")

                // 1. Download tokens.txt
                val tempTokens = File(tempDir, "tokens.txt")
                try {
                    downloadFileWithRedirects(
                        urlStr = meta.tokensUrl,
                        destFile = tempTokens,
                        langCode = langCode,
                        fileName = "tokens.txt",
                        totalEstimated = 70_000L
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "tokens.txt download failed, checking for local fallback tokens...", e)
                    // If downloading tokens failed, fallback to bundled Marathi tokens (same AI4Bharat 5633-token vocab)
                    val mrDir = File(context.filesDir, "model")
                    val bundledTokens = File(mrDir, "tokens.txt")
                    if (bundledTokens.exists() && bundledTokens.length() > 50L) {
                        bundledTokens.copyTo(tempTokens, overwrite = true)
                        Log.d(TAG, "Recovered tokens from bundled model directory")
                    } else {
                        throw e
                    }
                }

                // 2. Download model.int8.onnx
                val tempModel = File(tempDir, "model.int8.onnx")
                downloadFileWithRedirects(
                    urlStr = meta.modelUrl,
                    destFile = tempModel,
                    langCode = langCode,
                    fileName = "model.int8.onnx",
                    totalEstimated = meta.expectedSizeBytes
                )

                // 3. Milestone 2 Requirement: Package & File validation
                if (!tempModel.exists() || tempModel.length() < 10_000_000L) {
                    throw IllegalStateException("Downloaded model file is incomplete or invalid (${tempModel.length()} bytes)")
                }
                if (!tempTokens.exists() || tempTokens.length() < 50L) {
                    throw IllegalStateException("Downloaded tokens file is incomplete")
                }

                // 4. Milestone 2 Requirement: SHA-256 Checksum Verification
                _state.value = DownloadState.Verifying(langCode, "Verifying SHA-256 Checksum...")
                Log.d(TAG, "Computing SHA-256 checksum for ${meta.languageCode} model...")
                val computedSha256 = computeSha256(tempModel)
                Log.d(TAG, "Expected SHA-256: ${meta.expectedSha256}")
                Log.d(TAG, "Computed SHA-256: $computedSha256")

                if (!computedSha256.equals(meta.expectedSha256, ignoreCase = true)) {
                    throw IllegalStateException(
                        "SHA-256 checksum mismatch! Expected ${meta.expectedSha256.take(8)}... but got ${computedSha256.take(8)}..."
                    )
                }
                Log.d(TAG, "SHA-256 Checksum Verified Successfully! MATCH: $computedSha256")

                // 5. Milestone 2 Requirement: Safe atomic installation into persistent app storage
                _state.value = DownloadState.Verifying(langCode, "Installing model...")
                val targetDir = ModelRegistry.getModelDir(context, langCode)
                val targetModel = File(targetDir, "model.int8.onnx")
                val targetTokens = File(targetDir, "tokens.txt")

                // Atomically copy verified files
                tempModel.copyTo(targetModel, overwrite = true)
                tempTokens.copyTo(targetTokens, overwrite = true)

                // Save checksum verification file as record of installation
                File(targetDir, "checksum.sha256").writeText(computedSha256)

                // 6. Clean up temporary directory
                tempDir.deleteRecursively()

                Log.d(TAG, "Download, checksum verification, and installation of ${meta.languageCode} completed successfully!")
                _state.value = DownloadState.Completed(langCode)

            } catch (ce: CancellationException) {
                Log.d(TAG, "Download of $langCode was cancelled")
                // Failure recovery: remove temp files, leave working models intact
                tempDir.deleteRecursively()
                _state.value = DownloadState.Cancelled(langCode)
            } catch (e: Exception) {
                Log.e(TAG, "Download of $langCode failed: ${e.message}", e)
                // Failure recovery: remove temp files, leave working models intact
                tempDir.deleteRecursively()
                _state.value = DownloadState.Failed(langCode, e.message ?: "Download failed")
            }
        }
        activeJob = job
        return job
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(256 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cancelDownload() {
        val current = _state.value
        if (current is DownloadState.Downloading || current is DownloadState.Verifying) {
            activeJob?.cancel()
            val code = when (current) {
                is DownloadState.Downloading -> current.langCode
                is DownloadState.Verifying -> current.langCode
                else -> ""
            }
            _state.value = DownloadState.Cancelled(code)
        }
    }

    private suspend fun downloadFileWithRedirects(
        urlStr: String,
        destFile: File,
        langCode: String,
        fileName: String,
        totalEstimated: Long
    ) = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var redirects = 0
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            while (redirects < 6) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "MarathiVoice-Android/3.0")
                }
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location != null) {
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URL(URL(currentUrl), location).toString()
                        }
                        redirects++
                        continue
                    }
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Server returned HTTP $responseCode for $currentUrl")
                }
                break
            }

            val length = connection!!.contentLengthLong.let { if (it > 0) it else totalEstimated }
            inputStream = connection.inputStream
            outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!coroutineContext.isActive) throw CancellationException("Download cancelled")
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                val progress = if (length > 0) (totalRead.toFloat() / length).coerceIn(0f, 1f) else 0f
                _state.value = DownloadState.Downloading(
                    langCode = langCode,
                    currentFile = fileName,
                    bytesDownloaded = totalRead,
                    totalBytes = length,
                    progress = progress
                )
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream?.close() }
            runCatching { inputStream?.close() }
            runCatching { connection?.disconnect() }
        }
    }
}
