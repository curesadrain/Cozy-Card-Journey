package com.cozycards.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.cozycards.game.cards.CardCatalog
import com.cozycards.game.cards.CardPack
import com.cozycards.game.cards.CardPackValidator
import com.cozycards.levels.LevelCatalog
import com.cozycards.levels.LevelDefinition
import java.io.File

/**
 * Loads read-only content: bundled levels and card packs from assets, plus card packs and card
 * images the player imported locally. Adding a collection = adding a pack file; no code changes.
 */
class ContentRepository(private val context: Context) {
    private val userPackDir = File(context.filesDir, "card_packs").apply { mkdirs() }
    private val userImageDir = File(context.filesDir, "card_images").apply { mkdirs() }

    val levels: List<LevelDefinition> by lazy {
        LevelCatalog.loadAll { name -> context.assets.open("levels/$name").bufferedReader().use { it.readText() } }
    }

    /** Problems found while loading user packs (shown in the profile screen). */
    val packWarnings = mutableListOf<String>()

    val catalog: CardCatalog by lazy { CardCatalog(loadPacks()) }

    private fun loadPacks(): List<CardPack> {
        val bundled = context.assets.list("cards").orEmpty().filter { it.endsWith(".json") }.sorted().map { name ->
            CardCatalog.parsePack(context.assets.open("cards/$name").bufferedReader().use { it.readText() })
        }
        val ids = bundled.flatMap { p -> p.cards.map { it.id } }.toMutableSet()
        val user = userPackDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val pack = runCatching { CardCatalog.parsePack(f.readText()) }.getOrElse {
                packWarnings += "${f.name}: unreadable (${it.message?.take(60)})"; return@mapNotNull null
            }
            val issues = CardPackValidator.validate(pack, ids)
            if (issues.isNotEmpty()) {
                packWarnings += "${f.name}: ${issues.first()}"
                null
            } else {
                ids += pack.cards.map { it.id }
                pack
            }
        }
        return bundled + user
    }

    /** Imports a card pack JSON chosen by the player. Returns validation problems (empty = ok). */
    fun importPack(uri: Uri): List<String> {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return listOf("Can't read file")
        val pack = runCatching { CardCatalog.parsePack(text) }.getOrElse { return listOf("Not a card pack: ${it.message?.take(80)}") }
        val issues = CardPackValidator.validate(pack, catalog.cards.map { it.id }.toSet())
        if (issues.isNotEmpty()) return issues
        File(userPackDir, "${pack.collection.id}.json").writeText(text)
        return emptyList()
    }

    fun userImageFile(cardId: String) = File(userImageDir, "$cardId.jpg")
    fun hasUserImage(cardId: String) = userImageFile(cardId).exists()

    /**
     * Copies an image the player picked into private storage, downscaled to at most 1024 px.
     * The image stays on the device and is only shown for this player.
     */
    fun importCardImage(cardId: String, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val tmp = File(userImageDir, "$cardId.tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(userImageFile(cardId))
    }.onFailure { Log.w("Content", "image import failed", it) }.getOrDefault(false)

    fun removeCardImage(cardId: String) = userImageFile(cardId).delete()

    fun loadUserImage(cardId: String, maxSize: Int): Bitmap? {
        val f = userImageFile(cardId)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
