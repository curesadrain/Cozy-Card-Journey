package com.cozycards.game.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** Complete player state. Used for export/import, backups and the in-memory store. */
@Serializable
data class SaveData(
    val version: Int = CURRENT_VERSION,
    val wallet: Wallet = Wallet(),
    val ledger: Map<String, Long> = emptyMap(),
    val levels: List<LevelProgress> = emptyList(),
    val cards: List<OwnedCard> = emptyList(),
    val history: List<CardHistoryEntry> = emptyList(),
    val lootboxes: List<PendingLootbox> = emptyList(),
    val opened: List<OpenedLootbox> = emptyList(),
    val boosters: Map<String, Int> = emptyMap(),
    val meta: Map<String, String> = emptyMap(),
    val rewardLog: List<RewardLogEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/** Signed container written to export files. */
@Serializable
data class SaveEnvelope(val format: String, val version: Int, val createdAt: Long, val checksum: String, val payload: String)

class SaveFormatException(message: String) : IllegalArgumentException(message)

/**
 * Export/import of saves with integrity checking and forward migration of older versions.
 * Validation is strict: a corrupted or tampered file is rejected instead of half-applied.
 */
object SaveCodec {
    const val FORMAT = "cozy-card-journey-save"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun export(data: SaveData, now: Long): String {
        val payload = json.encodeToString(SaveData.serializer(), data)
        return json.encodeToString(SaveEnvelope.serializer(), SaveEnvelope(FORMAT, data.version, now, sha256(payload), payload))
    }

    fun import(text: String, knownCardIds: Set<String>? = null): SaveData {
        val env = try {
            json.decodeFromString(SaveEnvelope.serializer(), text)
        } catch (e: Exception) {
            throw SaveFormatException("Not a save file: ${e.message?.take(80)}")
        }
        if (env.format != FORMAT) throw SaveFormatException("Unknown save format '${env.format}'")
        if (sha256(env.payload) != env.checksum) throw SaveFormatException("Checksum mismatch – the file is damaged or was edited")
        if (env.version > SaveData.CURRENT_VERSION) throw SaveFormatException("Save is from a newer app version (${env.version})")
        val migrated = migrate(env.payload, env.version)
        val data = try {
            json.decodeFromString(SaveData.serializer(), migrated)
        } catch (e: Exception) {
            throw SaveFormatException("Save content is invalid: ${e.message?.take(120)}")
        }
        return sanitize(data, knownCardIds)
    }

    /** Applies migrations step by step from [from] to the current version. */
    fun migrate(payload: String, from: Int): String {
        var obj = json.parseToJsonElement(payload).jsonObject
        var v = from
        while (v < SaveData.CURRENT_VERSION) {
            obj = when (v) {
                1 -> migrateV1toV2(obj)
                else -> throw SaveFormatException("No migration from version $v")
            }
            v++
        }
        return obj.toString()
    }

    /** v1 stored coins/tokens as top-level fields; v2 groups them in a wallet object. */
    private fun migrateV1toV2(o: JsonObject): JsonObject {
        val coins = o["coins"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val tokens = o["tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val rest = o.filterKeys { it != "coins" && it != "tokens" }.toMutableMap()
        rest["wallet"] = JsonObject(mapOf("coins" to JsonPrimitive(coins), "tokens" to JsonPrimitive(tokens)))
        rest["version"] = JsonPrimitive(2)
        return JsonObject(rest)
    }

    /** Removes entries that reference unknown content and clamps impossible values. */
    fun sanitize(data: SaveData, knownCardIds: Set<String>?): SaveData {
        val cards = data.cards
            .filter { knownCardIds == null || it.cardId in knownCardIds }
            .filter { it.count > 0 }
            .groupBy { it.cardId }.map { (_, list) -> list.maxBy { it.count } }
        val boosters = data.boosters.mapValues { it.value.coerceIn(0, 9_999) }
        val ids = HashSet<Long>()
        val boxes = data.lootboxes.filter { ids.add(it.id) }
        return data.copy(version = SaveData.CURRENT_VERSION, cards = cards, boosters = boosters, lootboxes = boxes)
    }
}
