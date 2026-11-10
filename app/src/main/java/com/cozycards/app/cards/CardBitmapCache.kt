package com.cozycards.app.cards

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.cozycards.app.data.ContentRepository
import com.cozycards.game.cards.CardDef
import com.cozycards.game.economy.Cosmetic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Memory-bounded cache for generated card bitmaps. Rendering happens off the main thread;
 * the cache size is a fraction of the app's heap so large collections don't cause OOM.
 */
class CardBitmapCache(context: Context, private val content: ContentRepository) {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val locks = HashMap<String, Mutex>()

    private fun lockFor(key: String) = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    /** Bumped when a user image changes so dependent keys are invalidated. */
    @Volatile private var imageVersion = 0
    fun invalidate(cardId: String) {
        imageVersion++
        cache.snapshot().keys.filter { it.contains(cardId) }.forEach { cache.remove(it) }
    }

    private suspend fun cached(key: String, make: () -> Bitmap): Bitmap {
        cache.get(key)?.let { return it }
        return lockFor(key).withLock {
            cache.get(key) ?: withContext(Dispatchers.Default) { make() }.also { cache.put(key, it) }
        }
    }

    fun peekThumbnail(card: CardDef, width: Int, owned: Boolean, frame: Cosmetic): Bitmap? =
        cache.get(thumbKey(card, width, owned, frame))

    private fun thumbKey(card: CardDef, width: Int, owned: Boolean, frame: Cosmetic) = "thumb:${card.id}:$width:$owned:${frame.name}:$imageVersion"

    suspend fun thumbnail(card: CardDef, width: Int, owned: Boolean, frame: Cosmetic): Bitmap {
        val h = (width / CARD_ASPECT).toInt()
        return cached(thumbKey(card, width, owned, frame)) {
            if (!owned) CardArt.renderSilhouette(card, width, h)
            else CardArt.renderFront(card, content.catalog.collection(card.collectionId), width, h, frame == Cosmetic.FRAME_LACQUER, content.loadUserImage(card.id, width))
        }
    }

    /** Layers for the 2.5D renderer. Not cached: GL uploads them once and they are recycled. */
    suspend fun glLayers(card: CardDef, width: Int, frame: Cosmetic, back: Cosmetic): GlCardLayers = withContext(Dispatchers.Default) {
        val h = (width / CARD_ASPECT).toInt()
        val user = content.loadUserImage(card.id, width)
        GlCardLayers(
            art = CardArt.renderArt(card, width, h, user),
            frame = CardArt.renderFrame(card, content.catalog.collection(card.collectionId), width, h, frame == Cosmetic.FRAME_LACQUER, user != null),
            back = CardArt.renderBack(back, width, h),
        )
    }
}

class GlCardLayers(val art: Bitmap, val frame: Bitmap, val back: Bitmap)
