package com.cozycards.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.cozycards.app.ui.gfx.Sprites
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Procedural map scenery: a vertically tiling ground texture plus decoration sprites that are
 * scattered beside the path. Placement is a pure function of the level number, so the same
 * spot of the map always looks the same and nothing has to be stored.
 */
internal object MapWorld {

    enum class Kind { TREE, BUSH, ROCK, LANTERN, POND, SIGN, FLOWERS, SHRINE, FENCE }

    /** One decoration sprite with its on-screen height (in dp) range. */
    class Decor(val sprite: String, val kind: Kind, val minH: Float, val maxH: Float)

    private fun set(p: String) = listOf(
        Decor("${p}_00", Kind.TREE, 92f, 116f), Decor("${p}_01", Kind.TREE, 92f, 116f), Decor("${p}_02", Kind.TREE, 84f, 108f),
        Decor("${p}_03", Kind.BUSH, 46f, 60f), Decor("${p}_04", Kind.BUSH, 44f, 58f), Decor("${p}_05", Kind.BUSH, 44f, 58f),
        Decor("${p}_06", Kind.ROCK, 36f, 50f), Decor("${p}_07", Kind.ROCK, 30f, 44f),
        Decor("${p}_08", Kind.LANTERN, 50f, 60f), Decor("${p}_09", Kind.LANTERN, 64f, 76f),
        // _10 is a bridge: it needs water under it, so it is not scattered on grass.
        Decor("${p}_11", Kind.POND, 44f, 56f), Decor("${p}_12", Kind.SIGN, 46f, 56f),
        Decor("${p}_13", Kind.FLOWERS, 28f, 38f), Decor("${p}_14", Kind.SHRINE, 66f, 80f), Decor("${p}_15", Kind.FENCE, 34f, 42f),
    )

    val all: List<Decor> = set("decor_a") + set("decor_b")
    val spriteNames: List<String> = all.map { it.sprite } + "ground_garden"

    /** Relative frequency of each kind; trees and bushes carry the scene, props are rarer. */
    private val kindWeight = mapOf(
        Kind.TREE to 5f, Kind.BUSH to 5f, Kind.ROCK to 2.5f, Kind.FLOWERS to 3f, Kind.LANTERN to 1.2f,
        Kind.POND to 0.8f, Kind.SIGN to 0.6f, Kind.SHRINE to 0.5f, Kind.FENCE to 1f,
    )

    /** Chapter flavour: which trees dominate. */
    private fun treeBias(chapter: Int, d: Decor): Float {
        val cherry = d.sprite.endsWith("_00")
        val autumn = d.sprite.endsWith("_01")
        return when (chapter) {
            1, 7, 18, 20 -> if (cherry) 3f else 1f
            9, 17 -> if (autumn) 3f else 1f
            5 -> if (d.sprite == "decor_b_05" || d.sprite == "decor_a_05") 3f else 1f // bamboo / fern
            else -> 1f
        }
    }

    /** A placed decoration, in row-local coordinates (dp; y = 0 at the row top). */
    class Placed(val decor: Decor, val cx: Float, val baseY: Float, val h: Float, val flip: Boolean)

    private class Rng(seed: Long) {
        private var s = seed
        fun next(): Long { s += -0x61c8864680b583ebL; var z = s; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; return z xor (z ushr 31) }
        fun f(): Float = (next() ushr 40).toFloat() / (1 shl 24)
        fun f(a: Float, b: Float) = a + (b - a) * f()
    }

    private fun pick(r: Rng, chapter: Int, maxH: Float): Decor? {
        val options = all.filter { it.minH <= maxH }
        if (options.isEmpty()) return null
        val w = options.map { (kindWeight[it.kind] ?: 1f) * treeBias(chapter, it) }
        var x = r.f() * w.sum()
        options.forEachIndexed { i, d -> x -= w[i]; if (x <= 0f) return d }
        return options.last()
    }

    private fun aspect(d: Decor): Float = Sprites[d.sprite]?.let { it.width.toFloat() / it.height } ?: 1f

