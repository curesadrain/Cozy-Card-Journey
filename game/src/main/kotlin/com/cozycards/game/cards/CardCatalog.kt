package com.cozycards.game.cards

import kotlinx.serialization.json.Json

/**
 * Immutable index over all loaded card packs. Designed for thousands of cards: lookups are
 * map-based and per-rarity pools are precomputed.
 */
class CardCatalog(packs: List<CardPack>) {
    val collections: List<CollectionDef> = packs.map { it.collection }
    val cards: List<CardDef> = packs.flatMap { it.cards }
    private val byId: Map<String, CardDef> = cards.associateBy { it.id }
    private val byCollection: Map<String, List<CardDef>> = cards.groupBy { it.collectionId }
    private val byRarity: Map<Rarity, List<CardDef>> = cards.groupBy { it.gameRarity }

    operator fun get(id: String): CardDef? = byId[id]
    fun require(id: String): CardDef = byId[id] ?: error("Unknown card $id")
    fun contains(id: String) = id in byId
    fun inCollection(collectionId: String): List<CardDef> = byCollection[collectionId].orEmpty()
    fun collection(id: String): CollectionDef? = collections.firstOrNull { it.id == id }

    /** Cards of a rarity, optionally restricted to one collection. */
    fun pool(rarity: Rarity, collectionId: String? = null): List<CardDef> {
        val all = byRarity[rarity].orEmpty()
        return if (collectionId == null) all else all.filter { it.collectionId == collectionId }
    }

    fun albumOf(cardId: String): AlbumDef? =
        collections.asSequence().flatMap { it.albums.asSequence() }.firstOrNull { cardId in it.cardIds }

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun parsePack(text: String): CardPack = json.decodeFromString(CardPack.serializer(), text)
        fun encodePack(pack: CardPack): String = json.encodeToString(CardPack.serializer(), pack)
    }
}

/** Data-quality rules for card packs. Prevents unverified data from posing as real cards. */
object CardPackValidator {

    fun validate(pack: CardPack, existingIds: Set<String> = emptySet()): List<String> {
        val issues = mutableListOf<String>()
        val c = pack.collection
        if (c.id.isBlank() || c.name.isBlank()) issues += "collection needs id and name"
        if (!c.accentColor.matches(Regex("#[0-9A-Fa-f]{6}"))) issues += "collection accent colour must be #RRGGBB"
        val ids = pack.cards.map { it.id }
        if (ids.toSet().size != ids.size) issues += "duplicate card ids in pack"
        ids.filter { it in existingIds }.forEach { issues += "card id $it already exists in another pack" }
        val albumIds = c.albums.flatMap { it.cardIds }
        albumIds.filter { it !in ids }.forEach { issues += "album references unknown card $it" }
        ids.filter { it !in albumIds }.forEach { issues += "card $it is not in any album" }
        if (albumIds.toSet().size != albumIds.size) issues += "a card appears in more than one album"

        for (card in pack.cards) {
            val p = "card ${card.id}:"
            if (card.collectionId != c.id) issues += "$p wrong collection id"
            if (card.name.isBlank()) issues += "$p missing name"
            if (card.description.isBlank()) issues += "$p missing description"
            if (card.art.palette.size < 2 || card.art.palette.any { !it.matches(Regex("#[0-9A-Fa-f]{6}")) }) issues += "$p art palette needs >= 2 #RRGGBB colours"
            val realFields = listOfNotNull(card.manufacturer, card.year, card.cardNumber, card.originalRarity)
            when (card.verification) {
                VerificationStatus.VERIFIED -> {
                    if (card.manufacturer == null || card.year == null || card.cardNumber == null) issues += "$p verified card must have manufacturer, year and number"
                    if (card.source.isNullOrBlank()) issues += "$p verified card must cite a source"
                }
                VerificationStatus.INCOMPLETE ->
                    if (card.source.isNullOrBlank()) issues += "$p real card data must cite a source"
                VerificationStatus.DEMO, VerificationStatus.ORIGINAL_ART ->
                    if (realFields.isNotEmpty()) issues += "$p demo/original cards must not carry physical card data"
            }
            val mayShowImage = card.imageRights in setOf(ImageRights.ORIGINAL_ARTWORK, ImageRights.LICENSED)
            if ((card.frontImage != null || card.backImage != null) && !mayShowImage) {
                issues += "$p bundles an image without cleared rights (${card.imageRights})"
            }
            if (card.year != null && card.year !in 1900..2100) issues += "$p implausible year"
        }
        return issues
    }
}
