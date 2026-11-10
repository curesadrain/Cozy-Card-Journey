package com.cozycards.engine

/** Grid coordinate. x grows to the right, y grows downwards. */
data class Pos(val x: Int, val y: Int) {
    operator fun plus(d: Dir) = Pos(x + d.dx, y + d.dy)
    operator fun minus(d: Dir) = Pos(x - d.dx, y - d.dy)
    fun neighbors4(): List<Pos> = Dir.entries.map { this + it }
    fun isAdjacent(o: Pos) = Math.abs(x - o.x) + Math.abs(y - o.y) == 1
    override fun toString() = "($x,$y)"
}

enum class Dir(val dx: Int, val dy: Int) {
    DOWN(0, 1), UP(0, -1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Dir
        get() = when (this) {
            DOWN -> UP; UP -> DOWN; LEFT -> RIGHT; RIGHT -> LEFT
        }

    /** The two directions perpendicular to this one, in a stable order. */
    val sides: List<Dir>
        get() = if (dx == 0) listOf(LEFT, RIGHT) else listOf(UP, DOWN)
}

enum class Special {
    NONE, ROCKET_H, ROCKET_V, BOMB, RAINBOW, PLANE;

    val isRocket get() = this == ROCKET_H || this == ROCKET_V
}

enum class PieceKind {
    /** Regular coloured tile. */
    NORMAL,

    /** Coloured tile carrying a key. Clearing it opens one lock. */
    KEY,

    /** Colourless delivery item. Must reach an exit cell; immune to blasts. */
    DROP,
}

/**
 * A movable game piece. Immutable; every piece has a unique [id] so that the UI can
 * track it across board snapshots and animate movement.
 */
data class Piece(
    val id: Int,
    val color: Int,
    val kind: PieceKind = PieceKind.NORMAL,
    val special: Special = Special.NONE,
) {
    val isColored get() = color >= 0
    val isSpecial get() = special != Special.NONE
}

enum class BlockerType {
    /** Wooden crate. hp 1 = box, 2 = strong box, 3 = layered crate. Damaged by adjacent matches and blasts. */
    BOX,

    /** Stone. Ignores adjacent matches; only blasts (specials, boosters) damage it. */
    STONE,

    /** Closed cell. Opened only by collecting a key. */
    LOCK,
}

data class Blocker(val type: BlockerType, val hp: Int)

/** Directional teleport: a piece leaving [entrance] in gravity direction arrives at [exit]. */
data class Portal(val entrance: Pos, val exit: Pos)

class Cell(
    var void: Boolean = false,
    var ice: Int = 0,
    var chain: Int = 0,
    var web: Boolean = false,
    var blocker: Blocker? = null,
    var piece: Piece? = null,
) {
    fun copy() = Cell(void, ice, chain, web, blocker, piece)

    /** Can a piece occupy this cell? */
    val canHold get() = !void && blocker == null

    /** Is the piece in this cell free to move (fall or be swapped)? */
    val isMovable get() = piece != null && blocker == null && chain == 0 && !web
}

class Board(val width: Int, val height: Int, private val cells: Array<Cell>) {
    constructor(width: Int, height: Int) : this(width, height, Array(width * height) { Cell() })

    fun inBounds(p: Pos) = p.x in 0 until width && p.y in 0 until height
    operator fun get(p: Pos): Cell = cells[p.y * width + p.x]
    operator fun get(x: Int, y: Int): Cell = cells[y * width + x]
    fun cellOrNull(p: Pos): Cell? = if (inBounds(p)) get(p) else null

    fun copy() = Board(width, height, Array(cells.size) { cells[it].copy() })

    val positions: List<Pos> by lazy { (0 until height).flatMap { y -> (0 until width).map { x -> Pos(x, y) } } }

    fun count(predicate: (Cell) -> Boolean) = cells.count(predicate)

    fun pieceAt(p: Pos): Piece? = cellOrNull(p)?.piece

    fun find(predicate: (Cell) -> Boolean): List<Pos> = positions.filter { predicate(get(it)) }

    /** Colour usable for matching or -1. */
    fun matchColor(p: Pos): Int {
        val c = cellOrNull(p) ?: return -1
        val piece = c.piece ?: return -1
        if (c.void || c.blocker != null) return -1
        if (piece.kind == PieceKind.DROP || piece.special == Special.RAINBOW) return -1
        return piece.color
    }

    /** Debug rendering, one char per cell. */
    fun dump(): String = buildString {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = get(x, y)
                val ch = when {
                    c.void -> ' '
                    c.blocker != null -> when (c.blocker!!.type) {
                        BlockerType.BOX -> 'B'; BlockerType.STONE -> 'S'; BlockerType.LOCK -> 'L'
                    }
                    c.piece == null -> '.'
                    c.piece!!.kind == PieceKind.DROP -> 'D'
                    c.piece!!.special == Special.RAINBOW -> '*'
                    c.piece!!.special != Special.NONE -> 'a' + c.piece!!.color
                    else -> '0' + c.piece!!.color
                }
                append(ch)
            }
            append('\n')
        }
    }
}
