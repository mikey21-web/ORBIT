# Voice stack decision

Chosen: **sherpa-onnx** — https://github.com/k2-fsa/sherpa-onnx

Why:

- Apache-2.0 with an official Android AAR and Kotlin API.
- arm64 support and low CPU cost; streaming Zipformer is far faster than
  whisper.cpp on phone hardware.
- One runtime covers keyword spotting, voice-activity detection, streaming
  speech-to-text and optional Kokoro text-to-speech.

Gradle dependency (JitPack):

```kotlin
implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
```

The bilingual keyword spotting model is bundled under
`android-app/app/src/main/assets/kws`. It is about 9 MB and works offline.
The phone tokens in `keywords.txt` are the generated form of “Hey Diya” for
the model's phoneme lexicon; no voice samples or training are required.

Rejected alternatives:

- whisper.cpp — no official Android AAR and slow on mobile CPUs.
- faster-whisper — Python server only, not Android.
- openWakeWord — usable wake word but its bundled models are non-commercial.

Implemented:

1. `DiyaWakeService` holds a microphone foreground service and runs the KWS model.
2. Detection of “Hey Diya” shows the glow and floating mic overlay.
3. Tapping the mic uses Android speech recognition and posts the transcript to `POST /task`.
4. All wake processing is local; no audio leaves the device for wake detection.
