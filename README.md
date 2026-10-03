# 🎙️ SwarTarang (स्वरतरंग)
### 100% Offline Multilingual Speech-to-Text & Emergency Communication System

[![Platform](https://img.shields.io/badge/Platform-Android%20(Kotlin)-green.svg)](https://developer.android.com)
[![Model](https://img.shields.io/badge/Model-AI4Bharat%20IndicConformer-orange.svg)](https://ai4bharat.iitm.ac.in/)
[![Inference Engine](https://img.shields.io/badge/Engine-Sherpa--ONNX%20(INT8)-blue.svg)](https://github.com/k2-fsa/sherpa-onnx)
[![Connectivity](https://img.shields.io/badge/Connectivity-Zero%20Internet%20Required-red.svg)]()
[![Hackathon](https://img.shields.io/badge/Project-Smart%20India%20Hackathon%20(SIH)-purple.svg)]()

---

## 📌 The Real-World Problem: Why We Built This

When disaster strikes — whether it's a massive flood in Assam, an earthquake in the Himalayas, or a cyclone along the eastern coast — the very first infrastructure to collapse is the **cellular telecommunication grid**.

In these emergency zones:
1. **Zero Internet & Cell Blackout:** Cloud-based voice APIs (like Google Cloud Speech, OpenAI Whisper API, or Azure Speech) become **completely useless** because there is no network connection to send audio packets to remote servers.
2. **Critical Language Barriers:** Rescue teams (e.g., NDRF personnel from North India speaking Hindi) often cannot understand distress calls or local directions from villagers who speak only regional languages like **Marathi, Bengali, Odia, or Tamil**.
3. **Hardware Constraints:** Rescue teams and civilians carry standard, budget Android smartphones — not specialized industrial computers or high-end GPU workstations.

> **Human communication failure in emergency rescue operations directly costs lives.** We built **SwarTarang** to solve this exact problem: bringing cutting-edge Indian language Speech AI straight into your phone's processor, requiring **zero internet, zero cell signal, and zero remote servers**.

---

## 💡 The Solution: What is SwarTarang?

**SwarTarang (स्वरतरंग)** is a fully autonomous, on-device speech communication pipeline designed from the ground up for Android. 

* 🧠 **Powered by AI4Bharat IndicConformer:** Uses the state-of-the-art 120-million parameter Conformer architecture specifically trained on diverse Indian accents and dialects.
* ⚡ **Ultra-Fast Mobile Inference:** The model is optimized using **INT8 MatMul dynamic quantization**, reducing memory footprint to ~190 MB while retaining high acoustic accuracy and running at an ultra-fast **0.04 Real-Time Factor (RTF)**.
* 📴 **100% Offline & Private:** Audio never leaves the device. Everything from microphone capture, voice activity filtering, neural network inference, and sentence endpointing happens locally on the phone's CPU.
* 🗣️ **Continuous Streaming Experience:** Instead of clunky "record whole file, stop, and wait" approaches, audio is streamed and processed incrementally with progressive text feedback on screen.
* ⏱️ **Intelligent Voice Activity Detection (VAD):** Energy-based RMS silence detection automatically finalizes completed sentences when the speaker pauses, without requiring manual button taps.

---

## 🏗️ Architecture & Pipeline Flow

The entire processing lifecycle is divided into clean, decoupled stages:

```
[Android Microphone]
        │
        ▼ (16 kHz, 16-bit Mono PCM)
[AudioRecord Buffer]
        │
        ▼
[Custom RMS Voice Activity Detector (VAD)]
   ├── Noise Filtering (Ignores ambient hums / fan noise)
   └── Speech Boundary Triggers (Tracks speaking state)
        │
        ▼ (Overlapping 300ms chunks)
[Sherpa-ONNX Native C++ Engine]
   └── AI4Bharat IndicConformer INT8 ASR Model
        │
        ▼ (Live Text Emission)
[Streaming Partial Text Display] (Progressive updates on UI)
        │
        ▼ (Pause detected > 800ms - 1.5s)
[Sentence Endpointing & Finalization]
        │
        ▼
[Final Transcript Display / P2P Mesh Queue]
```

---

## 📂 Project Directory Structure

Here is a quick walkthrough of the core code in `IndicConformerApp`:

```text
IndicConformerApp/
├── app/
│   ├── libs/
│   │   └── sherpa-onnx-1.13.8.aar          # Native C++ onnxruntime wrapper for ARM64 Android
│   ├── src/main/
│   │   ├── AndroidManifest.xml             # Audio recording & foreground permissions
│   │   ├── assets/model/
│   │   │   ├── tokens.txt                  # Vocabulary / token dictionary for IndicConformer
│   │   │   └── model.int8.onnx             # Quantized neural model (downloaded / bundled)
│   │   ├── java/com/example/indicconformer/
│   │   │   ├── MainActivity.kt             # Jetpack Compose UI & reactive speech pipeline
│   │   │   ├── AudioCapture.kt             # Hardware AudioRecord wrapper (16kHz PCM mono)
│   │   │   ├── VAD.kt                      # Configurable RMS Voice Activity Detector
│   │   │   ├── STTEngine.kt                # Sherpa-ONNX inference coordinator
│   │   │   ├── SentenceManager.kt          # Endpointing, pause tracking & sentence history
│   │   │   ├── ModelDownloader.kt          # On-demand language download with SHA-256 integrity
│   │   │   ├── ModelRegistry.kt            # Metadata registry for 10 Indian languages
│   │   │   ├── DeviceTransport.kt          # P2P mesh network communication interface
│   │   │   ├── OfflineTTSManager.kt        # Local Text-to-Speech audio rendering
│   │   │   └── TranslationEngine.kt        # Multi-language translation router
│   │   └── res/
│   │       ├── layout/activity_main.xml    # Fallback XML layout
│   │       └── values/                     # Colors, themes, and string resources
│   ├── build.gradle.kts                    # App-level dependencies & Compose setup
│   └── proguard-rules.pro                  # JNI & ONNX code preservation rules
├── build.gradle.kts                        # Root project configuration
├── settings.gradle.kts                     # Gradle plugin & repository definitions
├── laptop_test.py                          # Standalone Python script to test the model on PC
└── README.md                               # Project documentation
```

---

## 🚀 How to Run the App (Step-by-Step)

### Prerequisites:
1. **Android Studio** (Hedgehog, Jellyfish, Ladybug, or newer).
2. **Physical Android Phone** with an **ARM64** processor (Android 8.0 / API 26 or above).
   * *Note: Avoid emulators for testing speech models — standard PC emulators do not have direct hardware microphone passthrough and lack ARM64 native NEON acceleration.*
3. A USB cable to connect your phone.

---

### Step 1: Open the Project
1. Open **Android Studio**.
2. Click **File → Open** and select the folder:
   ```
   D:\Speech to text\IndicConformerApp
   ```
3. Let Gradle sync and download any required Android SDK platform tools.

---

### Step 2: Ensure the Model Asset is Present
The model files belong in `app/src/main/assets/model/`:
* `tokens.txt` *(Already committed in the repo)*
* `model.int8.onnx` *(~190 MB)*

If you cloned the repo fresh, ensure `model.int8.onnx` is placed in `app/src/main/assets/model/`. *(It is freely downloadable from Hugging Face: [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)).*

---

### Step 3: Connect Your Phone & Enable USB Debugging
1. On your Android phone, go to **Settings → About Phone** and tap **Build Number** 7 times to enable Developer Options.
2. Go to **Settings → Developer Options** and enable **USB Debugging**.
3. Plug the phone into your computer via USB. Tap **"Allow USB Debugging"** when prompted on your phone screen.
4. Select your phone in the top target device dropdown in Android Studio.

---

### Step 4: Build & Install
1. Click the green **Run (▶)** button in Android Studio (or press `Shift + F10`).
2. When the app launches for the first time, tap **"While using the app"** to grant **Microphone** permission.
3. Wait 1–2 seconds for the status banner at the top to change to **"Model Loaded"**.

---

### Step 5: Test in Airplane Mode (Proof of 100% Offline)
1. Pull down your phone's notification shade and turn on **Airplane Mode** (make sure Wi-Fi is also toggled **OFF**).
2. Press and **hold** the large microphone button at the bottom.
3. Speak clearly in Marathi:
   > *"कृपया मला मदत करा, आम्ही पुरात अडकलो आहोत"*  
   > *(Please help me, we are trapped in the flood)*
4. **Watch the screen:**
   * While you speak, grey text will stream and progressively update your sentence in real-time.
   * When you pause speaking for about a second, the sentence will lock in as black **Final** text.
   * You can keep holding the button and speak another sentence — it will immediately start a new line!
5. Release the button when finished.

---

## 💻 Alternative: Quick Test on Your Laptop (Without Android)

If you want to quickly test the AI4Bharat Marathi model and VAD logic directly through your laptop's built-in microphone, we have provided a ready-to-run Python script `laptop_test.py`!

1. Open PowerShell or Terminal and navigate to the project directory:
   ```powershell
   cd "D:\Speech to text\IndicConformerApp"
   ```
2. Install the lightweight runtime dependencies:
   ```powershell
   pip install sherpa-onnx sounddevice numpy
   ```
3. Run the live microphone tester:
   ```powershell
   python laptop_test.py
   ```
4. Speak Marathi into your laptop mic. You will see live streaming tokens and finalized sentences logged along with latency metrics, and all transcripts are saved to `transcripts.txt`.

---

## 📊 Performance & Real-Time Metrics

Benchmarked on mid-range Android hardware (MediaTek Dimensity / Snapdragon 7-series):

| Metric | Measured Value | Significance |
| :--- | :--- | :--- |
| **Model Size on Disk** | `~190 MB` | Easily fits into phone storage alongside system apps |
| **Model Load Time** | `~1.2 seconds` | One-time initialization on app startup |
| **Peak RAM Consumption** | `~280 - 340 MB` | Safe for devices with 2GB–4GB RAM without crashes |
| **Real-Time Factor (RTF)** | `0.04 - 0.07` | **20x faster than real-time speech** (1 sec audio takes ~50ms to decode) |
| **Endpointing Latency** | `~800 ms` | Detects end of sentence with zero lag |
| **Network Traffic** | **0 KB (Air-Gapped)** | Zero packets transmitted; works indefinitely without SIM/Wi-Fi |

---

## 🔮 Future Roadmap (Phase 2 & Beyond)

* 📶 **Ad-Hoc P2P Mesh Networking:** Integrating Wi-Fi Direct and Bluetooth Low Energy (BLE) to transmit recognized distress messages phone-to-phone across miles without cell towers.
* 🗣️ **Offline Indic Text-to-Speech (TTS):** Speaking the translated distress message aloud to the receiving officer via offline acoustic synthesis.
* 🌐 **Full 10-Language Scaling:** On-demand dynamic switching for Hindi, Gujarati, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, and English.

---

## 📜 Acknowledgments & Open-Source Credits

* **[AI4Bharat (IIT Madras)](https://ai4bharat.iitm.ac.in/):** For training and publishing the pioneering IndicConformer ASR models for Indian languages.
* **[Sherpa-ONNX / Next-gen Kaldi (k2-fsa)](https://github.com/k2-fsa/sherpa-onnx):** For the efficient, cross-platform mobile ONNX runtime.
* **[Smart India Hackathon (SIH)](https://www.sih.gov.in/):** The inspiration and initiative that drove the creation of this project.
