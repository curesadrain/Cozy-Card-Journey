package com.cozycards.app.renderer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A physical card: rounded rectangle with real thickness. Front face, back face and the side
 * band are separate triangle sets tagged by a face id (0 front, 1 back, 2 edge) so the
 * shader can light and texture each part differently.
 *
 * Units: width 1.0, height 1/[aspect], thickness [thickness]. Vertex layout (9 floats):
 * position xyz, normal xyz, uv, face.
 */
class CardMesh(aspect: Float = 0.714f, private val cornerRadius: Float = 0.055f, private val thickness: Float = 0.014f, segments: Int = 10) {
    val width = 1f
    val height = 1f / aspect
    val vertexCount: Int
    val buffer: FloatBuffer

    init {
        val outline = outline(segments)
        val data = ArrayList<Float>()
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, t: Float, face: Float) {
            data += x; data += y; data += z; data += nx; data += ny; data += nz; data += u; data += t; data += face
        }
        fun uvFront(x: Float, y: Float) = Pair(x / width + 0.5f, 0.5f - y / height)
        val zf = thickness / 2f
        val zb = -thickness / 2f
        // Front: triangle fan expanded into triangles.
        for (i in outline.indices) {
            val a = outline[i]
            val b = outline[(i + 1) % outline.size]
            val (u0, t0) = uvFront(0f, 0f)
            val (u1, t1) = uvFront(a.first, a.second)
            val (u2, t2) = uvFront(b.first, b.second)
            v(0f, 0f, zf, 0f, 0f, 1f, u0, t0, 0f)
            v(a.first, a.second, zf, 0f, 0f, 1f, u1, t1, 0f)
            v(b.first, b.second, zf, 0f, 0f, 1f, u2, t2, 0f)
        }
        // Back: mirrored horizontally so the back texture reads correctly when flipped.
        for (i in outline.indices) {
            val a = outline[i]
            val b = outline[(i + 1) % outline.size]
            fun ub(x: Float, y: Float) = Pair(0.5f - x / width, 0.5f - y / height)
            val (u0, t0) = ub(0f, 0f)
            val (u1, t1) = ub(a.first, a.second)
            val (u2, t2) = ub(b.first, b.second)
            v(0f, 0f, zb, 0f, 0f, -1f, u0, t0, 1f)
            v(b.first, b.second, zb, 0f, 0f, -1f, u2, t2, 1f)
            v(a.first, a.second, zb, 0f, 0f, -1f, u1, t1, 1f)
        }
        // Edge band.
        val normals = outline.indices.map { i ->
            val prev = outline[(i - 1 + outline.size) % outline.size]
            val next = outline[(i + 1) % outline.size]
            val dx = next.first - prev.first
            val dy = next.second - prev.second
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            Pair(dy / len, -dx / len)
        }
        for (i in outline.indices) {
            val j = (i + 1) % outline.size
            val a = outline[i]; val b = outline[j]
            val na = normals[i]; val nb = normals[j]
            val ua = i.toFloat() / outline.size; val ubv = (i + 1).toFloat() / outline.size
            v(a.first, a.second, zf, na.first, na.second, 0f, ua, 0f, 2f)
            v(a.first, a.second, zb, na.first, na.second, 0f, ua, 1f, 2f)
            v(b.first, b.second, zf, nb.first, nb.second, 0f, ubv, 0f, 2f)
            v(b.first, b.second, zf, nb.first, nb.second, 0f, ubv, 0f, 2f)
            v(a.first, a.second, zb, na.first, na.second, 0f, ua, 1f, 2f)
            v(b.first, b.second, zb, nb.first, nb.second, 0f, ubv, 1f, 2f)
        }
        vertexCount = data.size / STRIDE_FLOATS
        buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.forEach { buffer.put(it) }
        buffer.position(0)
    }

    /** Counter-clockwise outline (seen from the front). */
    private fun outline(segments: Int): List<Pair<Float, Float>> {
        val hw = width / 2; val hh = height / 2; val r = cornerRadius
        val centers = listOf(
            Pair(hw - r, hh - r) to 0.0, Pair(-hw + r, hh - r) to 90.0,
            Pair(-hw + r, -hh + r) to 180.0, Pair(hw - r, -hh + r) to 270.0,
        )
        val pts = ArrayList<Pair<Float, Float>>()
        for ((c, start) in centers) {
            for (s in 0..segments) {
                val a = Math.toRadians(start + 90.0 * s / segments)
                pts += Pair(c.first + (cos(a) * r).toFloat(), c.second + (sin(a) * r).toFloat())
            }
        }
        return pts
    }

    companion object {
        const val STRIDE_FLOATS = 9
        const val STRIDE_BYTES = STRIDE_FLOATS * 4
    }
}
