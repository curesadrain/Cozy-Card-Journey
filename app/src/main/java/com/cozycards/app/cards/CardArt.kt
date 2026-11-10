package com.cozycards.app.cards

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cozycards.engine.Rng
import com.cozycards.game.cards.CardDef
import com.cozycards.game.cards.CollectionDef
import com.cozycards.game.cards.ImageRights
import com.cozycards.game.cards.Motif
import com.cozycards.game.cards.Rarity
import com.cozycards.game.cards.VerificationStatus
import com.cozycards.game.economy.Cosmetic
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Card proportions: 5:7, like real trading cards (63 × 88 mm). */
const val CARD_ASPECT = 0.714f

object RarityStyle {
    fun frameColors(r: Rarity): IntArray = when (r) {
        Rarity.COMMON -> intArrayOf(0xFFF3EDE2.toInt(), 0xFFD9CFC0.toInt(), 0xFFB9AE9E.toInt())
        Rarity.RARE -> intArrayOf(0xFFDDE9F7.toInt(), 0xFF8FB3DE.toInt(), 0xFF4F78B0.toInt())
        Rarity.EPIC -> intArrayOf(0xFFEADCFB.toInt(), 0xFFB18AE8.toInt(), 0xFF6B3FB0.toInt())
        Rarity.LEGENDARY -> intArrayOf(0xFFFFF4D2.toInt(), 0xFFF2C94C.toInt(), 0xFFB8860B.toInt())
    }

    fun accent(r: Rarity): Int = frameColors(r)[2]

    /** 0..1 foil strength on the frame and on the art, passed to the GL shader as uniforms. */
    fun frameFoil(r: Rarity) = when (r) {
        Rarity.COMMON -> 0.0f; Rarity.RARE -> 0.75f; Rarity.EPIC -> 0.9f; Rarity.LEGENDARY -> 1f
    }

    fun artFoil(r: Rarity) = when (r) {
        Rarity.COMMON -> 0.0f; Rarity.RARE -> 0.0f; Rarity.EPIC -> 0.45f; Rarity.LEGENDARY -> 0.85f
    }
}

/** Layout rectangles relative to the card size. */
class CardLayout(val w: Int, val h: Int) {
    private fun r(l: Float, t: Float, rr: Float, b: Float) = RectF(l * w, t * h, rr * w, b * h)
    val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
    val corner = w * 0.055f
    val nameBar = r(0.06f, 0.035f, 0.94f, 0.115f)
    val art = r(0.07f, 0.125f, 0.93f, 0.64f)
    val textBox = r(0.07f, 0.655f, 0.93f, 0.9f)
    val footer = r(0.06f, 0.905f, 0.94f, 0.965f)
}

/**
 * Procedural card illustrations drawn with android.graphics. Produces:
 *  - [renderArt]: the illustration layer (sampled with parallax by the GL renderer),
 *  - [renderFrame]: frame + texts with a transparent window over the art,
 *  - [renderFront]: flattened front for 2D thumbnails,
 *  - [renderBack] and [renderSilhouette].
 */
object CardArt {

    private fun color(hex: String) = Color.parseColor(hex)

