# Changelog

## 1.0.0 — 2026-11-10
First complete version.

### Added
* Match-3 engine (pure Kotlin): all match shapes, 4 specials and 7+ combos (incl. plane combos),
  11 obstacle variants, keys/locks, delivery items, portals, conveyors, gravity directions/shifts,
  multi-stage levels, deterministic RNG, shuffle with guaranteed move, cascade cap, 6 in-level/pre-level
  booster operations.
* 1000 levels in 20 themed chapters (JSON), generator from authored templates, bot calibration,
  validator and release-gate simulation.
* Card system: pack format with verification/image-rights fields, catalogue, validator, three starter
  collections (BTS, Spider-Man, TMNT tributes; original procedural art, clearly labelled).
* Lootboxes (Common 3 / Rare 5 / Epic 7 / Legendary 10 cards) with published odds, hard pity
  (Epic+ ≤ 25 cards, Legendary ≤ 80 cards) and box guarantees; contents fixed at grant time.
* Rewards: first-clear box on every level, Rare every 5th, Epic every 10th, Legendary every 50th,
  chapter completion bonus, album and collection completion rewards; replay coins.
* Economy: coins and collector tokens, recycling with last-copy and rare-card protection, token
  exchange for missing cards, shop (boosters, boxes, cosmetics, token→coin exchange).
* Daily reward (7-day cycle, clock-rollback protection), daily/weekly/seasonal quests, achievements,
  statistics, reward history.
* Persistence: Room (v2 with migration from v1), DataStore settings, atomic idempotent transactions,
  rolling JSON backups, corruption recovery, signed export/import with checksum and migration.
* 2.5D card viewer in OpenGL ES 2.0 with thickness, lighting, foil, holographic bands, sparkles,
  paper texture, laminate, parallax art layer, flip, gestures, fullscreen, gyroscope, quality levels.
* Compose UI: animated chapter scenes, map with 1000 nodes, level screen with animated board and
  effects, win/lose screens, collection, card detail, lootbox opening, shop, profile, settings.
* Original synthesised music and sound effects.

### Tests (all passing at release)
* engine: 55 · levels: 21 (over all 1000 levels) · game: 36 · app (Robolectric): 10 (Room parity,
  rollback, migration v1→v2, full level played through the ViewModel, animation watchdog,
  pre-level boosters, screenshots of 12 screens + card art). Total 122.
* Shader harness: both programs compile/link and render in WebGL (SwiftShader).
* Release APK built with R8; generated serializers and Room implementation verified present.

### Fixed during QA
* Duplicate level layouts across chapters → deterministic de-duplication pass in the generator.
* Levels the bot could not win on fresh seeds → release-gate simulation in the generator and tests.
* Over-strong holographic effect washing out text → band-based holo, matte text plates.
* Card footer text overlapping corner ornaments on long collection names.
* Navigation from a coroutine could resume off the main thread after a DB transaction → explicit
  switch to the main dispatcher before navigating.
* Rarity labels on the map were unreadable on light scenes → dark pill background.
