package com.cozycards.game.cards

import kotlinx.serialization.Serializable

/** In-game rarity (drives drop rates and visuals). Independent from any physical card rarity. */
@Serializable
enum class Rarity(val label: String) {
    COMMON("Common"), RARE("Rare"), EPIC("Epic"), LEGENDARY("Legendary");

    fun atLeast(other: Rarity) = ordinal >= other.ordinal
}

/** How much of a card's real-world data has been confirmed. */
@Serializable
enum class VerificationStatus(val label: String) {
    /** All physical-card fields confirmed against [CardDef.source]. */
    VERIFIED("Verified physical card"),

    /** Refers to a real card but some fields are unknown. */
    INCOMPLETE("Real card, incomplete data"),

    /** Game placeholder; does not correspond to a specific physical card. */
    DEMO("Demo card – not a real physical card"),

    /** Original artwork made for this game, inspired by the theme. */
    ORIGINAL_ART("Original alternate illustration"),
}

/** Whether the app may display a bundled image of the card. */
@Serializable
enum class ImageRights(val label: String) {
    /** Rights unknown – only a procedural placeholder may be shown. */
    UNKNOWN("Image rights unknown – placeholder shown"),

    /** Known not to be licensed for this app – placeholder only. */
    NOT_CLEARED("Image not licensed – placeholder shown"),

    /** Art created for this game (procedural); free to display. */
    ORIGINAL_ARTWORK("Original artwork"),

    /** Image imported locally by the user from their own device. */
    USER_PROVIDED("Your imported image"),

    /** Explicitly licensed image. */
    LICENSED("Licensed image"),
}

/** Parameters for the procedural illustration used when no cleared image exists. */
@Serializable
data class ArtSpec(
    /** Hex colours, background → highlight. */
    val palette: List<String>,
    val motif: Motif,
    val seed: Int = 0,
    /** Short glyph text drawn on the illustration (e.g. initials). */
    val glyph: String = "",
)

@Serializable
enum class Motif { MICROPHONE, STAGE, STARS, HEART, NOTE, WEB, MASK, CITY, SPIDER, LIGHTNING, SHELL, KATANA, SEWER, PIZZA, MOON, SAKURA, LANTERN, WAVE }

@Serializable
data class CardDef(
    val id: String,
    val name: String,
    val collectionId: String,
    /** In-game album / series grouping. */
    val series: String,
    /** Physical card data. Null = unknown; never invented. */
    val manufacturer: String? = null,
    val year: Int? = null,
    val cardNumber: String? = null,
    val originalRarity: String? = null,
    val gameRarity: Rarity,
    val description: String,
    /** Bundled image path for the front, only allowed when [imageRights] permits display. */
    val frontImage: String? = null,
    val backImage: String? = null,
    val art: ArtSpec,
    val source: String? = null,
    val verification: VerificationStatus,
    val imageRights: ImageRights,
)

@Serializable
data class AlbumDef(val id: String, val name: String, val description: String, val cardIds: List<String>)

@Serializable
data class CollectionDef(
    val id: String,
    val name: String,
    val description: String,
    /** Shown on every card of the collection. */
    val disclaimer: String,
    val accentColor: String,
    val albums: List<AlbumDef>,
)

/** A self-contained collection file. New collections are added by dropping a new pack in. */
@Serializable
data class CardPack(val schemaVersion: Int = 1, val collection: CollectionDef, val cards: List<CardDef>)
