package com.cozycards.levels

import com.cozycards.engine.Blocker
import com.cozycards.engine.BlockerType
import com.cozycards.engine.CellSpec
import com.cozycards.engine.InitialPiece
import com.cozycards.engine.PieceKind
import com.cozycards.engine.Special

/**
 * Text format of level layouts. Each row is a list of space separated tokens, one per cell.
 *
 * Base tokens:
 *  `.` normal cell · `#` void (not part of the board) · `X1`/`X2`/`X3` crate / strong crate / layered crate ·
 *  `S1`/`S2` stone · `L` lock (closed cell) · `D` delivery item · `K` key piece ·
 *  `R` rocket (horizontal) · `V` rocket (vertical) · `B` bomb · `P` paper plane · `*` rainbow sphere.
 *
 * Optional overlay prefixes (any order, before the base token):
 *  `i` ice · `I` double ice · `c` chain · `C` double chain · `w` web.
 *
 * Examples: `i.` ice under a random piece, `Ic.` double ice + chain, `wK` webbed key.
 */
object LayoutCodec {

    data class Layout(val width: Int, val height: Int, val cells: List<com.cozycards.engine.CellSpec>)

    class LayoutException(msg: String) : IllegalArgumentException(msg)

    fun tokens(rows: List<String>): List<List<String>> = rows.map { it.trim().split(Regex("\\s+")) }

    fun decode(rows: List<String>): Layout {
        if (rows.isEmpty()) throw LayoutException("layout has no rows")
        val grid = tokens(rows)
        val w = grid[0].size
        grid.forEachIndexed { y, r -> if (r.size != w) throw LayoutException("row $y has ${r.size} cells, expected $w") }
        val cells = grid.flatMapIndexed { y, row -> row.mapIndexed { x, t -> decodeToken(t, x, y) } }
        return Layout(w, grid.size, cells)
    }

    fun decodeToken(token: String, x: Int = 0, y: Int = 0): CellSpec {
        var t = token
        var ice = 0
        var chain = 0
        var web = false
        while (t.length > 1 && t[0] in "iIcCw") {
            when (t[0]) {
                'i' -> ice = 1
                'I' -> ice = 2
                'c' -> chain = 1
                'C' -> chain = 2
                'w' -> web = true
            }
            t = t.substring(1)
        }
        fun bad(): Nothing = throw LayoutException("unknown token '$token' at ($x,$y)")
        return when (t) {
            "." -> CellSpec(ice = ice, chain = chain, web = web)
            "#" -> if (ice + chain > 0 || web) bad() else CellSpec(void = true)
            "L" -> CellSpec(blocker = Blocker(BlockerType.LOCK, 1)).also { if (ice + chain > 0 || web) bad() }
            "X1", "X2", "X3" -> CellSpec(blocker = Blocker(BlockerType.BOX, t[1] - '0')).also { if (ice + chain > 0 || web) bad() }
            "S1", "S2" -> CellSpec(blocker = Blocker(BlockerType.STONE, t[1] - '0')).also { if (ice + chain > 0 || web) bad() }
            "D" -> CellSpec(ice = ice, initial = InitialPiece(PieceKind.DROP)).also { if (chain > 0 || web) bad() }
            "K" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(PieceKind.KEY))
            "R" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(special = Special.ROCKET_H))
            "V" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(special = Special.ROCKET_V))
            "B" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(special = Special.BOMB))
            "P" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(special = Special.PLANE))
            "*" -> CellSpec(ice = ice, chain = chain, web = web, initial = InitialPiece(special = Special.RAINBOW))
            else -> bad()
        }
    }

    fun encodeToken(c: CellSpec): String {
        if (c.void) return "#"
        c.blocker?.let {
            return when (it.type) {
                BlockerType.BOX -> "X${it.hp}"
                BlockerType.STONE -> "S${it.hp}"
                BlockerType.LOCK -> "L"
            }
        }
        val prefix = buildString {
            if (c.ice == 1) append('i')
            if (c.ice == 2) append('I')
            if (c.chain == 1) append('c')
            if (c.chain == 2) append('C')
            if (c.web) append('w')
        }
        val base = c.initial?.let {
            when {
                it.kind == PieceKind.DROP -> "D"
                it.kind == PieceKind.KEY -> "K"
                it.special == Special.ROCKET_H -> "R"
                it.special == Special.ROCKET_V -> "V"
                it.special == Special.BOMB -> "B"
                it.special == Special.PLANE -> "P"
                it.special == Special.RAINBOW -> "*"
                else -> "."
            }
        } ?: "."
        return prefix + base
    }

    fun encode(width: Int, cells: List<CellSpec>): List<String> =
        cells.chunked(width).map { row -> row.joinToString(" ") { encodeToken(it) } }
}
