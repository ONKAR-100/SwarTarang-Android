# Phase 3 — Multilingual STT, Hugging Face Model Downloads & Cross-Language Communication

**Project:** SIH Marathi Offline Speech Communication System / IndicConformer Offline / MarathiVoice  
**Platform:** Android  
**Phase:** Phase 3  
**Status:** Planned  
**Previous phases:** Phase 1 — Complete & Verified; Phase 2 — Complete & Verified

---

# 1. Phase Objective

Phase 3 upgrades the current Marathi-only application into a **10-language, on-demand model architecture** while preserving the existing offline STT and device-to-device communication system.

The SIH target languages are:

1. Hindi
2. Gujarati
3. Marathi
4. Kannada
5. Malayalam
6. Tamil
7. Telugu
8. Odia
9. Bengali
10. English

The central design requirement is:

> **Do not bundle all language models into the APK. Download additional open-source models from Hugging Face when the user requests them, install them locally, and run them offline afterward.**

The user selects a default language during first launch. That language's model becomes the active STT model. Additional languages can be downloaded later.

The second major requirement is cross-language communication:

```text
Sender Language
      ↓
Local STT
      ↓
Text + Source Language
      ↓
Offline Local Network
      ↓
Receiver
      ↓
If source != receiver language
      ↓
Offline Translation
      ↓
Receiver Language Text
```

TTS is intentionally **not part of this phase**. It will be implemented after the multilingual text pipeline is stable.

---

# 2. SIH Requirement Alignment

The SIH problem statement requires:

- lightweight STT and TTS
- 10 specified Indian languages
- local execution on low/mid-range Android devices
- pause/stoppage based sentence formation
- low-latency streaming
- Wi-Fi/Bluetooth/device communication
- offline operation
- open-source ML/TinyML-style frameworks
- voice communication / walkie-talkie behavior
- TTS after receiving text
- emergency alert speech

Phase 3 directly addresses:

- **10-language STT architecture**
- **on-device model management**
- **open-source model sourcing**
- **download-on-demand model distribution**
- **offline use after download**
- **cross-language text translation**
- **language-aware communication protocol**
- **storage/RAM optimization**

The following remain later work:

- TTS
- voice-note playback
- emergency highest-volume announcements
- final speech-to-speech loop
- complete end-to-end voice latency measurement

---

# 3. Model Source Policy — Hugging Face

## Primary model source

**Hugging Face Hub** will be the primary source for downloadable models.

We will not use Google Drive as the application's model distribution system.

We will not place large model binaries directly inside the normal Git repository.

We will not download arbitrary models merely because they claim to support an Indian language.

Every model must pass the Phase 3 model acceptance criteria before being exposed to users.

---

# 4. Model Acceptance Criteria

A candidate model can be used only if it satisfies the following.

## 4.1 Open-source licensing

The model must have a license compatible with our intended use and redistribution.

Preferred licenses include permissive licenses such as:

- MIT
- Apache-2.0
- similarly permissive licenses after verification

The license must be checked on the **specific Hugging Face repository/model revision**.

Do not assume that all models on Hugging Face have the same license.

## 4.2 Language support

The model must explicitly support one or more of:

```text
hi
gu
mr
kn
ml
ta
te
or
bn
en
```

## 4.3 Offline inference

The model must be usable from local files after download.

No cloud API may be required for inference.

## 4.4 Android compatibility

The model must be compatible with an Android-deployable runtime.

Preferred formats:

- ONNX
- ONNX-compatible exported model
- another format that can be reliably converted to ONNX/mobile runtime

## 4.5 Runtime compatibility

For the current STT architecture, the preferred target is:

```text
Sherpa-ONNX / ONNX Runtime
```

The model must be tested before integration.

## 4.6 Model size

Model size must be measured.

A model is not accepted simply because it is accurate if it is too large for the intended low/mid-range Android target.

## 4.7 Accuracy

The model must be benchmarked on representative language samples.

## 4.8 Latency

The model must be tested on an actual Android device.

---

# 5. Important Finding — AI4Bharat IndicConformer

The Hugging Face AI4Bharat IndicConformer collection provides ASR models for Indian languages.

