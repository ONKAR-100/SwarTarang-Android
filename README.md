# Phase 1: Marathi IndicConformer Offline STT

This is the Android prototype for real-time, fully offline Speech-to-Text using the AI4Bharat IndicConformer Marathi model (`indicconformer_stt_mr_hybrid_ctc_rnnt_large`).

## Architecture

1. **Microphone Capture**: `AudioRecord` continuously reads 16-kHz, mono, 16-bit PCM audio.
2. **Voice Activity Detection (VAD)**: A custom Energy-based RMS VAD processes the incoming stream in real-time. It detects speech start (threshold) and silence (pause duration).
3. **Inference Backend**: We use [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) which provides highly optimized on-device inference for Conformer CTC models. 
4. **Streaming Partial Results**: While you hold the PTT (Push-To-Talk) button and speak, the audio buffer grows. Every 300ms, the accumulated buffer is passed to the IndicConformer `OfflineRecognizer` to yield partial text updates. Since inference takes < 50ms for a few seconds of audio, this effectively provides real-time streaming feedback.
5. **Sentence Endpointing**: When the VAD detects a pause longer than the configured silence duration, the current buffer is finalized, appended to the final transcript, and the buffer is cleared. The system immediately resumes listening for the next sentence if the button is still held.

## Model Loading & Deployment

* **Model**: The AI4Bharat checkpoint was converted to an INT8-quantized ONNX model using the Sherpa-onnx NeMo exporter.
* **Storage**: The `.onnx` and `tokens.txt` files are bundled directly into `app/src/main/assets/model/`.
* **Execution**: Model is loaded into memory on startup and executed entirely on the CPU (optimized for ARM64).

## How to Test

1. Open this project in **Android Studio**.
2. Sync Gradle and run the app on a physical Android device (ARM64).
3. Wait for the status to show **"Model Loaded"**.
4. **Offline Validation Mode**: Turn ON Airplane Mode or disable Wi-Fi/Mobile Data.
5. Press and **hold** the "Hold to Speak (Marathi)" button.
6. Speak a sentence in Marathi (e.g., "कृपया मला मदत करा").
7. Watch the grey text for live partial transcripts.
8. Pause for ~800ms. The text will finalize (turn black), and you can continue speaking a new sentence without releasing the button.
9. Check the bottom metrics bar for RTF (Real-Time Factor), inference latency, and RAM usage.

### Test Cases Validation

* **Short/Long sentences:** Test by speaking short phrases and long paragraphs. VAD will chunk them automatically.
* **Pause between sentences:** Pausing will cleanly trigger the endpointing logic.
* **Airplane mode:** The app operates 100% locally. No data leaves the device.

## VAD Configuration

You can tweak the VAD parameters in `MainActivity.kt`:
- `speechStartThreshold = 0.015f` (Increase if background noise triggers speech).
- `silenceDurationMs = 800L` (How long a pause needs to be before finalizing a sentence).
- `minSpeechDurationMs = 200L` (Ignore short spurious noises).

## Future Phases

- Exploring the RNNT path (if Sherpa-onnx adds complete streaming state tracking for NeMo RNNT).
- Expanding to other languages.
- Implementing Android Foreground Service for background recording.
