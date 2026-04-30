package com.secondlife.visionaudio.audio

// Rohan's sensor module — standalone version outside the Android app module.
// See android-app/src/main/java/com/secondlife/audio/AudioCaptureManager.kt
// for the integrated version used by the UI.
//
// Contract → Shravan:
//   ByteArray — PCM 16-bit signed LE, 16 000 Hz, mono, no WAV headers
//   Delivered via Flow<ByteArray>; silence trimmed before emission.