The collection currently includes language-specific IndicConformer models including:

- Bengali
- Gujarati
- Hindi
- Kannada
- Malayalam
- Marathi
- Odia
- Tamil
- Telugu
- and other Indian languages.

AI4Bharat also provides a multilingual IndicConformer model covering 22 Indian languages. The multilingual model is released under the MIT license. 

Reference:

https://huggingface.co/collections/ai4bharat/indicconformer

The current project already uses the Marathi AI4Bharat IndicConformer model, so the **language-specific IndicConformer family is the first family to investigate for the remaining Indian languages**.

---

# 6. Preferred STT Model Strategy

There are two possible architectures.

## Strategy A — Language-specific models

Example:

```text
Marathi
ai4bharat/indicconformer_stt_mr_hybrid_ctc_rnnt_large

Hindi
ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large

Gujarati
ai4bharat/indicconformer_stt_gu_hybrid_ctc_rnnt_large
```

and similarly for:

```text
kn
ml
ta
te
or
bn
```

Advantages:

- smaller active model
- language-specific decoding
- easier memory control
- similar architecture to the existing Marathi implementation
- easier to benchmark per language

This is the **preferred first implementation path** if the language-specific models can be exported/packaged in the same runtime-compatible format.

---

# 7. Alternative STT Strategy — One Multilingual Model

AI4Bharat also provides:

```text
ai4bharat/indic-conformer-600m-multilingual
```

The model card describes it as a 600M-parameter multilingual Conformer hybrid CTC/RNNT ASR model supporting 22 Indian languages and released under MIT.

This option should be benchmarked against the language-specific models.

Potential advantage:

```text
One model
    ↓
10 languages
```

Potential disadvantages:

- larger active memory footprint
- potentially higher CPU/RAM requirements
- language switching behavior must be tested
- mobile performance must be verified
- the current Sherpa-ONNX pipeline may require additional adaptation

Therefore:

> **Do not replace the existing Marathi model with the multilingual 600M model until Android benchmarks prove it is suitable.**

---

# 8. Important Model Compatibility Rule

A Hugging Face model being "ONNX" does **not automatically mean it can be loaded by our existing Sherpa-ONNX configuration**.

Before integrating any model:

```text
Hugging Face Model
       ↓
Inspect architecture
       ↓
Inspect files
       ↓
Check license
       ↓
Check tokenizer
       ↓
Check ONNX graph
       ↓
Check opset
       ↓
Check input/output signatures
       ↓
Check Sherpa-ONNX compatibility
       ↓
Android test
```

Only then should the model be added to the app's model registry.

This prevents the native runtime crashes and incompatibilities encountered during the original Marathi integration.

---

# 9. Model Download Architecture

The Android application will contain a **Model Registry Client**.

Conceptually:

```text
Android App
    │
    ▼
Language Manager
    │
    ▼
Model Registry
    │
    ├── Marathi
    ├── Hindi
    ├── Gujarati
    ├── Kannada
    ├── Malayalam
    ├── Tamil
    ├── Telugu
    ├── Odia
    ├── Bengali
    └── English
```

The registry stores metadata such as:

```json
{
  "languageCode": "hi",
  "languageName": "Hindi",
  "modelName": "indicconformer_stt_hi_hybrid_ctc_rnnt_large",
  "version": "1.0",
  "source": "huggingface",
  "format": "onnx",
  "quantization": "int8",
  "sizeBytes": 0,
  "sha256": "",
  "downloadUrl": "",
  "runtime": "sherpa-onnx",
  "license": "MIT"
}
```

The actual values must come from the verified model release.

---

# 10. Where Models Will Be Downloaded From

The source will be a **specific Hugging Face model repository/revision that has been verified for our Android build**.

Possible model sources:

```text
Hugging Face
    ↓
AI4Bharat official model
    OR
Verified compatible conversion/export
```

Do not make the Android app depend on a random third-party conversion simply because it is smaller.

If a third-party conversion is used, verify:

- original model
- license
- conversion method
- files
- model outputs
- tokenizer
- accuracy
- Android runtime compatibility

---

# 11. Model Packaging

The application should download a complete model package rather than independently downloading arbitrary files.

Example:

