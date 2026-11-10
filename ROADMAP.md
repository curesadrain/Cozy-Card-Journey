# Roadmap

## Done
- [x] **Phase 1 – Foundation**: SDK setup, multi-module Gradle, Compose app shell, navigation, docs, first APK.
- [x] **Phase 2 – Match-3 engine**: matches 3/4/5/L/T/square, rocket/bomb/rainbow/plane + all combos,
      ice ×2, chains ×2, webs, crates ×3, stones ×2, locks + keys, delivery charms, portals, conveyors,
      gravity directions and shifts, multi-stage, shuffle, cascade cap, boosters; 55 tests incl. fuzzing.
- [x] **Phase 3 – Levels**: 20 chapters × 50 levels as JSON, generator from authored templates,
      bot calibration, validator, release-gate simulation; 21 tests over all 1000 levels.
- [x] **Phase 4 – Rewards**: coins, tokens, lootbox tiers, milestone boxes (5/10/50), chapter bonuses,
      odds + pity + guarantees, idempotent atomic grants.
- [x] **Phase 5 – Card database**: pack format, catalogue, validator (data honesty, image rights),
      3 starter collections (88 original-art cards), user pack import, user image import.
- [x] **Phase 6 – 2.5D renderer**: OpenGL ES 2.0 mesh with thickness, foil/holo/sparkle/paper/laminate,
      parallax, flip, rotate/zoom/pan/double-tap, fullscreen + hidden UI, gyroscope, quality levels.
- [x] **Phase 7 – Collection**: albums, progress, search, rarity/series/owned/duplicate/favourite filters,
      sorting, silhouettes, history, recycling (protected), token exchange, set rewards.
- [x] **Phase 8 – Polish**: daily/weekly/event quests, achievements, daily reward with clock-rollback
      protection, shop, cosmetics (card backs, frames, board themes), original audio, settings.
- [x] **Phase 9 – QA**: Room/migration tests, screenshot tests, shader harness, release build.

## Current / next
- [ ] Run on physical devices (several GPUs) to tune shader performance and gyroscope feel.
- [ ] Hand-polish a selection of showcase levels (e.g. first 10 of each chapter) on top of the generator.
- [ ] Add verified real cards when a reliable, citable data source and image licences are available.
- [ ] Localisation (strings are currently English in code).
- [ ] Optional: instrumented UI tests on a device farm.

## Release
* Debug: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
* Release: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
  Create `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) in the repo root to
  sign with your own key; without it the release build is signed with the debug key so it still installs.
* Regenerate levels: `./gradlew :levels:generateLevels` (deterministic). Regenerate audio:
  `python3 tools/synth_audio.py`. Regenerate card packs: `python3 tools/card_packs.py`.