    fun renderArt(card: CardDef, w: Int, h: Int, userImage: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val l = CardLayout(w, h)
        val rect = l.art
        if (userImage != null) {
            // Center-crop the player's own image into the art window.
            c.drawColor(Color.BLACK)
            val src = centerCrop(userImage.width, userImage.height, rect.width() / rect.height())
            c.drawBitmap(userImage, src, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            drawIllustration(c, card, rect)
        }
        return bmp
    }

    private fun centerCrop(sw: Int, sh: Int, aspect: Float): Rect {
        val srcAspect = sw.toFloat() / sh
        return if (srcAspect > aspect) {
            val nw = (sh * aspect).toInt(); Rect((sw - nw) / 2, 0, (sw + nw) / 2, sh)
        } else {
            val nh = (sw / aspect).toInt(); Rect(0, (sh - nh) / 2, sw, (sh + nh) / 2)
        }
    }

    fun drawIllustration(c: Canvas, card: CardDef, rect: RectF) {
        val pal = card.art.palette.map(::color)
        val rng = Rng(card.art.seed.toLong() * 7919 + card.id.hashCode())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.clipRect(rect)
        p.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, pal[0], pal[1 % pal.size], Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        p.shader = RadialGradient(rect.centerX(), rect.top + rect.height() * 0.38f, rect.width() * 0.7f,
            withAlpha(pal[2 % pal.size], 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        p.shader = null
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = min(rect.width(), rect.height())
        val hi = pal.last()
        val mid = pal[2 % pal.size]
        when (card.art.motif) {
            Motif.STAGE, Motif.MICROPHONE -> {
                // Spotlight rays
                p.style = Paint.Style.FILL
                for (i in 0 until 7) {
                    val a = Math.toRadians(-150.0 + i * 20 + rng.nextInt(8))
                    val path = Path()
                    path.moveTo(cx, rect.top - s * 0.05f)
                    path.lineTo(cx + cos(a).toFloat() * s * 1.6f - s * 0.06f, rect.top + sin(-a).toFloat() * s * 1.6f)
                    path.lineTo(cx + cos(a).toFloat() * s * 1.6f + s * 0.06f, rect.top + sin(-a).toFloat() * s * 1.6f)
                    path.close()
                    p.color = withAlpha(hi, 38)
                    c.drawPath(path, p)
                }
                if (card.art.motif == Motif.MICROPHONE) drawMic(c, cx, cy + s * 0.05f, s * 0.42f, hi, mid)
                else drawStageCrowd(c, rect, mid, rng)
            }
            Motif.STARS -> {
                for (i in 0 until 40) drawStar(c, rect.left + rng.nextDouble().toFloat() * rect.width(), rect.top + rng.nextDouble().toFloat() * rect.height(), s * (0.01f + rng.nextDouble().toFloat() * 0.035f), withAlpha(hi, 120 + rng.nextInt(130)))
                drawStar(c, cx, cy, s * 0.22f, hi)
            }
            Motif.HEART -> {
                for (i in 0 until 10) drawHeart(c, rect.left + rng.nextDouble().toFloat() * rect.width(), rect.top + rng.nextDouble().toFloat() * rect.height(), s * 0.06f, withAlpha(hi, 90))
                drawHeart(c, cx, cy, s * 0.3f, mid)
            }
            Motif.NOTE -> {
                p.color = withAlpha(hi, 70); p.strokeWidth = s * 0.008f
                for (k in 0 until 5) c.drawLine(rect.left, cy - s * 0.2f + k * s * 0.07f, rect.right, cy - s * 0.25f + k * s * 0.07f, p)
                drawNote(c, cx - s * 0.12f, cy + s * 0.05f, s * 0.25f, hi)
                drawNote(c, cx + s * 0.18f, cy - s * 0.05f, s * 0.18f, mid)
            }
            Motif.WEB, Motif.SPIDER -> {
                drawWeb(c, cx, cy, s * 0.75f, withAlpha(hi, 150))
                if (card.art.motif == Motif.SPIDER) drawSpiderEmblem(c, cx, cy, s * 0.22f, mid, pal[0])
            }
            Motif.MASK -> {
                drawCityline(c, rect, withAlpha(pal[0], 160), rng)
                drawMask(c, cx, cy - s * 0.05f, s * 0.3f, mid, hi)
            }
            Motif.CITY -> {
                drawMoon(c, rect.right - s * 0.22f, rect.top + s * 0.22f, s * 0.12f, hi)
                drawCityline(c, rect, withAlpha(pal[0], 230), rng)
                drawCityWindows(c, rect, hi, rng)
            }
            Motif.LIGHTNING -> {
                drawBolt(c, cx, cy, s * 0.6f, hi)
                drawBolt(c, cx - s * 0.25f, cy + s * 0.1f, s * 0.3f, withAlpha(mid, 200))
            }
            Motif.SHELL -> drawShell(c, cx, cy, s * 0.36f, mid, hi)
            Motif.KATANA -> {
                drawMoon(c, cx, cy, s * 0.3f, withAlpha(hi, 200))
                p.color = pal[0]; p.strokeWidth = s * 0.03f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(rect.left + s * 0.1f, rect.bottom - s * 0.1f, rect.right - s * 0.1f, rect.top + s * 0.15f, p)
                p.color = mid
                c.drawLine(rect.right - s * 0.15f, rect.bottom - s * 0.1f, rect.left + s * 0.15f, rect.top + s * 0.15f, p)
            }
            Motif.SEWER -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.02f
                for (k in 1..6) { p.color = withAlpha(hi, 30 + k * 25); c.drawCircle(cx, cy + s * 0.1f, s * 0.1f * k, p) }
                p.style = Paint.Style.FILL
                drawShell(c, cx, cy, s * 0.18f, mid, hi)
            }
            Motif.PIZZA -> drawPizza(c, cx, cy, s * 0.36f, rng)
            Motif.MOON -> {
                for (i in 0 until 25) drawStar(c, rect.left + rng.nextDouble().toFloat() * rect.width(), rect.top + rng.nextDouble().toFloat() * rect.height() * 0.6f, s * 0.012f, withAlpha(hi, 180))
                drawMoon(c, cx, cy - s * 0.08f, s * 0.24f, hi)
                drawCityline(c, rect, withAlpha(pal[0], 220), rng)
            }
            Motif.SAKURA -> {
                for (i in 0 until 26) drawPetal(c, rect.left + rng.nextDouble().toFloat() * rect.width(), rect.top + rng.nextDouble().toFloat() * rect.height(), s * 0.04f, rng.nextInt(360).toFloat(), withAlpha(0xFFFFC1D9.toInt(), 200))
                drawBlossom(c, cx, cy, s * 0.22f, 0xFFFFB3CF.toInt())
            }
            Motif.LANTERN -> {
                for (i in 0 until 5) drawLantern(c, rect.left + (i + 0.5f) * rect.width() / 5, rect.top + s * (0.18f + 0.1f * (i % 2)), s * 0.09f, if (i % 2 == 0) mid else hi)
                drawLantern(c, cx, cy + s * 0.12f, s * 0.2f, mid)
            }
            Motif.WAVE -> drawWaves(c, rect, mid, hi)
        }
        // Glyph signature
        if (card.art.glyph.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                textSize = s * 0.13f
                this.color = withAlpha(hi, 230)
                setShadowLayer(s * 0.02f, 0f, s * 0.01f, withAlpha(pal[0], 200))
            }
            c.drawText(card.art.glyph, rect.left + s * 0.06f, rect.bottom - s * 0.06f, tp)
        }
        // Vignette
        p.shader = RadialGradient(cx, cy, rect.width() * 0.8f, Color.TRANSPARENT, withAlpha(Color.BLACK, 90), Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        p.shader = null
        c.restore()
    }

    fun renderFrame(card: CardDef, collection: CollectionDef?, w: Int, h: Int, lacquer: Boolean, userImage: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val l = CardLayout(w, h)
        val rarity = card.gameRarity
        val cols = RarityStyle.frameColors(rarity)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Base frame
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(cols[0], cols[1], cols[0], cols[1]), null, Shader.TileMode.CLAMP)
        c.drawRect(l.outer, p)
        p.shader = null
        // Paper fibre texture
        val rng = Rng(card.id.hashCode().toLong())
        p.strokeWidth = 1f
        for (i in 0 until (w * h / 900)) {
            p.color = withAlpha(if (rng.nextInt(2) == 0) Color.WHITE else cols[2], 18)
            val x = rng.nextInt(w).toFloat(); val y = rng.nextInt(h).toFloat()
            c.drawLine(x, y, x + rng.nextInt(8) - 4, y + rng.nextInt(8) - 4, p)
        }
        // Inner border lines
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.012f
        p.color = cols[2]
        val inset = w * 0.025f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), l.corner * 0.7f, l.corner * 0.7f, p)
        if (lacquer) {
            p.strokeWidth = w * 0.02f
            p.color = 0xFF7A1F1F.toInt()
            c.drawRoundRect(RectF(inset * 0.4f, inset * 0.4f, w - inset * 0.4f, h - inset * 0.4f), l.corner, l.corner, p)
        }
        // Decorations per rarity
        p.style = Paint.Style.FILL
        when (rarity) {
            Rarity.COMMON -> Unit
            Rarity.RARE -> corners(c, l, cols[2], w * 0.03f) { x, y, s, col -> drawStar(c, x, y, s, col) }
            Rarity.EPIC -> corners(c, l, cols[2], w * 0.04f) { x, y, s, col -> drawBlossom(c, x, y, s, col) }
            Rarity.LEGENDARY -> {
                corners(c, l, cols[2], w * 0.05f) { x, y, s, col -> drawBlossom(c, x, y, s, col) }
                p.color = withAlpha(cols[2], 120)
                for (i in 0 until 12) drawStar(c, l.textBox.left + i * l.textBox.width() / 11, l.footer.centerY(), w * 0.008f, withAlpha(cols[2], 140))
            }
        }
        // Art window (transparent hole) with an inset shadow line
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawRoundRect(l.art, w * 0.02f, w * 0.02f, hole)
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.008f
        p.color = withAlpha(cols[2], 255)
        c.drawRoundRect(l.art, w * 0.02f, w * 0.02f, p)
        p.style = Paint.Style.FILL
        // Name bar
        p.color = withAlpha(Color.WHITE, 170)
        c.drawRoundRect(l.nameBar, h * 0.02f, h * 0.02f, p)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = h * 0.042f; color = 0xFF2B2235.toInt()
        }
        fitText(card.name, title, l.nameBar.width() * 0.92f)
        c.drawText(card.name, l.nameBar.left + l.nameBar.width() * 0.04f, l.nameBar.centerY() + title.textSize * 0.35f, title)
        // Rarity gems
        val gems = rarity.ordinal + 1
        for (i in 0 until gems) {
            val gx = l.nameBar.right - h * 0.025f - i * h * 0.03f
            p.color = cols[2]; c.drawCircle(gx, l.nameBar.top - h * 0.002f, h * 0.012f, p)
            p.color = Color.WHITE; c.drawCircle(gx - h * 0.004f, l.nameBar.top - h * 0.006f, h * 0.004f, p)
        }
        // Text box
        p.color = withAlpha(Color.WHITE, 185)
        c.drawRoundRect(l.textBox, h * 0.015f, h * 0.015f, p)
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.026f; color = 0xFF4A3F55.toInt(); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        c.drawText("${card.series} · ${rarity.label}", l.textBox.left + w * 0.03f, l.textBox.top + h * 0.035f, small)
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.023f; color = 0xFF3B3346.toInt() }
        val layout = StaticLayout.Builder.obtain(card.description, 0, card.description.length, body, (l.textBox.width() - w * 0.06f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(5).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(l.textBox.left + w * 0.03f, l.textBox.top + h * 0.05f); layout.draw(c); c.restore()
        // Footer: honesty badge + collection
        val badge = when {
            userImage -> "YOUR IMAGE"
            card.verification == VerificationStatus.VERIFIED -> "VERIFIED"
            card.verification == VerificationStatus.INCOMPLETE -> "INCOMPLETE DATA"
            card.verification == VerificationStatus.DEMO -> "DEMO CARD"
            else -> "ORIGINAL ART"
        }
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.022f; color = cols[2]; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        c.drawText(badge, l.footer.left + w * 0.01f, l.footer.centerY() + foot.textSize * 0.35f, foot)
        val coll = collection?.name ?: card.collectionId
        val number = card.cardNumber?.let { "#$it" } ?: card.id.substringAfterLast('_')
        val right = "$coll · $number"
        c.drawText(right, l.footer.right - foot.measureText(right) - w * 0.01f, l.footer.centerY() + foot.textSize * 0.35f, foot)
        // Rounded card corners: clear outside
        clearCorners(c, w, h, l.corner)
        return bmp
    }

    /** Flattened front for thumbnails: art under frame. */
    fun renderFront(card: CardDef, collection: CollectionDef?, w: Int, h: Int, lacquer: Boolean, userImage: Bitmap?): Bitmap {
        val art = renderArt(card, w, h, userImage)
        val frame = renderFrame(card, collection, w, h, lacquer, userImage != null)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val opaque = Paint()
        c.drawBitmap(art, 0f, 0f, opaque)
        c.drawBitmap(frame, 0f, 0f, opaque)
        clearCorners(c, w, h, CardLayout(w, h).corner)
        art.recycle(); frame.recycle()
        return out
    }

    fun renderBack(back: Cosmetic, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val (bg1, bg2, ink) = when (back) {
            Cosmetic.BACK_SAKURA -> Triple(0xFFFFE4EE.toInt(), 0xFFF7B6CF.toInt(), 0xFFD9467C.toInt())
            Cosmetic.BACK_LANTERN -> Triple(0xFF141B3A.toInt(), 0xFF25305E.toInt(), 0xFFFFB04A.toInt())
            Cosmetic.BACK_WAVE -> Triple(0xFF1B2B5E.toInt(), 0xFF2E4A8F.toInt(), 0xFFE9F1FF.toInt())
            Cosmetic.BACK_GOLD -> Triple(0xFF121010.toInt(), 0xFF2A2320.toInt(), 0xFFE2B857.toInt())
            else -> Triple(0xFFF6EFE3.toInt(), 0xFFE8DCC8.toInt(), 0xFFB08A4A.toInt())
        }
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bg1, bg2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val rng = Rng(back.ordinal * 101L + 3)
        when (back) {
            Cosmetic.BACK_SAKURA -> repeat(40) { drawPetal(c, rng.nextInt(w).toFloat(), rng.nextInt(h).toFloat(), w * 0.035f, rng.nextInt(360).toFloat(), withAlpha(ink, 120)) }
            Cosmetic.BACK_LANTERN -> repeat(14) { i -> drawLantern(c, (i % 4 + 0.5f) * w / 4f, (i / 4 + 0.5f) * h / 4f, w * 0.06f, withAlpha(ink, 200)) }
            Cosmetic.BACK_WAVE -> drawWaves(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), withAlpha(ink, 90), withAlpha(ink, 160))
            Cosmetic.BACK_GOLD -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.004f; p.color = withAlpha(ink, 140)
                for (k in 0 until 18) c.drawLine(0f, k * h / 18f, w.toFloat(), k * h / 18f + h * 0.1f, p)
                p.style = Paint.Style.FILL
            }
            else -> {
                p.color = withAlpha(ink, 40)
                for (y in 0 until 14) for (x in 0 until 10) if ((x + y) % 2 == 0) c.drawCircle((x + 0.5f) * w / 10f, (y + 0.5f) * h / 14f, w * 0.012f, p)
            }
        }
        // Central emblem
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.012f; p.color = ink
        c.drawCircle(w / 2f, h / 2f, w * 0.22f, p)
        p.strokeWidth = w * 0.005f
        c.drawCircle(w / 2f, h / 2f, w * 0.26f, p)
        p.style = Paint.Style.FILL
        drawBlossom(c, w / 2f, h / 2f, w * 0.13f, ink)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.035f; color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        c.drawText("COZY CARD JOURNEY", w / 2f, h * 0.84f, tp)
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.015f
        val inset = w * 0.04f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), w * 0.04f, w * 0.04f, p)
        clearCorners(c, w, h, CardLayout(w, h).corner)
        return bmp
    }

    fun renderSilhouette(card: CardDef, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val l = CardLayout(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF3A3346.toInt()
        c.drawRoundRect(l.outer, l.corner, l.corner, p)
        p.color = 0xFF4A4258.toInt()
        c.drawRoundRect(l.art, w * 0.02f, w * 0.02f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.01f
        p.color = withAlpha(RarityStyle.accent(card.gameRarity), 160)
        c.drawRoundRect(RectF(w * 0.025f, w * 0.025f, w - w * 0.025f, h - w * 0.025f), l.corner * 0.7f, l.corner * 0.7f, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.2f; color = 0x66FFFFFF; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("?", l.art.centerX(), l.art.centerY() + tp.textSize * 0.35f, tp)
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h * 0.04f; color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
        c.drawText(card.gameRarity.label, w / 2f, l.textBox.centerY(), small)
        return bmp
    }

    // ---------------------------------------------------------------- helpers

    fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun fitText(text: String, p: TextPaint, maxW: Float) {
        while (p.measureText(text) > maxW && p.textSize > 8f) p.textSize *= 0.93f
    }

    private fun clearCorners(c: Canvas, w: Int, h: Int, r: Float) {
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val path = Path().apply {
            fillType = Path.FillType.INVERSE_WINDING
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW)
        }
        c.drawPath(path, clear)
    }

    private inline fun corners(c: Canvas, l: CardLayout, col: Int, s: Float, draw: (Float, Float, Float, Int) -> Unit) {
        val m = l.w * 0.06f
        draw(m, m, s, col); draw(l.w - m, m, s, col); draw(m, l.h - m, s, col); draw(l.w - m, l.h - m, s, col)
    }

    fun drawStar(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        for (i in 0 until 10) {
            val a = Math.PI / 5 * i - Math.PI / 2
            val rr = if (i % 2 == 0) r else r * 0.42f
            val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); c.drawPath(path, p)
    }

    fun drawHeart(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path().apply {
            moveTo(x, y + s * 0.9f)
            cubicTo(x - s * 1.4f, y, x - s * 0.6f, y - s * 1.0f, x, y - s * 0.35f)
            cubicTo(x + s * 0.6f, y - s * 1.0f, x + s * 1.4f, y, x, y + s * 0.9f)
        }
        c.drawPath(path, p)
    }

    fun drawPetal(c: Canvas, x: Float, y: Float, s: Float, rot: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.save(); c.rotate(rot, x, y)
        val path = Path().apply {
            moveTo(x, y - s)
            quadTo(x + s * 0.9f, y - s * 0.2f, x, y + s)
            quadTo(x - s * 0.9f, y - s * 0.2f, x, y - s)
        }
        c.drawPath(path, p); c.restore()
    }

    fun drawBlossom(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (i in 0 until 5) {
            c.save(); c.rotate(i * 72f, x, y)
            val path = Path().apply {
                moveTo(x, y)
                cubicTo(x - r * 0.55f, y - r * 0.45f, x - r * 0.35f, y - r * 1.05f, x - r * 0.08f, y - r)
                lineTo(x, y - r * 0.85f); lineTo(x + r * 0.08f, y - r)
                cubicTo(x + r * 0.35f, y - r * 1.05f, x + r * 0.55f, y - r * 0.45f, x, y)
            }
            c.drawPath(path, p); c.restore()
        }
        p.color = withAlpha(Color.WHITE, 200)
        c.drawCircle(x, y, r * 0.18f, p)
    }

    private fun drawMic(c: Canvas, x: Float, y: Float, s: Float, hi: Int, mid: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = mid; p.strokeWidth = s * 0.08f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(x, y, x, y + s * 0.8f, p)
        p.shader = RadialGradient(x - s * 0.1f, y - s * 0.45f, s * 0.4f, hi, mid, Shader.TileMode.CLAMP)
        c.drawCircle(x, y - s * 0.3f, s * 0.3f, p)
        p.shader = null
        p.color = withAlpha(Color.BLACK, 60); p.strokeWidth = s * 0.015f
        for (k in -2..2) c.drawLine(x - s * 0.25f, y - s * 0.3f + k * s * 0.08f, x + s * 0.25f, y - s * 0.3f + k * s * 0.08f, p)
    }

    private fun drawStageCrowd(c: Canvas, r: RectF, color: Int, rng: Rng) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, 200) }
        var x = r.left
        while (x < r.right) {
            val hgt = r.height() * (0.08f + rng.nextDouble().toFloat() * 0.06f)
            c.drawCircle(x, r.bottom - hgt, r.width() * 0.035f, p)
            c.drawRect(x - r.width() * 0.04f, r.bottom - hgt, x + r.width() * 0.04f, r.bottom, p)
            if (rng.nextInt(3) == 0) {
                p.color = withAlpha(0xFFFFE08A.toInt(), 220)
                c.drawCircle(x + r.width() * 0.02f, r.bottom - hgt - r.height() * 0.1f, r.width() * 0.012f, p)
                p.color = withAlpha(color, 200)
            }
            x += r.width() * 0.085f
        }
    }

    private fun drawNote(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = s * 0.08f }
        c.drawOval(RectF(x - s * 0.3f, y, x + s * 0.1f, y + s * 0.28f), p)
        c.drawLine(x + s * 0.07f, y + s * 0.12f, x + s * 0.07f, y - s * 0.7f, p)
        c.drawLine(x + s * 0.07f, y - s * 0.7f, x + s * 0.4f, y - s * 0.5f, p)
    }

    private fun drawWeb(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = r * 0.012f }
        val spokes = 12
        for (i in 0 until spokes) {
            val a = 2 * Math.PI * i / spokes
            c.drawLine(x, y, x + (cos(a) * r).toFloat(), y + (sin(a) * r).toFloat(), p)
        }
        for (ring in 1..6) {
            val rr = r * ring / 6f
            val path = Path()
            for (i in 0..spokes) {
                val a = 2 * Math.PI * i / spokes
                val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
                if (i == 0) path.moveTo(px, py) else {
                    val am = 2 * Math.PI * (i - 0.5) / spokes
                    path.quadTo(x + (cos(am) * rr * 0.86).toFloat(), y + (sin(am) * rr * 0.86).toFloat(), px, py)
                }
            }
            c.drawPath(path, p)
        }
    }

    private fun drawSpiderEmblem(c: Canvas, x: Float, y: Float, s: Float, color: Int, dark: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = dark; strokeWidth = s * 0.06f; strokeCap = Paint.Cap.ROUND }
        c.drawOval(RectF(x - s * 0.18f, y - s * 0.35f, x + s * 0.18f, y + s * 0.05f), p)
        c.drawOval(RectF(x - s * 0.24f, y, x + s * 0.24f, y + s * 0.6f), p)
        for (side in listOf(-1, 1)) for (k in 0 until 4) {
            val sy = y - s * 0.1f + k * s * 0.15f
            c.drawLine(x, sy, x + side * s * 0.6f, sy - s * 0.25f + k * s * 0.12f, p)
            c.drawLine(x + side * s * 0.6f, sy - s * 0.25f + k * s * 0.12f, x + side * s * 0.8f, sy + s * 0.2f + k * s * 0.1f, p)
        }
        p.color = color
        c.drawCircle(x, y + s * 0.3f, s * 0.08f, p)
    }

    private fun drawMask(c: Canvas, x: Float, y: Float, s: Float, color: Int, eye: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawOval(RectF(x - s, y - s * 1.1f, x + s, y + s * 1.2f), p)
        p.color = eye
        for (side in listOf(-1, 1)) {
            val path = Path().apply {
                moveTo(x + side * s * 0.12f, y - s * 0.1f)
                quadTo(x + side * s * 0.5f, y - s * 0.55f, x + side * s * 0.8f, y - s * 0.25f)
                quadTo(x + side * s * 0.5f, y + s * 0.15f, x + side * s * 0.12f, y - s * 0.1f)
            }
            c.drawPath(path, p)
        }
    }

    private fun drawCityline(c: Canvas, r: RectF, color: Int, rng: Rng) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var x = r.left
        while (x < r.right) {
            val bw = r.width() * (0.07f + rng.nextDouble().toFloat() * 0.08f)
            val bh = r.height() * (0.18f + rng.nextDouble().toFloat() * 0.3f)
            c.drawRect(x, r.bottom - bh, x + bw, r.bottom, p)
            x += bw * 0.92f
        }
    }

    private fun drawCityWindows(c: Canvas, r: RectF, color: Int, rng: Rng) {
        val p = Paint().apply { this.color = withAlpha(color, 170) }
        repeat(60) {
            val x = r.left + rng.nextDouble().toFloat() * r.width()
            val y = r.bottom - rng.nextDouble().toFloat() * r.height() * 0.3f
            c.drawRect(x, y, x + r.width() * 0.012f, y + r.width() * 0.016f, p)
        }
    }

    private fun drawMoon(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = withAlpha(color, 70)
        p.maskFilter = BlurMaskFilter(r * 0.5f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(x, y, r * 1.3f, p)
        p.maskFilter = null
        p.color = color
        c.drawCircle(x, y, r, p)
        p.color = withAlpha(Color.BLACK, 25)
        c.drawCircle(x - r * 0.3f, y - r * 0.2f, r * 0.18f, p)
        c.drawCircle(x + r * 0.25f, y + r * 0.3f, r * 0.12f, p)
    }

    private fun drawBolt(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path().apply {
            moveTo(x + s * 0.1f, y - s * 0.5f); lineTo(x - s * 0.22f, y + s * 0.05f); lineTo(x, y + s * 0.05f)
            lineTo(x - s * 0.1f, y + s * 0.5f); lineTo(x + s * 0.24f, y - s * 0.08f); lineTo(x + s * 0.02f, y - s * 0.08f); close()
        }
        p.maskFilter = BlurMaskFilter(s * 0.06f, BlurMaskFilter.Blur.SOLID)
        c.drawPath(path, p)
    }

    private fun drawShell(c: Canvas, x: Float, y: Float, r: Float, color: Int, line: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawOval(RectF(x - r, y - r * 1.15f, x + r, y + r * 1.15f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.06f; p.color = line
        val hex = { cx: Float, cy: Float, s: Float ->
            val path = Path()
            for (i in 0..6) {
                val a = Math.PI / 3 * i + Math.PI / 6
                val px = cx + (cos(a) * s).toFloat(); val py = cy + (sin(a) * s).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, p)
        }
        hex(x, y, r * 0.32f)
        for (i in 0 until 6) {
            val a = Math.PI / 3 * i
            hex(x + (cos(a) * r * 0.6).toFloat(), y + (sin(a) * r * 0.65).toFloat(), r * 0.26f)
        }
    }

    private fun drawPizza(c: Canvas, x: Float, y: Float, r: Float, rng: Rng) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFE9A44C.toInt(); c.drawCircle(x, y, r, p)
        p.color = 0xFFF4D35E.toInt(); c.drawCircle(x, y, r * 0.86f, p)
        p.color = 0xFFC0392B.toInt()
        repeat(9) {
            val a = rng.nextDouble() * Math.PI * 2; val d = r * 0.6 * Math.sqrt(rng.nextDouble())
            c.drawCircle(x + (cos(a) * d).toFloat(), y + (sin(a) * d).toFloat(), r * 0.11f, p)
        }
        p.color = 0xAA8B5A2B.toInt(); p.strokeWidth = r * 0.03f
        for (i in 0 until 4) {
            val a = Math.PI / 4 * i
            c.drawLine(x - (cos(a) * r).toFloat(), y - (sin(a) * r).toFloat(), x + (cos(a) * r).toFloat(), y + (sin(a) * r).toFloat(), p)
        }
    }

    fun drawLantern(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = withAlpha(color, 80); p.maskFilter = BlurMaskFilter(s * 0.6f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(x, y, s * 1.2f, p)
        p.maskFilter = null
        p.color = color
        c.drawOval(RectF(x - s * 0.75f, y - s, x + s * 0.75f, y + s), p)
        p.color = withAlpha(Color.BLACK, 70); p.strokeWidth = s * 0.05f
        for (k in -2..2) c.drawLine(x - s * 0.7f * (1 - k * k / 9f), y + k * s * 0.33f, x + s * 0.7f * (1 - k * k / 9f), y + k * s * 0.33f, p)
        p.color = 0xFF3B2A20.toInt()
        c.drawRect(x - s * 0.3f, y - s * 1.12f, x + s * 0.3f, y - s * 0.92f, p)
        c.drawRect(x - s * 0.3f, y + s * 0.92f, x + s * 0.3f, y + s * 1.12f, p)
    }

    fun drawWaves(c: Canvas, r: RectF, mid: Int, hi: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r.width() * 0.012f }
        val step = r.width() / 6f
        var row = 0
        var y = r.top + step * 0.6f
        while (y < r.bottom + step) {
            var x = r.left - (if (row % 2 == 0) 0f else step / 2)
            while (x < r.right + step) {
                for (k in 1..3) {
                    p.color = if (k == 1) hi else mid
                    c.drawArc(RectF(x - step * k / 3f, y - step * k / 3f, x + step * k / 3f, y + step * k / 3f), 180f, 180f, false, p)
                }
                x += step
            }
            y += step * 0.45f; row++
        }
    }
}
