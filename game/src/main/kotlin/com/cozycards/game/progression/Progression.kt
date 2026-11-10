package com.cozycards.game.progression

import com.cozycards.engine.Rng
import com.cozycards.levels.LootboxTier
import java.time.DayOfWeek
import java.time.LocalDate

interface GameClock {
    fun now(): Long
    /** Offset of the player's local time zone from UTC. */
    fun zoneOffsetMillis(): Long
}

object TimeMath {
    const val DAY = 86_400_000L
    fun dayIndex(epochMillis: Long, offset: Long) = Math.floorDiv(epochMillis + offset, DAY)
    /** Weeks start on Monday. Epoch day 0 (1970-01-01) was a Thursday. */
    fun weekIndex(day: Long) = Math.floorDiv(day + 3, 7)
    fun localDate(day: Long): LocalDate = LocalDate.ofEpochDay(day)
}

enum class Stat(val label: String) {
    LEVELS_PLAYED("Levels played"),
    LEVELS_WON("Levels won"),
    STARS_EARNED("Stars earned"),
    THREE_STAR_WINS("Three-star wins"),
    PIECES_CLEARED("Tiles cleared"),
    SPECIALS_CREATED("Specials created"),
    COMBOS("Special combos"),
    OBSTACLES_CLEARED("Obstacles cleared"),
    LOOTBOXES_OPENED("Lootboxes opened"),
    CARDS_RECEIVED("Cards received"),
    NEW_CARDS("New cards discovered"),
    DUPLICATES_RECYCLED("Duplicates recycled"),
    BOOSTERS_USED("Boosters used"),
    COINS_EARNED("Coins earned"),
    DAILY_CLAIMS("Daily rewards claimed"),
    CHAPTERS_COMPLETED("Chapters completed"),
}

enum class QuestPeriod { DAILY, WEEKLY, EVENT }

data class QuestDef(
    val id: String,
    val period: QuestPeriod,
    val title: String,
    val stat: Stat,
    val target: Long,
    val coins: Long = 0,
    val tokens: Long = 0,
    val box: LootboxTier? = null,
)

object Quests {
    val dailyPool = listOf(
        QuestDef("d_win3", QuestPeriod.DAILY, "Win 3 levels", Stat.LEVELS_WON, 3, coins = 120),
        QuestDef("d_play5", QuestPeriod.DAILY, "Play 5 levels", Stat.LEVELS_PLAYED, 5, coins = 100),
        QuestDef("d_clear300", QuestPeriod.DAILY, "Clear 300 tiles", Stat.PIECES_CLEARED, 300, coins = 120),
        QuestDef("d_special8", QuestPeriod.DAILY, "Create 8 special tiles", Stat.SPECIALS_CREATED, 8, coins = 130),
        QuestDef("d_combo1", QuestPeriod.DAILY, "Fire a special combo", Stat.COMBOS, 1, coins = 100),
        QuestDef("d_box2", QuestPeriod.DAILY, "Open 2 lootboxes", Stat.LOOTBOXES_OPENED, 2, tokens = 10),
        QuestDef("d_stars6", QuestPeriod.DAILY, "Earn 6 stars", Stat.STARS_EARNED, 6, coins = 140),
        QuestDef("d_obst40", QuestPeriod.DAILY, "Clear 40 obstacles", Stat.OBSTACLES_CLEARED, 40, coins = 140),
    )
    val weeklyPool = listOf(
        QuestDef("w_win20", QuestPeriod.WEEKLY, "Win 20 levels", Stat.LEVELS_WON, 20, coins = 600, box = LootboxTier.RARE),
        QuestDef("w_box10", QuestPeriod.WEEKLY, "Open 10 lootboxes", Stat.LOOTBOXES_OPENED, 10, tokens = 40),
        QuestDef("w_special60", QuestPeriod.WEEKLY, "Create 60 special tiles", Stat.SPECIALS_CREATED, 60, coins = 700),
        QuestDef("w_3star8", QuestPeriod.WEEKLY, "Get three stars 8 times", Stat.THREE_STAR_WINS, 8, box = LootboxTier.EPIC),
        QuestDef("w_recycle15", QuestPeriod.WEEKLY, "Recycle 15 duplicates", Stat.DUPLICATES_RECYCLED, 15, coins = 500),
        QuestDef("w_new10", QuestPeriod.WEEKLY, "Discover 10 new cards", Stat.NEW_CARDS, 10, tokens = 60),
    )

    fun daily(day: Long): List<QuestDef> = pick(dailyPool, 3, day * 31 + 7)
    fun weekly(week: Long): List<QuestDef> = pick(weeklyPool, 3, week * 131 + 11)

    private fun pick(pool: List<QuestDef>, n: Int, seed: Long): List<QuestDef> {
        val list = pool.toMutableList()
        Rng(seed).shuffle(list)
        return list.take(n)
    }
}

/** Local, calendar-based events. No server needed: activity is derived from the local date. */
data class SeasonalEvent(
    val id: String,
    val title: String,
    val description: String,
    val coinBonusPercent: Int,
    val quest: QuestDef?,
    val isActive: (LocalDate) -> Boolean,
)

object EventCalendar {
    private fun range(m1: Int, d1: Int, m2: Int, d2: Int): (LocalDate) -> Boolean = { date ->
        val v = date.monthValue * 100 + date.dayOfMonth
        val a = m1 * 100 + d1
        val b = m2 * 100 + d2
        if (a <= b) v in a..b else v >= a || v <= b
    }