```text
marathi/
├── model.int8.onnx
├── tokens.txt
├── model.json
└── checksum.txt
```

Package:

```text
marathi-v1.0-android.zip
```

The package can be downloaded from Hugging Face.

After download:

```text
ZIP
 ↓
Verify package
 ↓
Extract to temporary directory
 ↓
Verify required files
 ↓
Verify SHA-256
 ↓
Move to final model directory
```

---

# 12. Local Storage

Suggested storage:

```text
filesDir/
└── models/
    ├── mr/
    │   ├── model.int8.onnx
    │   ├── tokens.txt
    │   └── model.json
    │
    ├── hi/
    │   └── ...
    │
    ├── gu/
    │   └── ...
    │
    ├── kn/
    │   └── ...
    │
    ├── ml/
    │   └── ...
    │
    ├── ta/
    │   └── ...
    │
    ├── te/
    │   └── ...
    │
    ├── or/
    │   └── ...
    │
    ├── bn/
    │   └── ...
    │
    └── en/
        └── ...
```

---

# 13. First Launch

First launch must become:

```text
SPLASH
  ↓
LANGUAGE SETUP
  ↓
Choose Default Language
  ↓
Check Model
  ↓
Install / Load Model
  ↓
HOME
```

Example:

```text
--------------------------------
       CHOOSE YOUR LANGUAGE
--------------------------------

  हिन्दी
  ગુજરાતી
  मराठी
  ಕನ್ನಡ
  മലയാളം
  தமிழ்
  తెలుగు
  ଓଡ଼ିଆ
  বাংলা
  English

              [ CONTINUE ]
--------------------------------
```

The selected language becomes the device's initial active STT language.

---

# 14. Initial Model Rule

For the initial SIH build, the application can continue shipping with the already verified Marathi model.

Therefore:

```text
APK
 ├── Core Application
 ├── STT Runtime
 ├── Communication
 ├── Model Manager
 └── Verified Marathi model
```

Other language models are downloaded on demand.

If the project later decides to ship a different default language, the same architecture remains valid.

---

# 15. Optional Model Download UI

Settings:

```text
LANGUAGES
────────────────────────────

ACTIVE
✓ Marathi
  Model ready

INSTALLED
✓ Hindi
  210 MB
  [ ACTIVE ]

AVAILABLE
↓ Gujarati
  200 MB
  [ DOWNLOAD ]

↓ Tamil
  205 MB
  [ DOWNLOAD ]

↓ English
  180 MB
  [ DOWNLOAD ]
```

Model sizes must be read from verified metadata.

Do not hard-code guessed sizes.

---

# 16. Download Process

When the user presses Download:

```text
User
 ↓
Download button
 ↓
Check Internet
 ↓
Fetch verified model metadata
 ↓
Show size
 ↓
Download
 ↓
Progress
 ↓
Checksum verification
 ↓
Package extraction
 ↓
Model validation
 ↓
Register installed model
 ↓
Available offline
```

---

# 17. Download Failure Handling

The application must handle:

- no network
- timeout
- interrupted download
- insufficient storage
- corrupted package
- checksum mismatch
- invalid model
- unsupported runtime
- incomplete extraction

A failed download must never destroy an already working model.

---

# 18. SHA-256 Verification

Every model package must have a SHA-256 checksum.

Example:

```text
Expected:
abc123...

Downloaded:
abc123...

MATCH
 ↓
Install
```

Mismatch:

```text
Expected:
abc123...

Downloaded:
xyz456...

REJECT
```

Delete the incomplete/corrupt temporary file and allow retry.

---

# 19. Model Versioning

Each model needs:

```text
languageCode
modelVersion
runtimeVersion
packageVersion
sha256
```

Example:

```json
{
  "languageCode": "mr",
  "modelVersion": "1.0",
  "runtimeCompatibility": "sherpa-onnx-1.13.8",
  "packageVersion": "1",
  "sha256": "..."
}
```

This prevents future model updates from silently breaking the application.

---

# 20. Model Lifecycle

Only the active STT model should normally be loaded into RAM.

Example:

```text
DISK
Marathi ✓
Hindi ✓
Tamil ✓
English ✓

RAM
Marathi ← ACTIVE
```

