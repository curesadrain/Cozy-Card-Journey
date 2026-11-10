# Cozy Card Journey — Project Context

## Concept
Offline Android match-3 puzzle game with collectible trading-card rewards, in a cozy Japanese/Korean
aesthetic (sakura streets, cafés, lantern festivals, temples, winter lights). The player clears levels
on a 20-chapter map, earns coins and a guaranteed lootbox on every first clear, opens boxes, and builds
card collections viewed in a 2.5D OpenGL card viewer.

* Platform: Android 10+ (minSdk 29, targetSdk 35), Kotlin, Jetpack Compose, portrait only.
* Fully offline. No lives, no energy, no timers, no ads, no real-money purchases.
* Output: installable APK (`./gradlew :app:assembleDebug` or `:app:assembleRelease`).

## Key requirements (from the brief) and where they live
| Requirement | Implementation |
|---|---|
| Independent, deterministic match-3 engine | `engine/` (pure Kotlin, no Android) |
| Specials, all combos, 11 obstacle kinds, portals, conveyors, gravity, multi-stage | `engine/` |
| 1000 levels / 20 chapters, data format, validator, simulations | `levels/` + `app/src/main/assets/levels/*.json` |
| Cards, rarities, lootboxes, pity, rewards, economy, quests, saves | `game/` |
| Room + DataStore persistence, atomic rewards, backups, migration | `app/.../data/` |
| 2.5D card renderer (OpenGL ES 2.0) | `app/.../renderer/` |
| UI (Map, Collection, Lootboxes, Shop, Profile, Level) | `app/.../ui/` |
| Original audio | `tools/synth_audio.py` → `app/src/main/res/raw/` |

## Current status (v1.0.0)
All phases 1–9 implemented. The app builds to an APK; all JVM and Robolectric tests pass
(see CHANGELOG for counts). Screens are verified by Robolectric screenshot tests; the GL shaders are
compiled and rendered in a WebGL harness (`tools/shader_check/`). **No physical device or emulator was
available in the build environment** (no KVM), so on-device behaviour (GL driver specifics, gyroscope,
audio latency, real performance) has not been observed directly.

## Decisions
* **Multi-module Gradle**: `engine` → `levels` → `game` are plain JVM modules; `app` is the only
  Android module. This enforces "no Compose in game logic" at compile time and keeps tests fast.
* **Engine API returns visual steps**: a move is resolved completely and synchronously; the UI only
  animates `Step` snapshots. The UI can never block the game: a watchdog advances animations.
* **Levels are generated offline, then shipped as data**. Each level is built deterministically from
  authored ingredients (chapter themes and mechanics, 20 hand-drawn board silhouettes, named obstacle
  patterns, difficulty curve with breather/boss levels). Move limits are calibrated by a greedy bot.
  The level files are honest about this: each level records its `template` and bot `metrics`.
  The JSON is the source of truth at runtime and can be edited by hand.
* **Release gate**: every level is validated structurally, must start with a valid move on several
  seeds, and must be won by the bot at least once on fixed gate seeds (constructive proof of
  solvability). `LevelCatalogTest` re-runs all of this on every test run.
* **Rewards are idempotent** through a ledger table (`claimOnce(key)`), and every operation runs in one
  SQLite transaction. Lootbox contents are fixed by a seed stored with the box, so killing the app can
  neither duplicate nor reroll rewards.
* **Card data honesty**: no physical-card facts are invented. Starter cards are `ORIGINAL_ART` with
  `null` manufacturer/year/number/original rarity, procedural illustrations, and disclaimers.
  `CardPackValidator` refuses packs that claim real data without a source or bundle images without
  cleared rights. Players can import their own images locally (stored privately, never shared).
* **OpenGL ES 2.0 for the card viewer** (not Compose `graphicsLayer`): real geometry with thickness
  and side band, per-pixel lighting, foil/holo/sparkle/paper/laminate shading, parallax art layer.
  ES 2.0 was chosen over ES 3.0 for maximum device compatibility; textures are power-of-two.
* **Audio is synthesised** (numpy + ffmpeg) — original and royalty-free.
* **Google's Maven Central mirror** is listed before `mavenCentral()` to avoid HTTP 429 rate limits.

## Known limitations
* Not run on a real device/emulator in this environment (see above).
* Starter collections contain 88 original-art cards; no verified real cards are included because no
  verified data source was available. The pack format supports verified cards with sources.
* Level layouts are procedurally assembled from authored templates, not drawn one by one.
* Bot-calibrated difficulty approximates human difficulty; the greedy bot plays one move ahead.
* Release signing uses the debug key unless `keystore.properties` is provided (see README section in
  ROADMAP "Release").
* Process death mid-level restarts the level (no penalty; rewards are only granted on completion).
