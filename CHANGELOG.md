# Changelog

## 1.3.0 — 2026-11-11
Second feedback round.

### Changed
* New icon art: booster tools, navigation tabs and four lootbox chests (sheet 7), used on the board,
  pre-level picker, shop, map milestones, win screen and lootbox opening.
* Swipes off the board are ignored entirely (no text); invalid swaps only wiggle and play a sound.
* Floating navigation: no plate behind the buttons; content scrolls underneath; hidden during
  full-screen overlays (lootbox opening).
* Map background stays fixed behind the path and cross-fades to the next chapter's art as its gate
  scrolls through the screen.
* Lootbox history moved behind a "History" button.
* Collection: compact one-line dropdown filters (rarity, owned/favourites, series, sort) with a search
  toggle; fixed the rectangular ripple artefact on the selected collection tab; custom progress bars.

## 1.2.0 — 2026-11-11
Feedback round after device testing.

### Changed
* **Specials are colourless and fire on their own**: swiping a rocket/bomb/plane/rainbow in any
  direction fires it at the cell it was moved to (no match needed); swiping it towards the edge or a
  blocked cell, or double-tapping it, fires it in place. Specials no longer take part in colour
  matches, so the colour badge on them is gone. Levels were re-calibrated for the new rules.
* **Unified design system** (`ui/common/Design.kt`): cream plates with gold rims, glossy sprite buttons
  (primary pink, secondary lavender, neutral cream), selectable chips, gold-rimmed medallions and
  badges; used by panels, dialogs, filters, profile tabs, bottom navigation, booster bars.
* **Map**: bottom-up winding path over a continuous chapter illustration, glossy level medallions
  (current level pulsing with a "Play" marker, cleared levels gold with stars, locked levels muted),
  reward chests next to milestone levels, chapter gates with text on an opaque plate.
* Booster buttons reuse the rocket/bomb/rainbow art; the remaining tool icons are placeholders until
  dedicated art is supplied.

### Tests
* engine 60 (+5 activation rules) · levels 21 · game 36 · app 11 — 128 total, all passing.

## 1.1.0 — 2026-11-11
Visual and game-feel overhaul.

### Added
* Generated art set (tiles, specials, obstacles, board cells, portal, conveyor, particles, UI parts) and
  20 painted chapter backgrounds; sources in `art/source`, processed by `tools/process_art.py`
  (chroma key with colour un-mixing, sheet splitting, WebP export).
* Animation director (`StepPlanner`): per-piece timing instead of whole-board phases — rockets sweep
  their line cell by cell, bombs ripple outwards, rainbows strike targets one after another, planes hit
  on landing, matched tiles flash and pop, tiles of a special-creating match slide into it,
  gravity falls with column stagger and squash-and-stretch landings, invalid swaps wiggle back.
* Effects layer (`Fx`) independent of board steps: themed particle bursts per tile colour, tumbling
  debris for crates/stones/ice/chains, smoke, glows, shockwave rings, lightning beams, rocket trails,
  screen shake, floating cascade words ("Nice!", "Great!", "Amazing!"…), win confetti.
* Collected tiles and destroyed obstacles fly in an arc to their goal counter; counters drop and bounce
  on arrival.
* New level HUD (moves medallion, goal plate), chapter background art on the level and map screens,
  chapter header art, sprite currency icons, stars and glossy buttons.

### Changed
* The board renders with sprites in a hardware-accelerated Compose Canvas (particle budget scales with
  the graphics quality setting) rather than the planned separate OpenGL board renderer: lower risk,
  same draw path for UI and board, and sufficient for ~700 particles.

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
* engine: 55 · levels: 21 (over all 1000 levels) · game: 36 · app (Robolectric): 11 (Room parity,
  rollback, migration v1→v2, full level played through the ViewModel, animation watchdog,
  pre-level boosters, board stage-change rendering, screenshots of 12 screens + card art). Total 123.
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
* Stage change to a board of a different size would index out of bounds in the board animation.
* Opening an empty set of lootboxes could crash the opening overlay.
* Concurrent state refreshes could publish an older snapshot last → refreshes are serialised.
