import sounddevice as sd
import numpy as np
import sherpa_onnx
import time
import sys

# Paths to the models we downloaded in the Android project
MODEL_PATH = "IndicConformerApp/app/src/main/assets/model/model.int8.onnx"
TOKENS_PATH = "IndicConformerApp/app/src/main/assets/model/tokens.txt"

# VAD parameters (matching our Android implementation)
SPEECH_START_THRESHOLD = 0.025  # Increased slightly to ignore background noise
SILENCE_DURATION_SEC = 1.5     # Wait 1.5 seconds of silence before finalizing
MIN_SPEECH_DURATION_SEC = 0.2
CHUNK_DURATION_SEC = 0.1  # read 100ms chunks from mic
SAMPLE_RATE = 16000

print("Loading IndicConformer Marathi Model on your laptop...")
t0 = time.time()
try:
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=MODEL_PATH,
        tokens=TOKENS_PATH,
        num_threads=4,
        decoding_method="greedy_search",
    )
except Exception as e:
    print(f"Error loading model: {e}")
    sys.exit(1)
    
print(f"Model loaded successfully in {time.time()-t0:.2f} seconds!")

is_speaking = False
speech_start_time = 0
last_speech_time = 0
last_partial_time = 0
audio_buffer = []

def calculate_rms(audio_chunk):
    return np.sqrt(np.mean(np.square(audio_chunk)))

def decode_buffer(samples):
    start_t = time.time()
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    recognizer.decode_stream(stream)
    text = stream.result.text
    end_t = time.time()
    return text, end_t - start_t

print("\n=======================================================")
print("🎙️  Listening to your microphone (Press Ctrl+C to stop)")
print("🗣️  Speak some Marathi... ")
print("=======================================================\n")

try:
    # Find the actual microphone instead of Stereo Mix
    devices = sd.query_devices()
    mic_device = None
    for i, d in enumerate(devices):
        name = d['name'].lower()
        if d['max_input_channels'] > 0 and 'microphone array' in name:
            mic_device = i
            print(f"🎤 Auto-selected Microphone: {d['name']}")
            break
            
    if mic_device is None:
        print("Could not find 'Microphone Array'. Falling back to default.")

    # Open microphone stream
    with sd.InputStream(device=mic_device, samplerate=SAMPLE_RATE, channels=1, dtype='float32') as stream:
        while True:
            chunk, overflowed = stream.read(int(SAMPLE_RATE * CHUNK_DURATION_SEC))
            if overflowed:
                continue
            chunk = chunk.flatten()
            
            rms = calculate_rms(chunk)
            current_time = time.time()
            
            if rms > SPEECH_START_THRESHOLD:
                if not is_speaking:
                    is_speaking = True
                    speech_start_time = current_time
                last_speech_time = current_time
                
            if is_speaking:
                audio_buffer.extend(chunk)
                
                # Decode and show partial transcript every ~300ms
                if current_time - last_partial_time > 0.3:
                    samples = np.array(audio_buffer, dtype=np.float32)
                    # Only decode if we have enough samples
                    if len(samples) > 0:
                        text, inf_time = decode_buffer(samples)
                        audio_dur = len(samples) / SAMPLE_RATE
                        rtf = inf_time / audio_dur if audio_dur > 0 else 0
                        # \r overwrites the current line
                        sys.stdout.write(f"\r⏳ Partial: {text} (RTF: {rtf:.2f})                                ")
                        sys.stdout.flush()
                    last_partial_time = current_time
                    
                # Check for silence (pause detected)
                if current_time - last_speech_time > SILENCE_DURATION_SEC:
                    is_speaking = False
                    duration = last_speech_time - speech_start_time
                    if duration > MIN_SPEECH_DURATION_SEC:
                        samples = np.array(audio_buffer, dtype=np.float32)
                        text, inf_time = decode_buffer(samples)
                        audio_dur = len(samples) / SAMPLE_RATE
                        rtf = inf_time / audio_dur if audio_dur > 0 else 0
                        
                        sys.stdout.write(f"\r✅ Final: {text}  |  (Audio: {audio_dur:.2f}s | Infer: {inf_time*1000:.0f}ms | RTF: {rtf:.2f})\n")
                        sys.stdout.flush()
                        
                        # Save to text file
                        with open("transcripts.txt", "a", encoding="utf-8") as f:
                            f.write(text + "\n")
                            
                    audio_buffer = [] # Clear buffer for next sentence

except KeyboardInterrupt:
    print("\n\nStopped listening.")
except Exception as e:
    print(f"\nMicrophone Error: {e}")
