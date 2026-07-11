# FluidVoice Remote for Android

Open this directory in Android Studio, let it install Android SDK 36, then run the `app` configuration on an Android 10+ device with Google Play services. Android 10 is the minimum because the transport requires TLS 1.3.

With JDK 17+ available through `JAVA_HOME`, use the checked-in wrapper for reproducible command-line builds:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest lintDebug assembleDebug
```

The current MVP supports QR pairing, TLS certificate pinning, compressed utterance recording, remote transcription, and optional AI enhancement. After validating the transport on a physical device, the MVP also gained a draggable recording overlay and Accessibility-based insertion into the previously focused field, with a clipboard fallback.