    val events = listOf(
        SeasonalEvent("weekend_lanterns", "Weekend Lanterns", "+25% coins from levels on Saturdays and Sundays.", 25, null) {
            it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY
        },
        SeasonalEvent("sakura_season", "Sakura Season", "Blossom festival: special quest with a Legendary box.", 10,
            QuestDef("e_sakura", QuestPeriod.EVENT, "Win 40 levels during Sakura Season", Stat.LEVELS_WON, 40, box = LootboxTier.LEGENDARY),
            range(3, 20, 4, 20)),
        SeasonalEvent("summer_fireworks", "Summer Fireworks", "Create specials to light up the sky.", 10,
            QuestDef("e_fireworks", QuestPeriod.EVENT, "Create 150 special tiles", Stat.SPECIALS_CREATED, 150, box = LootboxTier.EPIC),
            range(7, 15, 8, 15)),
        SeasonalEvent("moon_festival", "Moon Festival", "Harvest-moon celebration.", 10,
            QuestDef("e_moon", QuestPeriod.EVENT, "Open 15 lootboxes", Stat.LOOTBOXES_OPENED, 15, tokens = 150),
            range(9, 10, 9, 30)),
        SeasonalEvent("autumn_leaves", "Autumn Leaves", "Maple season in the temple gardens.", 10,
            QuestDef("e_autumn", QuestPeriod.EVENT, "Clear 400 obstacles", Stat.OBSTACLES_CLEARED, 400, box = LootboxTier.EPIC),
            range(10, 15, 11, 30)),
        SeasonalEvent("winter_lights", "Winter Lights", "Glowing streets for the holidays.", 15,
            QuestDef("e_winter", QuestPeriod.EVENT, "Earn 60 stars", Stat.STARS_EARNED, 60, box = LootboxTier.LEGENDARY),
            range(12, 15, 1, 10)),
    )

    fun active(date: LocalDate) = events.filter { it.isActive(date) }

    /** Stable key of the current occurrence of an event (year it started). */
    fun occurrenceKey(event: SeasonalEvent, date: LocalDate): String {
        val year = if (event.id == "winter_lights" && date.monthValue == 1) date.year - 1 else date.year
        return "${event.id}.$year"
    }
}

data class AchievementDef(val id: String, val title: String, val description: String, val stat: Stat, val threshold: Long, val coins: Long, val tokens: Long = 0)

object Achievements {
    val all: List<AchievementDef> = buildList {
        listOf(1L to 50L, 10L to 150L, 50L to 400L, 100L to 700L, 250L to 1200L, 500L to 2000L, 1000L to 5000L).forEach { (n, c) ->
            add(AchievementDef("win_$n", "Journey $n", "Win $n levels.", Stat.LEVELS_WON, n, c))
        }
        listOf(25L to 300L, 100L to 800L, 300L to 2000L).forEach { (n, c) ->
            add(AchievementDef("threestar_$n", "Perfectionist $n", "Get three stars $n times.", Stat.THREE_STAR_WINS, n, c))
        }
        listOf(50L to 200L, 500L to 800L, 2500L to 2500L).forEach { (n, c) ->
            add(AchievementDef("specials_$n", "Sparkler $n", "Create $n special tiles.", Stat.SPECIALS_CREATED, n, c))
        }
        listOf(10L to 30L, 25L to 60L, 50L to 120L, 88L to 300L).forEach { (n, t) ->
            add(AchievementDef("discover_$n", "Collector $n", "Discover $n different cards.", Stat.NEW_CARDS, n, 0, t))
        }
        listOf(10L to 300L, 100L to 1500L).forEach { (n, c) ->
            add(AchievementDef("boxes_$n", "Unboxer $n", "Open $n lootboxes.", Stat.LOOTBOXES_OPENED, n, c))
        }
        listOf(1L to 300L, 5L to 1000L, 20L to 5000L).forEach { (n, c) ->
            add(AchievementDef("chapters_$n", "Traveller $n", "Complete $n chapter(s).", Stat.CHAPTERS_COMPLETED, n, c))
        }
        add(AchievementDef("daily_7", "Regular Guest", "Claim 7 daily rewards.", Stat.DAILY_CLAIMS, 7, 500))
        add(AchievementDef("recycle_50", "Tidy Shelves", "Recycle 50 duplicates.", Stat.DUPLICATES_RECYCLED, 50, 0, 80))
        add(AchievementDef("combo_25", "Combo Artist", "Fire 25 special combos.", Stat.COMBOS, 25, 600))
    }
}

sealed interface DailyReward {
    data class Coins(val amount: Long) : DailyReward
    data class Tokens(val amount: Long) : DailyReward
    data class Booster(val type: com.cozycards.game.economy.BoosterType, val amount: Int) : DailyReward
    data class Box(val tier: LootboxTier) : DailyReward
}

object DailyRewards {
    val cycle: List<DailyReward> = listOf(
        DailyReward.Coins(100),
        DailyReward.Coins(150),
        DailyReward.Booster(com.cozycards.game.economy.BoosterType.HAMMER, 1),
        DailyReward.Coins(200),
        DailyReward.Tokens(20),
        DailyReward.Coins(300),
        DailyReward.Box(LootboxTier.RARE),
    )
}
