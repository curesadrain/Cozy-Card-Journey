package com.cozycards.game

import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.cards.Rarity
import com.cozycards.game.economy.BoosterKind
import com.cozycards.game.economy.BoosterType
import com.cozycards.game.economy.Cosmetic
import com.cozycards.game.economy.CosmeticSlot
import com.cozycards.game.economy.Economy
import com.cozycards.game.economy.ShopItem
import com.cozycards.game.loot.LootboxEngine
import com.cozycards.game.progression.AchievementDef
import com.cozycards.game.progression.Achievements
import com.cozycards.game.progression.DailyReward
import com.cozycards.game.progression.DailyRewards
import com.cozycards.game.progression.EventCalendar
import com.cozycards.game.progression.GameClock
import com.cozycards.game.progression.QuestDef
import com.cozycards.game.progression.QuestPeriod
import com.cozycards.game.progression.Quests
import com.cozycards.game.progression.Stat
import com.cozycards.game.progression.TimeMath
import com.cozycards.game.state.CardDraw
import com.cozycards.game.state.CardHistoryEntry
import com.cozycards.game.state.GameStore
import com.cozycards.game.state.GameTx
import com.cozycards.game.state.LevelProgress
import com.cozycards.game.state.OpenedLootbox
import com.cozycards.game.state.OwnedCard
import com.cozycards.game.state.PendingLootbox
import com.cozycards.game.state.PityState
import com.cozycards.game.state.RewardLogEntry
import com.cozycards.game.state.addCoins
import com.cozycards.game.state.addTokens
import com.cozycards.game.state.incrementMeta
import com.cozycards.game.state.metaInt
import com.cozycards.game.state.metaLong
import com.cozycards.game.state.nextId
import com.cozycards.game.state.setMetaLong
import com.cozycards.game.state.spend
import com.cozycards.levels.Chapters
import com.cozycards.levels.LevelDefinition
import com.cozycards.levels.LootboxTier
import kotlin.math.roundToLong

class GameRuleException(message: String) : IllegalStateException(message)

/** Result of a finished level, produced by the level controller. */
data class LevelResult(
    val levelId: String,
    val won: Boolean,
    val stars: Int,
    val score: Int,
    val movesLeft: Int,
    val piecesCleared: Int,
    val specialsCreated: Int,
    val combos: Int,
    val obstaclesCleared: Int,
)

data class RewardSummary(
    val firstClear: Boolean,
    val coins: Long,
    val tokens: Long,
    val lootboxes: List<PendingLootbox>,
    val unlockedLevel: Int?,
    val chapterCompleted: Int?,
    val newBestStars: Boolean,
    val eventBonusPercent: Int,
)

data class OpenResult(val box: PendingLootbox, val draws: List<CardDraw>, val tokensFromAlbums: Long, val completedAlbums: List<String>)

data class QuestStatus(val def: QuestDef, val progress: Long, val claimed: Boolean, val periodKey: String) {
    val complete get() = progress >= def.target
}

data class DailyStatus(val day: Long, val canClaim: Boolean, val streak: Int, val nextReward: DailyReward, val clockTampered: Boolean)

/**
 * All state-changing game rules. Every public operation runs inside exactly one store
 * transaction, so rewards are granted atomically and idempotency keys prevent double grants
 * even if the app is killed and the same request is replayed.
 */
