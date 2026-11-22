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

Model files are not bundled in the APK because they are tens of megabytes.
They should be downloaded on first use into app-private storage, with a
checksum verified before loading.

Rejected alternatives:

- whisper.cpp — no official Android AAR and slow on mobile CPUs.
- faster-whisper — Python server only, not Android.
- openWakeWord — usable wake word but its bundled models are non-commercial.

Next implementation steps:

1. Add `DiyaWakeService` with `RECORD_AUDIO` and microphone foreground service.
2. Add a `VoiceBridge` around sherpa-onnx KWS + streaming ASR.
3. Feed the transcript into the existing `POST /task` endpoint.
4. Add the “Hey Diya” glow and mic overlay.