Switch:

```text
Marathi
 ↓
Stop STT
 ↓
Release native resources
 ↓
Unload Marathi
 ↓
Load Hindi
 ↓
Verify
 ↓
Hindi ACTIVE
```

Do not load all downloaded language models simultaneously.

---

# 21. Language Switching

Settings:

```text
Current Language: Marathi

Change Language
       ↓
Hindi
```

System:

```text
Is Hindi installed?
       │
   ┌───┴───┐
   │       │
  YES      NO
   │       │
Load      Download
   │       │
   └───┬───┘
       ↓
Hindi ACTIVE
```

If model loading fails, keep the previous working language active.

---

# 22. Message Protocol Upgrade

The existing `SpeechResult` contract must be extended.

Recommended:

```json
{
  "messageId": "msg-8f92a1",
  "sourceLanguage": "mr",
  "text": "कृपया मला मदत करा",
  "timestamp": 1727527456789,
  "isFinal": true,
  "translationRequired": true
}
```

The most important addition is:

```text
sourceLanguage
```

The receiver's own language is local state and does not need to be transmitted by the sender.

---

# 23. Same-Language Communication

Example:

```text
Sender language = Marathi
Receiver language = Marathi
```

Pipeline:

```text
Marathi speech
     ↓
Marathi STT
     ↓
Marathi text
     ↓
Network
     ↓
Receiver
     ↓
source == target
     ↓
Display original text
```

No translation model is required.

---

# 24. Cross-Language Communication

Example:

```text
Sender = Marathi
Receiver = Hindi
```

Pipeline:

```text
🎤 Marathi speech
       ↓
Marathi STT
       ↓
"मला मदत करा"
       ↓
TEXT + sourceLanguage=mr
       ↓
Wi-Fi
       ↓
Receiver
       ↓
sourceLanguage != activeLanguage
       ↓
Offline Translation
       ↓
Hindi
       ↓
"मेरी मदद करो"
```

---

# 25. Translation Engine

Phase 3 requires a separate abstraction:

```text
TranslationEngine
```

Suggested API:

```text
translate(
    text,
    sourceLanguage,
    targetLanguage
)
```

Example:

```text
translate(
    "मला मदत करा",
    "mr",
    "hi"
)
```

Output:

```text
"मेरी मदद करो"
```

---

# 26. Translation Model Candidate

AI4Bharat provides open-source IndicTrans2 models on Hugging Face.

The IndicTrans2 project supports translation across the 22 scheduled Indic languages and includes the languages needed for this project, including:

- Bengali
- English
- Gujarati
- Hindi
- Kannada
- Malayalam
- Marathi
- Odia
- Tamil
- Telugu

The AI4Bharat model collection currently includes IndicTrans2 variants such as:

```text
indictrans2-indic-indic-dist-320M
indictrans2-indic-indic-1B
indictrans2-indic-en-dist-200M
indictrans2-en-indic-dist-200M
```

These candidates must be benchmarked for Android before final selection.

For Phase 3, **prefer the smallest model that provides acceptable translation quality and Android performance**.

Do not assume the 320M or 200M model is automatically suitable; benchmark it on the actual target device.

---

# 27. Translation Architecture

Preferred conceptual pipeline:

```text
Receiver
   ↓
Read sourceLanguage
   ↓
Read activeLanguage
   ↓
Same?
 /   \
YES   NO
 |     |
Display Translation
       ↓
Offline Translation Model
       ↓
Translated Text
       ↓
Display
```

---

# 28. Translation Storage Strategy

Do not automatically download every translation model.

Possible approaches:

## Option A — Multilingual Indic model

```text
One translation model
       ↓
Many Indian language pairs
```

## Option B — Smaller directional models

```text
Indic → Indic
Indic → English
English → Indic
```

## Option C — Hybrid

Use a smaller multilingual model plus optional components.

The final choice must be based on:

- model size
- RAM
- CPU
- translation latency
- quality
- Android compatibility
- license

---

# 29. Translation Must Be Offline

After translation model installation:

```text
Internet OFF
       ↓
Marathi text
       ↓
Local translation model
       ↓
Hindi text
```

No:

