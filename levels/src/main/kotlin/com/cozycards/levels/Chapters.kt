package com.cozycards.levels

/** Gameplay mechanics a chapter can use. */
enum class Mechanic(val title: String) {
    BASIC("Matching basics"),
    ICE("Ice"),
    DOUBLE_ICE("Double ice"),
    CRATES("Crates"),
    STRONG_CRATES("Strong crates"),
    LAYERED_CRATES("Layered crates"),
    CHAINS("Chains"),
    DOUBLE_CHAINS("Double chains"),
    WEBS("Webs"),
    STONES("Stones"),
    DROPS("Delivery charms"),
    KEYS("Keys & locks"),
    PORTALS("Portals"),
    CONVEYORS("Conveyor belts"),
    SIDE_GRAVITY("Changed gravity"),
    GRAVITY_SHIFT("Shifting gravity"),
    MULTI_STAGE("Multi-stage boards"),
    SHAPES("Unusual board shapes"),
}

data class ChapterInfo(
    val number: Int,
    val title: String,
    val subtitle: String,
    /** Mechanics first introduced in this chapter. */
    val introduces: List<Mechanic>,
) {
    val firstLevel get() = (number - 1) * Chapters.LEVELS_PER_CHAPTER + 1
    val lastLevel get() = number * Chapters.LEVELS_PER_CHAPTER
}

object Chapters {
    const val COUNT = 20
    const val LEVELS_PER_CHAPTER = 50
    const val TOTAL_LEVELS = COUNT * LEVELS_PER_CHAPTER

    val all: List<ChapterInfo> = listOf(
        ChapterInfo(1, "Sakura Street", "Petals drift over a quiet shopping lane", listOf(Mechanic.BASIC)),
        ChapterInfo(2, "Kyoto Garden", "Frosted moss and stone lanterns", listOf(Mechanic.ICE)),
        ChapterInfo(3, "Seoul Cafe", "Crates of beans behind the counter", listOf(Mechanic.CRATES)),
        ChapterInfo(4, "Lantern Festival", "Paper lanterns tied with silk cords", listOf(Mechanic.CHAINS, Mechanic.DOUBLE_ICE)),
        ChapterInfo(5, "Bamboo Forest", "Spider silk between green stalks", listOf(Mechanic.WEBS, Mechanic.DROPS)),
        ChapterInfo(6, "Moonlight Market", "Stone stalls under a silver moon", listOf(Mechanic.STONES, Mechanic.STRONG_CRATES)),
        ChapterInfo(7, "Cherry Blossom Hill", "Locked gates on the hilltop path", listOf(Mechanic.KEYS)),
        ChapterInfo(8, "Coastal Village", "Tide pools that lead somewhere else", listOf(Mechanic.PORTALS)),
        ChapterInfo(9, "Autumn Temple", "Layered offerings in red and gold", listOf(Mechanic.LAYERED_CRATES, Mechanic.SHAPES)),
        ChapterInfo(10, "Winter Lights", "Ribbons of lights roll down the street", listOf(Mechanic.CONVEYORS)),
        ChapterInfo(11, "Hanok Village", "Wind sweeps through tiled courtyards", listOf(Mechanic.SIDE_GRAVITY)),
        ChapterInfo(12, "Tea House Alley", "One room leads into the next", listOf(Mechanic.MULTI_STAGE)),
        ChapterInfo(13, "Night Train Station", "Platforms glowing after midnight", listOf(Mechanic.DOUBLE_CHAINS)),
        ChapterInfo(14, "Hot Spring Inn", "Steam curls over icy pools", emptyList()),
        ChapterInfo(15, "Rainy Day Bookshop", "Shelves in every odd shape", emptyList()),
        ChapterInfo(16, "Starlight Rooftop", "The sky turns with the hours", listOf(Mechanic.GRAVITY_SHIFT)),
        ChapterInfo(17, "Fireworks Pier", "Bright bursts over the harbour", emptyList()),
        ChapterInfo(18, "Koi Pond Garden", "Charms ripple between the ponds", emptyList()),
        ChapterInfo(19, "Snowy Mountain Shrine", "Every trial on one mountain", emptyList()),
        ChapterInfo(20, "Spring Homecoming", "The journey's warm ending", emptyList()),
    )

    fun of(levelNumber: Int): ChapterInfo = all[(levelNumber - 1) / LEVELS_PER_CHAPTER]

    /** All mechanics available up to and including [chapter]. */
    fun unlockedMechanics(chapter: Int): List<Mechanic> = all.take(chapter).flatMap { it.introduces }

    fun levelId(number: Int): String {
        val ch = (number - 1) / LEVELS_PER_CHAPTER + 1
        val idx = (number - 1) % LEVELS_PER_CHAPTER + 1
        return "c%02d_l%02d".format(ch, idx)
    }
}
