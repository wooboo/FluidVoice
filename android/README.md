# FluidVoice Remote for Android

Open this directory in Android Studio, let it install Android SDK 36, then run the `app` configuration on an Android 10+ device with Google Play services. Android 10 is the minimum because the transport requires TLS 1.3.

The current MVP supports QR pairing, TLS certificate pinning, recording a complete utterance, remote transcription, and displaying the enhanced result. Overlay and Accessibility-based insertion are intentionally deferred until this end-to-end transport is validated on physical devices.