- Google Translate API
- cloud translation API
- OpenAI API
- Microsoft Translator API
- online translation endpoint

during normal operation.

---

# 30. Important Scope Rule

Phase 3 does not have to prove every one of the 90 possible language directions before the architecture is considered complete.

However, the architecture must support:

```text
sourceLanguage
targetLanguage
```

and the selected translation model must be tested for the language pairs it claims to support.

At least one real cross-language Android demonstration must be completed first.

Then expand coverage systematically.

---

# 31. UI — Receiver Translation

Example:

```text
┌──────────────────────────────────┐
│ RECEIVED MESSAGE                 │
│                                  │
│ मुझे मदद चाहिए                  │
│                                  │
│ Original: Marathi                │
│ Translated: Hindi                │
│                                  │
│ 20:42                            │
└──────────────────────────────────┘
```

Optional:

```text
[ VIEW ORIGINAL ]
```

Original:

```text
मला मदत हवी आहे
```

This makes the translation behavior transparent during the SIH demonstration.

---

# 32. UI — Sender

Display active language:

```text
MARATHI
● MODEL READY

        VOICE ORB

     HOLD TO SPEAK
```

When switched:

```text
HINDI
● MODEL READY
```

The UI must distinguish:

```text
MODEL DOWNLOADING
MODEL LOADING
MODEL READY
MODEL ERROR
```

---

# 33. UI — Model Download

Example:

```text
Hindi

Offline Speech Recognition Model

Size:
210 MB

Source:
Hugging Face

License:
MIT

Status:
Not installed

[ DOWNLOAD ]
```

During download:

```text
Downloading Hindi...

145 MB / 210 MB

██████████████░░░░░░ 69%

[ CANCEL ]
```

After:

```text
✓ Hindi installed

Available offline
```

---

# 34. Security and Trust

The application should display:

- model source
- model license
- model version
- model size
- checksum/version information

Only verified model packages should be installed.

If a model originates from a third-party conversion rather than the official AI4Bharat repository, document:

```text
Original model
Conversion repository
License
Conversion process
Runtime compatibility
Benchmark result
```

---

# 35. STT Model Selection Process

For every required language:

```text
1. Search Hugging Face
2. Prefer official AI4Bharat model
3. Verify license
4. Verify language
5. Check model format
6. Check model files
7. Check tokenizer
8. Check ONNX compatibility
9. Quantize if required
10. Test desktop inference
11. Test Android inference
12. Measure RAM
13. Measure CPU
14. Measure RTF
15. Measure WER
16. Approve model
```

Only approved models enter the registry.

---

# 36. Language-by-Language Target Table

| SIH Language | Code | Primary STT Candidate | Status |
|---|---|---|---|
| Marathi | `mr` | AI4Bharat IndicConformer | Existing / Verified |
| Hindi | `hi` | AI4Bharat IndicConformer | To validate |
| Gujarati | `gu` | AI4Bharat IndicConformer | To validate |
| Kannada | `kn` | AI4Bharat IndicConformer | To validate |
| Malayalam | `ml` | AI4Bharat IndicConformer | To validate |
| Tamil | `ta` | AI4Bharat IndicConformer | To validate |
| Telugu | `te` | AI4Bharat IndicConformer | To validate |
| Odia | `or` | AI4Bharat IndicConformer | To validate |
| Bengali | `bn` | AI4Bharat IndicConformer | To validate |
| English | `en` | Open-source compatible ASR candidate | To validate |

Important:

**"To validate" does not mean the model has already been proven Android-compatible.**

Each candidate must pass the model acceptance process.

---

# 37. English STT

English must be treated separately from the Indian-language IndicConformer set if the selected IndicConformer collection does not provide the required English model.

Candidate requirements:

- open-source
- offline
- Android-compatible
- reasonable model size
- suitable WER
- acceptable CPU/RAM
- compatible with the chosen runtime

A model must not be selected merely because it is popular.

---

# 38. Phase 3 Development Milestones

## Milestone 1 — Model Registry

Implement:

- Language data class
- Model metadata
- Installed model registry
- Active language persistence

**Output:**

The app can understand which languages are installed and which model is active.

---

## Milestone 2 — Hugging Face Downloader