class GameService(
    private val store: GameStore,
    val catalog: CardCatalog,
    levels: List<LevelDefinition>,
    private val clock: GameClock,
) {
    private val levelsById = levels.associateBy { it.id }
    private val levelsByNumber = levels.associateBy { it.number }
    private val loot = LootboxEngine(catalog)

    companion object {
        const val STARTER_COINS = 500L
        const val TIME_TOLERANCE = 10 * 60_000L
        const val KEY_HIGHEST_CLEARED = "progress.highestCleared"
        const val KEY_PITY_EPIC = "pity.sinceEpicPlus"
        const val KEY_PITY_LEGENDARY = "pity.sinceLegendary"
        const val KEY_TIME_HIGH = "time.highWater"
        fun statKey(s: Stat) = "stat.${s.name}"
        fun equippedKey(slot: CosmeticSlot) = "equipped.${slot.name}"
    }

    fun level(id: String): LevelDefinition? = levelsById[id]
    fun level(number: Int): LevelDefinition? = levelsByNumber[number]

    // ------------------------------------------------------------------ setup

    suspend fun initialize() = store.transaction { tx ->
        if (tx.claimOnce("init.starter", clock.now())) {
            tx.addCoins(STARTER_COINS)
            BoosterType.entries.forEach { tx.setBoosterCount(it, it.starterAmount) }
            CosmeticSlot.entries.forEach { slot ->
                val def = Cosmetic.defaultFor(slot)
                tx.claimOnce("cosmetic.${def.name}", clock.now())
                tx.setMeta(equippedKey(slot), def.name)
            }
            tx.addRewardLog(RewardLogEntry("init.starter", clock.now(), "Welcome gift", STARTER_COINS))
        }
    }

    // ------------------------------------------------------------------ levels

    fun isUnlocked(tx: GameTx, number: Int): Boolean = number == 1 || number <= tx.metaInt(KEY_HIGHEST_CLEARED) + 1

    /** Consumes the selected pre-level boosters atomically. Throws if a booster is missing. */
    suspend fun startLevel(levelId: String, boosters: Set<BoosterType>) = store.transaction { tx ->
        val def = levelsById[levelId] ?: throw GameRuleException("Unknown level $levelId")
        if (!isUnlocked(tx, def.number)) throw GameRuleException("Level ${def.number} is locked")
        for (b in boosters) {
            if (b.kind != BoosterKind.PRE_LEVEL) throw GameRuleException("${b.title} can't be used before a level")
            consumeBooster(tx, b)
        }
        val p = tx.levelProgress(levelId) ?: LevelProgress(levelId)
        tx.putLevelProgress(p.copy(plays = p.plays + 1))
        bumpStat(tx, Stat.LEVELS_PLAYED, 1)
        if (boosters.isNotEmpty()) bumpStat(tx, Stat.BOOSTERS_USED, boosters.size.toLong())
    }

    suspend fun useInLevelBooster(type: BoosterType) = store.transaction { tx ->
        if (type.kind != BoosterKind.IN_LEVEL) throw GameRuleException("${type.title} is a pre-level booster")
        consumeBooster(tx, type)
        bumpStat(tx, Stat.BOOSTERS_USED, 1)
    }

    private fun consumeBooster(tx: GameTx, type: BoosterType) {
        val n = tx.boosterCount(type)
        if (n <= 0) throw GameRuleException("No ${type.title} left")
        tx.setBoosterCount(type, n - 1)
    }

    suspend fun completeLevel(result: LevelResult): RewardSummary = store.transaction { tx ->
        val def = levelsById[result.levelId] ?: throw GameRuleException("Unknown level ${result.levelId}")
        val now = clock.now()
        val old = tx.levelProgress(def.id) ?: LevelProgress(def.id)
        bumpStat(tx, Stat.PIECES_CLEARED, result.piecesCleared.toLong())
        bumpStat(tx, Stat.SPECIALS_CREATED, result.specialsCreated.toLong())
        bumpStat(tx, Stat.COMBOS, result.combos.toLong())
        bumpStat(tx, Stat.OBSTACLES_CLEARED, result.obstaclesCleared.toLong())
        if (!result.won) {
            return@transaction RewardSummary(false, 0, 0, emptyList(), null, null, false, 0)
        }
        val stars = result.stars.coerceIn(1, 3)
        tx.putLevelProgress(
            old.copy(
                bestStars = maxOf(old.bestStars, stars),
                bestScore = maxOf(old.bestScore, result.score),
                wins = old.wins + 1,
                firstClearedAt = old.firstClearedAt ?: now,
            )
        )
        bumpStat(tx, Stat.LEVELS_WON, 1)
        val newStars = (stars - old.bestStars).coerceAtLeast(0)
        if (newStars > 0) bumpStat(tx, Stat.STARS_EARNED, newStars.toLong())
        if (stars == 3) bumpStat(tx, Stat.THREE_STAR_WINS, 1)

        val bonusPercent = activeCoinBonus()
        val boxes = mutableListOf<PendingLootbox>()
        var coins: Long
        var unlocked: Int? = null
        var chapterDone: Int? = null
        val first = tx.claimOnce("level.first.${def.id}", now)
        if (first) {
            coins = def.reward.coins + stars * Economy.COINS_PER_STAR + result.movesLeft.coerceAtMost(20) * Economy.COINS_PER_MOVE_LEFT
            boxes += grantBox(tx, def.reward.lootbox, "Level ${def.number}")
            val highest = tx.metaInt(KEY_HIGHEST_CLEARED)
            if (def.number > highest) tx.setMetaLong(KEY_HIGHEST_CLEARED, def.number.toLong())
            if (def.number < Chapters.TOTAL_LEVELS) unlocked = def.number + 1
            val chapter = Chapters.all[def.chapter - 1]
            val chapterCleared = (chapter.firstLevel..chapter.lastLevel).all { n ->
                tx.isClaimed("level.first.${Chapters.levelId(n)}")
            }
            if (chapterCleared && tx.claimOnce("chapter.complete.${def.chapter}", now)) {
                chapterDone = def.chapter
                val bonus = Economy.chapterCompleteCoins(def.chapter)
                coins += bonus
                boxes += grantBox(tx, Economy.chapterCompleteBox(def.chapter), "Chapter ${def.chapter} complete")
                bumpStat(tx, Stat.CHAPTERS_COMPLETED, 1)
            }
        } else {
            // Replays stay worthwhile but never required: a small fixed amount.
            coins = Economy.REPLAY_COINS + newStars * Economy.COINS_PER_STAR * 4
        }
        coins = (coins * (100 + bonusPercent) / 100.0).roundToLong()
        tx.addCoins(coins)
        bumpStat(tx, Stat.COINS_EARNED, coins)
        tx.addRewardLog(RewardLogEntry("level.${def.id}.${tx.metaLong("seq.reward") + 1}", now, if (first) "First clear: level ${def.number}" else "Replay: level ${def.number}", coins))
        tx.incrementMeta("seq.reward")
        RewardSummary(first, coins, 0, boxes, unlocked, chapterDone, stars > old.bestStars, bonusPercent)
    }

    private fun activeCoinBonus(): Int {
        val day = TimeMath.dayIndex(clock.now(), clock.zoneOffsetMillis())
        return EventCalendar.active(TimeMath.localDate(day)).sumOf { it.coinBonusPercent }.coerceAtMost(50)
    }

    // ------------------------------------------------------------------ lootboxes

    private fun grantBox(tx: GameTx, tier: LootboxTier, source: String, collectionId: String? = null): PendingLootbox {
        val id = tx.nextId("lootbox")
        // Seed derived from id and a per-install salt: deterministic for this grant, unknowable in advance.
        val salt = tx.meta("install.salt")?.toLong() ?: (clock.now() xor 0x2545F4914F6CDD1DL).also { tx.setMeta("install.salt", it.toString()) }
        val box = PendingLootbox(id, tier, salt * 31 + id * 1_000_003, source, clock.now(), collectionId)
        tx.addLootbox(box)
        return box
    }

    fun pity(tx: GameTx) = PityState(tx.metaInt(KEY_PITY_EPIC), tx.metaInt(KEY_PITY_LEGENDARY))

    /** Opens one box: removal, card grants, pity update and history happen in one transaction. */
    suspend fun openLootbox(boxId: Long): OpenResult = store.transaction { tx -> openInTx(tx, boxId) }

    suspend fun openAll(limit: Int = 50): List<OpenResult> = store.transaction { tx ->
        tx.lootboxes().sortedBy { it.id }.take(limit).map { openInTx(tx, it.id) }
    }

    private fun openInTx(tx: GameTx, boxId: Long): OpenResult {
        val box = tx.lootboxes().firstOrNull { it.id == boxId } ?: throw GameRuleException("Lootbox $boxId was already opened")
        val now = clock.now()
        val result = loot.draw(box.tier, box.seed, pity(tx), { tx.ownedCard(it) != null }, box.collectionId)
        check(tx.removeLootbox(boxId))
        tx.setMetaLong(KEY_PITY_EPIC, result.pity.sinceEpicPlus.toLong())
        tx.setMetaLong(KEY_PITY_LEGENDARY, result.pity.sinceLegendary.toLong())
        val (tokens, albums) = addCards(tx, result.draws.map { it.cardId }, box.source, now)
        tx.addOpenedLootbox(OpenedLootbox(box.id, box.tier, box.source, now, result.draws))
        bumpStat(tx, Stat.LOOTBOXES_OPENED, 1)
        return OpenResult(box, result.draws, tokens, albums)
    }

    /** Adds cards and grants album/collection completion rewards. Returns (tokens, completed album ids). */
    private fun addCards(tx: GameTx, cardIds: List<String>, source: String, now: Long): Pair<Long, List<String>> {
        for (id in cardIds) {
            catalog.require(id)
            val owned = tx.ownedCard(id)
            if (owned == null) {
                tx.putOwnedCard(OwnedCard(id, 1, now, now))
                bumpStat(tx, Stat.NEW_CARDS, 1)
            } else {
                tx.putOwnedCard(owned.copy(count = owned.count + 1, lastObtainedAt = now))
            }
            tx.addCardHistory(CardHistoryEntry(id, now, source))
        }
        bumpStat(tx, Stat.CARDS_RECEIVED, cardIds.size.toLong())
        var tokens = 0L
        val completed = mutableListOf<String>()
        val touchedCollections = cardIds.mapNotNull { catalog[it]?.collectionId }.toSet()
        for (cid in touchedCollections) {
            val coll = catalog.collection(cid) ?: continue
            for (album in coll.albums) {
                if (album.cardIds.all { tx.ownedCard(it) != null } && tx.claimOnce("album.${album.id}", now)) {
                    tx.addTokens(Economy.ALBUM_COMPLETE_TOKENS)
                    tx.addCoins(Economy.ALBUM_COMPLETE_COINS)
                    tokens += Economy.ALBUM_COMPLETE_TOKENS
                    completed += album.id
                    tx.addRewardLog(RewardLogEntry("album.${album.id}", now, "Album complete: ${album.name}", Economy.ALBUM_COMPLETE_COINS, Economy.ALBUM_COMPLETE_TOKENS))
                }
            }
            if (catalog.inCollection(cid).all { tx.ownedCard(it.id) != null } && tx.claimOnce("collection.$cid", now)) {
                grantBox(tx, LootboxTier.LEGENDARY, "Collection complete: ${coll.name}")
                tx.addRewardLog(RewardLogEntry("collection.$cid", now, "Collection complete: ${coll.name}"))
            }
        }
        return tokens to completed
    }

    // ------------------------------------------------------------------ collection

    /**
     * Recycles [amount] duplicates of a card into tokens. The last copy can never be recycled,
     * and Epic/Legendary cards require [confirmRare] (the UI asks the player explicitly).
     */
    suspend fun recycle(cardId: String, amount: Int, confirmRare: Boolean = false): Long = store.transaction { tx ->
        val card = catalog.require(cardId)
        if (amount <= 0) throw GameRuleException("Nothing to recycle")
        val owned = tx.ownedCard(cardId) ?: throw GameRuleException("You don't own this card")
        if (amount > owned.duplicates) throw GameRuleException("Only ${owned.duplicates} duplicate(s) available; your last copy is protected")
        if (card.gameRarity.atLeast(Rarity.EPIC) && !confirmRare) throw GameRuleException("Recycling ${card.gameRarity.label} cards needs confirmation")
        if (owned.favorite && !confirmRare) throw GameRuleException("Favourite cards need confirmation")
        tx.putOwnedCard(owned.copy(count = owned.count - amount))
        val tokens = Economy.recycleValue(card.gameRarity) * amount
        tx.addTokens(tokens)
        bumpStat(tx, Stat.DUPLICATES_RECYCLED, amount.toLong())
        tokens
    }

    /** Recycles all Common and Rare duplicates (never favourites, never the last copy). */
    suspend fun recycleSafeDuplicates(): Long = store.transaction { tx ->
        var tokens = 0L
        var n = 0L
        for (owned in tx.allOwnedCards()) {
            val card = catalog[owned.cardId] ?: continue
            if (owned.favorite || card.gameRarity.atLeast(Rarity.EPIC) || owned.duplicates == 0) continue
            tokens += Economy.recycleValue(card.gameRarity) * owned.duplicates
            n += owned.duplicates
            tx.putOwnedCard(owned.copy(count = 1))
        }
        if (tokens > 0) tx.addTokens(tokens)
        if (n > 0) bumpStat(tx, Stat.DUPLICATES_RECYCLED, n)
        tokens
    }

    /** Buys a specific missing card with tokens. */
    suspend fun exchangeForCard(cardId: String): OpenResult = store.transaction { tx ->
        val card = catalog.require(cardId)
        if (tx.ownedCard(cardId) != null) throw GameRuleException("You already own ${card.name}")
        tx.spend(tokens = Economy.exchangePrice(card.gameRarity))
        val now = clock.now()
        val (tokens, albums) = addCards(tx, listOf(cardId), "Token exchange", now)
        OpenResult(PendingLootbox(-1, LootboxTier.COMMON, 0, "Token exchange", now), listOf(CardDraw(cardId, card.gameRarity, true)), tokens, albums)
    }

    suspend fun setFavorite(cardId: String, favorite: Boolean) = store.transaction { tx ->
        val owned = tx.ownedCard(cardId) ?: throw GameRuleException("You don't own this card")
        tx.putOwnedCard(owned.copy(favorite = favorite))
    }

    // ------------------------------------------------------------------ shop

    suspend fun buy(item: ShopItem): PendingLootbox? = store.transaction { tx ->
        when (item) {
            is ShopItem.Style -> if (tx.isClaimed("cosmetic.${item.cosmetic.name}")) throw GameRuleException("Already owned")
            is ShopItem.Box -> if (item.collectionId != null && catalog.collection(item.collectionId) == null) throw GameRuleException("Unknown collection")
            else -> Unit
        }
        tx.spend(item.coins, item.tokens)
        when (item) {
            is ShopItem.Booster -> tx.setBoosterCount(item.type, tx.boosterCount(item.type) + item.amount).let { null }
            is ShopItem.Box -> grantBox(tx, item.tier, "Shop: ${item.title}", item.collectionId)
            is ShopItem.Style -> {
                tx.claimOnce("cosmetic.${item.cosmetic.name}", clock.now()); null
            }
            is ShopItem.CoinPack -> tx.addCoins(item.coinsOut).let { null }
        }
    }

    suspend fun equip(cosmetic: Cosmetic) = store.transaction { tx ->
        if (!tx.isClaimed("cosmetic.${cosmetic.name}")) throw GameRuleException("${cosmetic.title} is not owned")
        tx.setMeta(equippedKey(cosmetic.slot), cosmetic.name)
    }

    // ------------------------------------------------------------------ time, daily, quests

    /**
     * Returns the effective day and whether the clock was moved backwards. The day never goes
     * backwards: it's based on the highest time ever observed.
     */
    private fun observeTime(tx: GameTx): Pair<Long, Boolean> {
        val now = clock.now()
        val high = tx.metaLong(KEY_TIME_HIGH)
        val tampered = high > 0 && now + TIME_TOLERANCE < high
        if (now > high) tx.setMetaLong(KEY_TIME_HIGH, now)
        val effective = maxOf(now, high)
        return TimeMath.dayIndex(effective, clock.zoneOffsetMillis()) to tampered
    }

    suspend fun dailyStatus(): DailyStatus = store.transaction { tx -> dailyStatusIn(tx) }

    private fun dailyStatusIn(tx: GameTx): DailyStatus {
        val (day, tampered) = observeTime(tx)
        val last = tx.metaLong("daily.lastDay", -10)
        val streak = tx.metaInt("daily.streak")
        val nextStreak = if (last == day - 1) streak + 1 else 1
        val canClaim = !tampered && !tx.isClaimed("daily.$day")
        val shown = if (canClaim) nextStreak else streak.coerceAtLeast(1)
        return DailyStatus(day, canClaim, if (last >= day - 1) streak else 0, DailyRewards.cycle[(shown - 1) % DailyRewards.cycle.size], tampered)
    }

    suspend fun claimDaily(): DailyReward = store.transaction { tx ->
        val status = dailyStatusIn(tx)
        if (status.clockTampered) throw GameRuleException("Your device clock moved backwards. Daily rewards resume once it catches up.")
        if (!tx.claimOnce("daily.${status.day}", clock.now())) throw GameRuleException("Today's reward was already claimed")
        val last = tx.metaLong("daily.lastDay", -10)
        val streak = if (last == status.day - 1) tx.metaInt("daily.streak") + 1 else 1
        tx.setMetaLong("daily.lastDay", status.day)
        tx.setMetaLong("daily.streak", streak.toLong())
        val reward = DailyRewards.cycle[(streak - 1) % DailyRewards.cycle.size]
        applyDailyReward(tx, reward)
        bumpStat(tx, Stat.DAILY_CLAIMS, 1)
        tx.addRewardLog(RewardLogEntry("daily.${status.day}", clock.now(), "Daily reward day $streak"))
        reward
    }

    private fun applyDailyReward(tx: GameTx, r: DailyReward) {
        when (r) {
            is DailyReward.Coins -> tx.addCoins(r.amount)
            is DailyReward.Tokens -> tx.addTokens(r.amount)
            is DailyReward.Booster -> tx.setBoosterCount(r.type, tx.boosterCount(r.type) + r.amount)
            is DailyReward.Box -> grantBox(tx, r.tier, "Daily reward")
        }
    }

    private fun activeQuests(tx: GameTx): List<Pair<QuestDef, String>> {
        val (day, _) = observeTime(tx)
        val week = TimeMath.weekIndex(day)
        val date = TimeMath.localDate(day)
        return Quests.daily(day).map { it to "d$day" } +
            Quests.weekly(week).map { it to "w$week" } +
            EventCalendar.active(date).mapNotNull { e -> e.quest?.let { it to EventCalendar.occurrenceKey(e, date) } }
    }

    private fun bumpStat(tx: GameTx, stat: Stat, by: Long) {
        if (by <= 0) return
        tx.incrementMeta(statKey(stat), by)
        for ((q, period) in activeQuests(tx)) {
            if (q.stat == stat) tx.incrementMeta("quest.$period.${q.id}", by)
        }
    }

    suspend fun quests(): List<QuestStatus> = store.transaction { tx ->
        activeQuests(tx).map { (q, period) ->
            QuestStatus(q, tx.metaLong("quest.$period.${q.id}"), tx.isClaimed("questclaim.$period.${q.id}"), period)
        }
    }

    suspend fun claimQuest(questId: String): QuestStatus = store.transaction { tx ->
        val (q, period) = activeQuests(tx).firstOrNull { it.first.id == questId } ?: throw GameRuleException("Quest is not active")
        val progress = tx.metaLong("quest.$period.${q.id}")
        if (progress < q.target) throw GameRuleException("Quest not complete yet")
        if (!tx.claimOnce("questclaim.$period.${q.id}", clock.now())) throw GameRuleException("Already claimed")
        if (q.coins > 0) tx.addCoins(q.coins)
        if (q.tokens > 0) tx.addTokens(q.tokens)
        q.box?.let { grantBox(tx, it, "Quest: ${q.title}") }
        tx.addRewardLog(RewardLogEntry("quest.$period.${q.id}", clock.now(), "Quest: ${q.title}", q.coins, q.tokens))
        QuestStatus(q, progress, true, period)
    }

    data class AchievementStatus(val def: AchievementDef, val progress: Long, val claimed: Boolean) {
        val unlocked get() = progress >= def.threshold
    }

    suspend fun achievements(): List<AchievementStatus> = store.transaction { tx ->
        Achievements.all.map { AchievementStatus(it, tx.metaLong(statKey(it.stat)), tx.isClaimed("ach.${it.id}")) }
    }

    suspend fun claimAchievement(id: String) = store.transaction { tx ->
        val def = Achievements.all.firstOrNull { it.id == id } ?: throw GameRuleException("Unknown achievement")
        if (tx.metaLong(statKey(def.stat)) < def.threshold) throw GameRuleException("Not unlocked yet")
        if (!tx.claimOnce("ach.$id", clock.now())) throw GameRuleException("Already claimed")
        if (def.coins > 0) tx.addCoins(def.coins)
        if (def.tokens > 0) tx.addTokens(def.tokens)
        tx.addRewardLog(RewardLogEntry("ach.$id", clock.now(), "Achievement: ${def.title}", def.coins, def.tokens))
    }

    fun questPeriodLabel(p: QuestPeriod) = when (p) {
        QuestPeriod.DAILY -> "Daily"
        QuestPeriod.WEEKLY -> "Weekly"
        QuestPeriod.EVENT -> "Event"
    }
}