    /**
     * Decorations for the row of level [n]. [pathMin]/[pathMax] are the path's horizontal extent
     * (dp) inside this row and its neighbours, already widened by the clearance the medallion,
     * stars and milestone chest need.
     */
    fun place(n: Int, chapter: Int, widthDp: Float, rowH: Float, pathMin: Float, pathMax: Float): List<Placed> {
        val r = Rng(n * 0x9E3779B97F4A7C15uL.toLong() + 77)
        val out = ArrayList<Placed>(4)
        fun side(zoneStart: Float, zoneEnd: Float, leftSide: Boolean) {
            val free = zoneEnd - zoneStart
            if (free < 34f) return
            val count = if (free > 150f) 2 else 1
            var cursor = if (leftSide) zoneEnd else zoneStart
            repeat(count) { k ->
                if (r.f() < 0.12f) return@repeat // occasional breathing room
                val d = pick(r, chapter, rowH * 1.08f) ?: return@repeat
                val h = r.f(d.minH, min(d.maxH, rowH * 1.08f))
                val w = h * aspect(d)
                // Objects hug the path side of the zone; the outermost may run off the screen edge.
                val cx = if (leftSide) cursor - w / 2 - r.f(0f, 10f) else cursor + w / 2 + r.f(0f, 10f)
                if (leftSide && cx + w / 2 > zoneEnd + 1f) return@repeat
                if (!leftSide && cx - w / 2 < zoneStart - 1f) return@repeat
                if (cx < -w * 0.3f || cx > widthDp + w * 0.3f) return@repeat
                val base = if (k == 0) r.f(rowH * 0.62f, rowH * 0.98f) else r.f(rowH * 0.4f, rowH * 0.9f)
                out += Placed(d, cx, max(base, h * 0.85f).coerceAtMost(rowH), h, r.f() < 0.5f && d.kind != Kind.SIGN)
                cursor = if (leftSide) cx - w / 2 + w * 0.15f else cx + w / 2 - w * 0.15f
            }
        }
        side(0f, pathMin, true)
        side(pathMax, widthDp, false)
        return out.sortedBy { it.baseY }
    }

    fun DrawScope.drawPlaced(p: Placed, density: Float, filter: ColorFilter?) {
        val img = Sprites[p.decor.sprite] ?: return
        val h = p.h * density
        val w = h * img.width / img.height
        val left = p.cx * density - w / 2
        val top = p.baseY * density - h
        val draw = {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                IntOffset(left.roundToInt(), top.roundToInt()), IntSize(w.roundToInt(), h.roundToInt()), colorFilter = filter)
        }
        if (p.flip) {
            val c = Offset(p.cx * density, top + h / 2)
            drawContext.transform.scale(-1f, 1f, c)
            draw()
            drawContext.transform.scale(-1f, 1f, c)
        } else draw()
    }

    /** Per-chapter colour grade of the garden art (time of day, season). */
    class Grade(val sat: Float, val r: Float, val g: Float, val b: Float, val add: Float)

    private val grades = mapOf(
        2 to Grade(0.85f, 0.95f, 1f, 1.05f, 0.04f),
        3 to Grade(0.9f, 1.05f, 0.98f, 0.88f, 0f),
        4 to Grade(0.9f, 0.95f, 0.82f, 0.75f, 0f),
        5 to Grade(1.05f, 0.92f, 1.02f, 0.92f, 0f),
        6 to Grade(0.7f, 0.55f, 0.6f, 0.85f, 0f),
        7 to Grade(1f, 1.04f, 0.98f, 1f, 0f),
        8 to Grade(0.95f, 0.92f, 1f, 1.05f, 0f),
        9 to Grade(0.9f, 1.15f, 0.88f, 0.6f, 0f),
        10 to Grade(0.35f, 0.9f, 0.95f, 1.08f, 0.18f),
        11 to Grade(1f, 1.02f, 1f, 0.94f, 0f),
        12 to Grade(0.9f, 1f, 0.97f, 0.88f, 0f),
        13 to Grade(0.65f, 0.5f, 0.55f, 0.8f, 0f),
        14 to Grade(0.7f, 1f, 1f, 1f, 0.08f),
        15 to Grade(0.6f, 0.8f, 0.88f, 0.95f, 0f),
        16 to Grade(0.6f, 0.5f, 0.5f, 0.85f, 0f),
        17 to Grade(0.8f, 0.8f, 0.65f, 0.85f, 0f),
        18 to Grade(1f, 0.95f, 1.02f, 1f, 0f),
        19 to Grade(0.3f, 0.92f, 0.96f, 1.08f, 0.22f),
    )
    private val neutral = Grade(1f, 1f, 1f, 1f, 0f)
    fun grade(chapter: Int) = grades[chapter] ?: neutral

    /** Blends two chapter grades; [strength] < 1 softens it (used for decorations). */
    fun filter(a: Grade, b: Grade, mix: Float, strength: Float = 1f): ColorFilter? {
        fun l(x: Float, y: Float) = (x + (y - x) * mix).let { 1f + (it - 1f) * strength }
        val sat = l(a.sat, b.sat); val r = l(a.r, b.r); val g = l(a.g, b.g); val bl = l(a.b, b.b)
        val add = (a.add + (b.add - a.add) * mix) * strength
        if (sat == 1f && r == 1f && g == 1f && bl == 1f && add == 0f) return null
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val s = sat; val i = 1f - s
        val m = floatArrayOf(
            (lr * i + s) * r, lg * i * r, lb * i * r, 0f, add * 255f,
            lr * i * g, (lg * i + s) * g, lb * i * g, 0f, add * 255f,
            lr * i * bl, lg * i * bl, (lb * i + s) * bl, 0f, add * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        return ColorFilter.colorMatrix(ColorMatrix(m))
    }
}