Implement:

- HTTPS download
- progress
- cancellation
- temporary files
- checksum verification
- extraction
- installation
- failure recovery

**Output:**

One additional model can be downloaded and installed.

---

## Milestone 3 — Dynamic STT Model Loading

Implement:

```text
loadModel(language)
unloadModel(language)
```

**Output:**

Marathi can be unloaded and another compatible model can be loaded without rebuilding the APK.

---

## Milestone 4 — First Additional Language

Do not integrate all nine remaining languages immediately.

First prove:

```text
Marathi
    +
one additional validated language
```

Complete:

```text
Download
 ↓
Install
 ↓
Internet OFF
 ↓
Switch language
 ↓
STT
```

This proves the architecture.

---

## Milestone 5 — Remaining STT Languages

After the first additional language succeeds:

```text
Hindi
Gujarati
Kannada
Malayalam
Tamil
Telugu
Odia
Bengali
English
```

are integrated one by one.

Each language must be tested before being marked supported.

---

## Milestone 6 — Translation Engine

Implement:

```text
TranslationEngine
```

and test the smallest practical offline multilingual translation model.

---

## Milestone 7 — Cross-Language Communication

First demonstration:

```text
Marathi Sender
       ↓
Hindi Receiver
```

Then reverse:

```text
Hindi Sender
       ↓
Marathi Receiver
```

Then expand language-pair testing.

---

# 39. Performance Metrics

For every STT language, record:

```text
Model size
Model load time
Peak RAM
Idle CPU
STT CPU
First partial latency
Final transcript latency
Inference time
RTF
WER/CER
```

For translation:

```text
Translation model size
Translation model load time
Translation RAM
Translation CPU
Translation latency
Translation quality
```

---

# 40. Storage Benchmark

Compare:

### Current-style architecture

```text
APK
+
all 10 models
```

against:

### Phase 3 architecture

```text
APK
+
Marathi model
+
user-selected optional models
```

The second architecture should demonstrate reduced initial storage requirements.

---

# 41. Offline Verification

After downloading a language:

1. Download model.
2. Verify model.
3. Install.
4. Disable Wi-Fi internet/mobile data while keeping local device communication available where possible.
5. Run STT.
6. Run translation.
7. Send message.
8. Receive message.
9. Verify no cloud API is called.

The application must continue operating locally.

---

# 42. Testing Matrix

## STT

| Test | Expected |
|---|---|
| Marathi model | Works |
| Hindi model | Works after installation |
| Gujarati model | Works after installation |
| Kannada model | Works after installation |
| Malayalam model | Works after installation |
| Tamil model | Works after installation |
| Telugu model | Works after installation |
| Odia model | Works after installation |
| Bengali model | Works after installation |
| English model | Works after installation |

## Communication

| Sender | Receiver | Expected |
|---|---|---|
| Marathi | Marathi | Direct display |
| Marathi | Hindi | Marathi → Hindi |
| Hindi | Marathi | Hindi → Marathi |
| Marathi | English | Marathi → English |
| English | Marathi | English → Marathi |

Additional pairs are added after the core path is verified.

---

# 43. Memory Test

Install:

```text
Marathi
Hindi
Tamil
English
```

Then verify:

```text
Disk:
All four installed

RAM:
Only active STT model loaded
```

Switch:

```text
Marathi → Hindi
```

Verify Marathi native resources are released.

---

# 44. Failure Test

Corrupt a downloaded model.

Expected:

```text
Model verification FAILED

Hindi model was not installed.

Existing Marathi model remains available.
```

The application must not crash.

---

# 45. Backward Compatibility

Phase 3 must not break:

- Marathi STT
- VAD
- partial transcription
- sentence finalization
- sender mode
- receiver mode
- TCP
- Hotspot transport
- Local Wi-Fi
- Wi-Fi Direct
- duplicate filtering
- chat history
- current UI navigation

The existing verified Phase 1 and Phase 2 functionality is the baseline.

---

# 46. Phase 3 Definition of Done

Phase 3 is complete only when:

## Language Management

- [ ] First-launch language selection works.
- [ ] Active language persists across app restarts.
- [ ] User can change active language.
- [ ] Installed and unavailable languages are clearly shown.

