# Architecture

```
engine  (JVM)  ← levels (JVM) ← game (JVM) ← app (Android, Compose)
```

## Modules

### `engine` — Match3Engine (pure Kotlin, no dependencies)
* `Model.kt` — `Pos`, `Dir`, `Piece` (id, colour, kind, special), `Cell` (void, ice, chain, web, blocker),
  `Board` (mutable grid, `copy()` for snapshots).
* `Spec.kt` — `GameSpec` / `StageSpec` / `CellSpec` / `Goal`: what a level is made of.
* `MatchFinder` — horizontal/vertical runs, union of overlapping runs (L/T), 2×2 squares → shape →
  created special (4 → rocket, L/T → bomb, 5 → rainbow, square → paper plane).
* `Gravity` / `Topology` — flow graph per gravity direction with void skipping and portals; settling
  with spawning and diagonal sliding; guaranteed termination.
* `Resolver` — hit model (`MATCH`, `ADJACENT`, `BLAST`) for every obstacle, special activation queue,
  all combos, plane targeting.
* `Match3Game` — a play session: swaps, cascades (capped at 60, then matches are broken up),
  conveyors, gravity shifts, stages, win/lose, shuffle (permute → recolour → constructed move),
  boosters (hammer, super hammer, area clear, swap glove, shuffle, extra moves, pre-placed specials).
  Deterministic via `Rng` (SplitMix64). Returns `MoveResult.Applied(steps, events, status)`.

### `levels` — LevelSystem + LevelValidator
* `LevelModel.kt` — serialisable `LevelDefinition` / `StageDef` (layout rows in `LayoutCodec` token format).
* `Chapters` — 20 chapters (titles, mechanics introduced). `Shapes` — 20 authored board silhouettes.
* `LevelGenerator` — deterministic builder (difficulty curve, mechanic selection, patterns, goals).
* `BotPlayer` — greedy one-move-lookahead player. `LevelCalibrator` — move limits from simulations.
* `LevelValidator` — structure, goals reachability, portals/conveyors/gravity rules, spawn supply,
  start playability, simulation. `GenerateLevels.kt` — offline tool (`./gradlew :levels:generateLevels`).

### `game` — rules, rewards, economy, saves
* `cards/` — `CardDef` (all physical-card fields nullable, verification & image-rights status),
  `CardCatalog` (indexed, pack-based), `CardPackValidator`.
* `loot/LootboxEngine` — published odds, hard pity, box guarantees; pure function of (seed, pity).
* `economy/` — boosters, recycle/exchange values, shop catalogue, cosmetics.
* `progression/` — stats, daily/weekly/event quests, achievements, daily reward cycle, time math.
* `state/` — `GameStore` / `GameTx` transaction interface, `InMemoryGameStore` (reference implementation),
  `SaveData` + `SaveCodec` (signed JSON export/import, migrations, sanitising).
* `GameService` — every state-changing rule, each in exactly one transaction.

### `app` — Android
* `data/db` — Room entities/DAO, `AppDatabase` (v2, migration 1→2), `RoomGameStore` (implements `GameStore`).
* `data/` — `ContentRepository` (assets, user packs, user images), `GameRepository` (PlayerState flow,
  rolling backups, restore, export/import), `SettingsRepository` (DataStore).
* `cards/` — procedural card art (android.graphics) and an LRU bitmap cache (async).
* `renderer/` — `CardMesh`, `CardShaders` (GLSL ES 1.00), `CardRenderer` (GLSurfaceView.Renderer),
  `CardViewer` (Compose wrapper: gestures, gyroscope, lifecycle, quality levels).
* `audio/` — SoundPool SFX + MediaPlayer music, lifecycle-aware.
* `ui/` — theme, scenes, components; screens: map, level (board/VM), collection, card detail,
  lootboxes, shop, profile; `AppNavigation` (Navigation Compose, bottom bar).

## Data flow
```
User input → LevelViewModel → Match3Game.swap() → steps → BoardView animates step by step
Level end  → GameService.completeLevel()  ─┐
Open box   → GameService.openLootbox()     ├→ GameStore.transaction { … } → Room (one SQLite tx)
Shop/etc.  → GameService.*                ─┘          │ afterCommit
                                                       ├→ GameRepository.refresh() → StateFlow<PlayerState> → UI
                                                       └→ debounced backup (files/backups/autosave.json)
```

## Key interfaces
```kotlin
interface GameStore { suspend fun <T> transaction(block: (GameTx) -> T): T }
interface GameTx { fun claimOnce(key: String, at: Long): Boolean; fun wallet(): Wallet; … }
class Match3Game(spec: GameSpec, seed: Long) { fun swap(a: Pos, b: Pos): MoveResult; … }
class LootboxEngine(catalog) { fun draw(tier, seed, pity, owned, collectionId): DrawResult }
```

## Persistence
* Room tables: wallet, ledger (idempotency), level_progress, owned_cards, card_history, lootboxes,
  opened_lootboxes, boosters, meta (counters/pity/stats/quests/cosmetics), reward_log.
* Startup: `PRAGMA quick_check`; corrupted DB is moved aside and recreated; empty DB + valid backup →
  restore (newest, then previous generation).
* Settings in DataStore (volumes, mute, quality, gyroscope, haptics, reduced motion).

## Testing
* `engine`: matches, specials, combos, obstacles, gravity/portals/conveyors, flow, determinism, fuzzing.
* `levels`: all 1000 levels — structure, start, release-gate simulation, fresh-seed health, rules.
* `game`: odds/pity statistics, idempotency, rollback, recycling protection, daily anti-rollback,
  quests, saves/migration, 1000-level playthrough invariants.
* `app` (Robolectric): Room store parity, rollback, migration v1→v2, screenshots of every main screen.
* Shaders: compiled/linked and rendered in headless Chromium WebGL (`tools/shader_check/`).
