# Cozy Card Journey

Offline match-3 puzzle game for Android with collectible 2.5D trading cards.
Kotlin · Jetpack Compose · Room · OpenGL ES 2.0 · Android 10+.

* 1000 levels in 20 themed chapters, no lives/energy/timers/ads/real-money purchases.
* Lootbox on every first clear, transparent odds with pity, three starter collections of original art.
* Interactive 2.5D card viewer with foil, holographic and parallax effects.

Docs: [PROJECT_CONTEXT](PROJECT_CONTEXT.md) · [ARCHITECTURE](ARCHITECTURE.md) · [ROADMAP](ROADMAP.md) · [CHANGELOG](CHANGELOG.md)

## Install on a phone
1. Build (or take a built APK): `./gradlew :app:assembleRelease`
   → `app/build/outputs/apk/release/app-release.apk` (≈4.5 MB, R8-optimised).
   The debug build (`:app:assembleDebug`) is larger (≈60 MB, unshrunk) and installs side by side
   with the release build (package `com.cozycards.journey.debug`).
2. Either `adb install app/build/outputs/apk/release/app-release.apk`,
   or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).
3. Requires Android 10 or newer and OpenGL ES 2.0 (every Android 10 device has it).

Without a `keystore.properties` file the release APK is signed with the Android debug key — fine for
personal installs, not for store publishing. To sign with your own key, create `keystore.properties`:
```
storeFile=release-keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Build requirements
JDK 17+ (21 tested), Android SDK platform 35 + build-tools 35.0.0 (`local.properties` → `sdk.dir`).

## Tests
```
./gradlew test            # engine, levels (all 1000 levels), game, app (Robolectric)
./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'   # PNGs in app/build/screenshots
```

## Content tools
* `./gradlew :levels:generateLevels` — regenerate and validate all levels (deterministic).
* `python3 tools/card_packs.py` — starter card packs. `python3 tools/synth_audio.py` — audio.
* `tools/shader_check/` — render the card shaders in WebGL for visual QA.