## Hugging Face Model Distribution

- [ ] Hugging Face is used as the model source.
- [ ] Each model's license is verified.
- [ ] Each model's source is documented.
- [ ] Model metadata is stored.
- [ ] Model download works.
- [ ] Download progress works.
- [ ] Download cancellation works.
- [ ] SHA-256 verification works.
- [ ] Corrupt downloads are rejected.
- [ ] Models are stored locally.
- [ ] Models work after internet is disabled.

## STT

- [ ] Marathi continues working.
- [ ] At least one additional language is successfully downloaded and used.
- [ ] Remaining SIH languages are integrated and individually validated.
- [ ] Dynamic model loading works.
- [ ] Dynamic model unloading works.
- [ ] RTF is measured per tested language.
- [ ] WER/CER is measured per tested language.

## Translation

- [ ] Offline translation engine is integrated.
- [ ] Receiver language determines target language.
- [ ] Same-language messages bypass translation.
- [ ] Different-language messages are translated locally.
- [ ] At least one cross-language pair works end-to-end on Android.
- [ ] Reverse direction is tested.
- [ ] Translation latency is measured.

## Communication

- [ ] Source language is included in the message.
- [ ] Existing TCP communication remains stable.
- [ ] Existing duplicate protection remains stable.
- [ ] Bidirectional communication remains stable.

## SIH Performance

- [ ] APK size measured.
- [ ] Model sizes measured.
- [ ] RAM measured.
- [ ] CPU measured.
- [ ] STT latency measured.
- [ ] Translation latency measured.
- [ ] RTF measured.

---

# 47. What Is NOT Being Done in Phase 3

Do not implement these yet:

- TTS
- emergency siren/alert speech
- highest-volume non-interruptible speech
- final voice-note playback
- complete speech-to-speech loop
- all TTS models
- TTS latency benchmarking

Those belong to the next phase after multilingual STT + translation is stable.

---

# 48. Final Phase 3 Architecture

```text
                         ANDROID APP
                              │
                              ▼
                     LANGUAGE MANAGER
                              │
              ┌───────────────┴────────────────┐
              │                                │
       Installed Models                  Available Models
              │                                │
              │                          Hugging Face
              │                                │
              │                         Download + Verify
              │                                │
              └───────────────┬────────────────┘
                              │
                              ▼
                        MODEL MANAGER
                              │
                    Active STT Model
                              │
                              ▼
                         🎤 SPEECH
                              │
                              ▼
                           VAD
                              │
                              ▼
                           STT
                              │
                              ▼
                    TEXT + SOURCE LANGUAGE
                              │
                              ▼
                     LOCAL TCP / WI-FI
                              │
                              ▼
                         RECEIVER
                              │
                  ┌───────────┴───────────┐
                  │                       │
             Same Language          Different Language
                  │                       │
                  │                       ▼
                  │               OFFLINE TRANSLATION
                  │                       │
                  └───────────┬───────────┘
                              ▼
                       RECEIVER TEXT
                              │
                              ▼
                       [ FUTURE TTS ]
                              │
                              ▼
                              🔊
```

---

# 49. Final Development Principle

The Phase 3 implementation must follow these rules:

> **Open-source models only.**

> **Hugging Face is the primary model distribution source.**

> **Official AI4Bharat models are preferred when they satisfy the runtime and performance requirements.**

> **Every model must be license-verified, runtime-verified, and Android-benchmarked before being marked supported.**

> **Download models on demand instead of putting all models in the APK.**

> **After installation, models must operate completely offline.**

> **Only the models required by the user should consume storage/RAM.**

> **Communication transmits lightweight text and source-language metadata, not raw speech audio.**

> **Translation happens locally on the receiver when the sender and receiver languages differ.**

> **Do not sacrifice Android performance merely to claim 10 languages. Each language must be individually validated.**

The immediate implementation target is therefore:

```text
FIRST PROOF

Hugging Face
     ↓
Download ONE additional open-source STT model
     ↓
Install on Android
     ↓
Disable internet
     ↓
Switch language
     ↓
Run offline STT successfully
```

Only after this succeeds should the system expand to all remaining languages and offline translation.
