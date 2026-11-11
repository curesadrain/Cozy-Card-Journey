package com.cozycards.app.ui.gfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Game art loaded from assets/sprites (produced by tools/process_art.py), plus a few
 * procedurally generated soft sprites (glow, sparkle) that look better when added together.
 * Everything is decoded once in the background at startup (~6 MB).
 */
object Sprites {
    private val map = ConcurrentHashMap<String, ImageBitmap>()
    @Volatile var loaded = false
        private set

    val NAMES = listOf(
        "tile_0", "tile_1", "tile_2", "tile_3", "tile_4", "tile_5",
        "rocket_h", "rocket_v", "bomb", "rainbow", "plane",
        "crate_1", "crate_2", "crate_3", "ice_1", "ice_2", "stone", "chain_1", "chain_2", "web", "lock", "charm", "key",
        "cell_light", "cell_pink", "portal", "conveyor",
        "p_petal", "p_leaf", "p_splinter", "p_shard", "p_smoke", "p_coin",
        "btn_pink", "btn_lavender", "ui_coin", "ui_token", "ui_panel", "ui_star",
        "icon_hammer", "icon_super_hammer", "icon_glove", "icon_shuffle", "icon_area", "icon_moves",
        "nav_map", "nav_collection", "nav_loot", "nav_shop", "nav_profile", "nav_settings",
        "chest_common", "chest_rare", "chest_epic", "chest_legendary", "logo",
    ) + com.cozycards.app.ui.map.MapWorld.spriteNames

    fun load(context: Context) {
        if (loaded) return
        for (n in NAMES) {
            context.assets.open("sprites/$n.webp").use { s ->
                BitmapFactory.decodeStream(s)?.let { map[n] = it.asImageBitmap() }
            }
        }
        map["glow"] = softGlow(128).asImageBitmap()
        map["sparkle"] = sparkle(128).asImageBitmap()
        loaded = true
    }

    operator fun get(name: String): ImageBitmap? = map[name]

    fun tile(color: Int) = map["tile_${color.coerceIn(0, 5)}"]

    private fun softGlow(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size / 2f, size / 2f, size / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x22FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p)
        return b
    }

    private fun sparkle(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val h = size / 2f
        p.shader = RadialGradient(h, h, h * 0.45f, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(h, h, h * 0.45f, p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt()
        val path = android.graphics.Path().apply {
            moveTo(h, 0f); quadTo(h, h, size.toFloat(), h); quadTo(h, h, h, size.toFloat()); quadTo(h, h, 0f, h); quadTo(h, h, h, 0f); close()
        }
        c.drawPath(path, p)
        return b
    }
}

/** Chapter backgrounds, decoded on demand and kept in a small LRU cache. */
object Backgrounds {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(chapter: Int, thumb: Boolean): ImageBitmap? = cache.get(key(chapter, thumb))

    private fun key(chapter: Int, thumb: Boolean) = "%02d%s".format(chapter.coerceIn(1, 20), if (thumb) "_thumb" else "")

    /** Blocking; call off the main thread. */
    fun load(context: Context, chapter: Int, thumb: Boolean): ImageBitmap? {
        val k = key(chapter, thumb)
        cache.get(k)?.let { return it }
        return runCatching {
            context.assets.open("bg/chapter_$k.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()?.also { cache.put(k, it) }
    }
}
