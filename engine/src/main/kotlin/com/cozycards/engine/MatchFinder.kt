package com.cozycards.engine

enum class MatchShape { LINE3, LINE4, LINE5, LT, SQUARE }

/**
 * A group of same-coloured cells cleared together. Overlapping runs (L/T shapes) are merged
 * into one group so that simultaneous matches are resolved exactly once.
 */
data class MatchGroup(
    val color: Int,
    val cells: Set<Pos>,
    val shape: MatchShape,
    /** Orientation of the longest run; used for rocket direction. */
    val horizontal: Boolean,
    /** Intersection cell for L/T shapes. */
    val pivot: Pos?,
) {
    val createdSpecial: Special
        get() = when (shape) {
            MatchShape.LINE5 -> Special.RAINBOW
            MatchShape.LT -> Special.BOMB
            MatchShape.LINE4 -> if (horizontal) Special.ROCKET_H else Special.ROCKET_V
            MatchShape.SQUARE -> Special.PLANE
            MatchShape.LINE3 -> Special.NONE
        }
}

object MatchFinder {

    private class Run(val cells: List<Pos>, val horizontal: Boolean)

    fun findMatches(board: Board): List<MatchGroup> {
        val runs = mutableListOf<Run>()
        // Horizontal runs
        for (y in 0 until board.height) {
            var x = 0
            while (x < board.width) {
                val c = board.matchColor(Pos(x, y))
                if (c < 0) { x++; continue }
                var end = x + 1
                while (end < board.width && board.matchColor(Pos(end, y)) == c) end++
                if (end - x >= 3) runs += Run((x until end).map { Pos(it, y) }, true)
                x = end
            }
        }
        // Vertical runs
        for (x in 0 until board.width) {
            var y = 0
            while (y < board.height) {
                val c = board.matchColor(Pos(x, y))
                if (c < 0) { y++; continue }
                var end = y + 1
                while (end < board.height && board.matchColor(Pos(x, end)) == c) end++
                if (end - y >= 3) runs += Run((y until end).map { Pos(x, it) }, false)
                y = end
            }
        }

        // Union runs that share a cell.
        val parent = IntArray(runs.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val owner = HashMap<Pos, Int>()
        runs.forEachIndexed { i, run ->
            for (p in run.cells) {
                val o = owner[p]
                if (o == null) owner[p] = i else parent[root(i)] = root(o)
            }
        }
        val groups = runs.indices.groupBy { root(it) }.values
        val result = mutableListOf<MatchGroup>()
        for (idx in groups) {
            val groupRuns = idx.map { runs[it] }
            val cells = groupRuns.flatMap { it.cells }.toSet()
            val color = board.matchColor(cells.first())
            val longest = groupRuns.maxBy { it.cells.size }
            val hasH = groupRuns.any { it.horizontal }
            val hasV = groupRuns.any { !it.horizontal }
            val shape = when {
                longest.cells.size >= 5 -> MatchShape.LINE5
                hasH && hasV -> MatchShape.LT
                longest.cells.size == 4 -> MatchShape.LINE4
                else -> MatchShape.LINE3
            }
            val pivot = if (hasH && hasV) {
                val h = groupRuns.filter { it.horizontal }.flatMap { it.cells }.toSet()
                groupRuns.filter { !it.horizontal }.flatMap { it.cells }.firstOrNull { it in h }
            } else null
            result += MatchGroup(color, cells, shape, longest.horizontal, pivot)
        }

        // 2x2 squares made of cells not already used by a run.
        val used = HashSet<Pos>(owner.keys)
        for (y in 0 until board.height - 1) for (x in 0 until board.width - 1) {
            val sq = listOf(Pos(x, y), Pos(x + 1, y), Pos(x, y + 1), Pos(x + 1, y + 1))
            val c = board.matchColor(sq[0])
            if (c < 0 || sq.any { it in used || board.matchColor(it) != c }) continue
            used += sq
            result += MatchGroup(c, sq.toSet(), MatchShape.SQUARE, true, null)
        }
        return result
    }

    /** Fast check used by move search: does [p] take part in any 3-run or 2x2 square? */
    fun hasMatchAt(board: Board, p: Pos): Boolean {
        val c = board.matchColor(p)
        if (c < 0) return false
        fun len(dx: Int, dy: Int): Int {
            var n = 0
            var q = Pos(p.x + dx, p.y + dy)
            while (board.matchColor(q) == c) { n++; q = Pos(q.x + dx, q.y + dy) }
            return n
        }
        if (len(-1, 0) + len(1, 0) >= 2) return true
        if (len(0, -1) + len(0, 1) >= 2) return true
        for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) {
            if (board.matchColor(Pos(p.x + dx, p.y)) == c &&
                board.matchColor(Pos(p.x, p.y + dy)) == c &&
                board.matchColor(Pos(p.x + dx, p.y + dy)) == c
            ) return true
        }
        return false
    }
}
