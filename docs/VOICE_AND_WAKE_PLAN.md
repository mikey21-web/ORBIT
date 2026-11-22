# Voice, images and wake word plan

## Current state

ORBIT accepts text and screenshots through the local agent server. There is no
audio code yet: no microphone permission, no speech-to-text, and no wake word.
`CapsuleService` is retired; the only working in-app overlay is the virtual
screen mirror in `MainActivity`.

## Recommended stack

- **On-device audio runtime:** `sherpa-onnx` — https://github.com/k2-fsa/sherpa-onnx
  - Apache-2.0, official Android AAR, arm64, 16 KB page-size support.
  - Streaming Zipformer/Paraformer for dictation, bundled Silero VAD, KWS for
    wake word, and Kokoro TTS.
  - Much faster on mobile CPUs than whisper.cpp.
- **Optional offline accuracy re-pass:** Whisper through sherpa-onnx ONNX, not
  whisper.cpp (too slow on phone CPUs).
- **Server-side re-pass:** faster-whisper — https://github.com/SYSTRAN/faster-whisper
- **Wake word alternatives:** openWakeWord — https://github.com/dscripka/openWakeWord
  (code is Apache-2.0 but bundled models are non-commercial CC BY-NC-SA); prefer
  sherpa-onnx KWS to avoid that license limit.
- **TTS:** Kokoro — https://github.com/hexgrad/kokoro — via sherpa-onnx.

## DeepSeek Flash

`deepseek-flash` is a current OpenAI-compatible model at
`https://api.deepseek.com`. It supports text, images, tools and long context.
DeepSeek also exposes cloud ASR (`/v1/tools/asr`) and TTS endpoints, usable as
optional fallbacks when the device is online. The API key stays in the app's
encrypted preferences, never in source or logs.

## "Hey Diya" wake flow

1. Add `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE` permissions.
2. Add a `DiyaWakeService` microphone foreground service using sherpa-onnx KWS.
3. On wake: show a subtle glow, then a small floating mic button using the
   existing `SYSTEM_ALERT_WINDOW` permission and the overlay pattern already
   in `CapsuleService`.
4. Tap the button: stream microphone audio through sherpa-onnx ASR until VAD
   detects the end of speech.
5. Send the transcript to `POST /task` on `127.0.0.1:8765` with the existing
   `X-Agent-Token` header; the answer returns to the overlay/chat.
6. Keep the wake switch opt-in and show a persistent notification while the
   microphone service runs.

## Explicitly out of scope for the first slice

- Training a custom "Hey Diya" model (needs recorded samples and a KWS trainer).
- Full continuous conversation mode; the first slice is wake + one utterance.
- Replacing the chat UI; the overlay only shows a mic button and the reply.
