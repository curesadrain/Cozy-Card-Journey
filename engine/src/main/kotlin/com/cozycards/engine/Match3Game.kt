package com.cozycards.engine

/**
 * A single play session of one level. Pure Kotlin, deterministic for a given seed and input
 * sequence. All mutations happen through the public move / booster functions which return the
 * visual [Step]s needed by a UI to animate what happened.
 */
class Match3Game(val spec: GameSpec, seed: Long) {

    companion object {
        /** Hard upper bound for cascades in a single move (protection against endless chains). */
        const val MAX_CASCADES = 60
        const val POINTS_PER_PIECE = 10
        const val POINTS_PER_SPECIAL = 30
        const val POINTS_PER_OBSTACLE = 20
    }

    /** When false, no board snapshots are recorded (much faster; used by simulations). */
    var recordSteps = true

    private val rng = Rng(seed)
    private var idCounter = 0
    private fun newId() = ++idCounter

    lateinit var board: Board
        private set
    var stageIndex = 0
        private set
    var movesLeft = spec.moves
        private set
    var movesMade = 0
        private set
    var score = 0
        private set
    var status = GameStatus.PLAYING
        private set
    var lossReason: LossReason? = null
        private set
    var goals: List<GoalProgress> = emptyList()
        private set
    var gravity: Dir = Dir.DOWN
        private set
    lateinit var topology: Topology
        private set
    private lateinit var spawner: Spawner
    private var gravityCycleIndex = 0

    val stage: StageSpec get() = spec.stages[stageIndex]
    val stageCount get() = spec.stages.size
    val totalMoves get() = spec.moves + spec.stages.drop(1).sumOf { it.bonusMoves }

    init {
        loadStage(0)
    }

    // -------------------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------------------

    private fun loadStage(index: Int) {
        stageIndex = index
        val st = spec.stages[index]
        val b = Board(st.width, st.height)
        board = b
        for (y in 0 until st.height) for (x in 0 until st.width) {
            val cs = st.cell(x, y)
            val c = b[x, y]
            c.void = cs.void
            if (cs.void) continue
            c.ice = cs.ice
            c.chain = cs.chain
            c.web = cs.web
            c.blocker = cs.blocker
        }
        gravity = st.gravity
        gravityCycleIndex = 0
        topology = Topology(b, gravity, st.portals)
        spawner = Spawner(rng, spec.colors, ::newId).also {
            it.pendingDrops = st.dropSpawns
            it.pendingKeys = st.keySpawns
        }
        // Pre-placed pieces first, then fill the rest without creating matches.
        for (y in 0 until st.height) for (x in 0 until st.width) {
            val cs = st.cell(x, y)
            val init = cs.initial ?: continue
            val c = b[x, y]
            if (!c.canHold) continue
            val color = when {
                init.kind == PieceKind.DROP -> -1
                init.special == Special.RAINBOW -> -1
                init.color >= 0 -> init.color % spec.colors
                else -> safeColor(Pos(x, y))
            }
            c.piece = Piece(newId(), color, init.kind, init.special)
        }
        for (p in b.positions) {
            val c = b[p]
            if (c.canHold && c.piece == null) c.piece = Piece(newId(), safeColor(p))
        }
        goals = st.goals.map { GoalProgress(it) }
        if (MatchFinder.findMatches(b).isNotEmpty() || !hasPossibleMove()) shuffleBoard()
    }

    /** A colour that does not create a match at [p] given current neighbours. */
    private fun safeColor(p: Pos): Int {
        val candidates = (0 until spec.colors).toMutableList()
        rng.shuffle(candidates)
        val cell = board[p]
        val old = cell.piece
        for (c in candidates) {
            cell.piece = Piece(-1, c)
            val bad = MatchFinder.hasMatchAt(board, p)
            cell.piece = old
            if (!bad) return c
        }
        return candidates.first()
    }

    /** Replaces the board with an exact layout. Used by tests and tools only. */
    internal fun overrideBoard(b: Board) {
        board = b
        topology = Topology(b, gravity, stage.portals)
        idCounter = maxOf(idCounter, 1_000_000)
    }

    // -------------------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------------------

    fun canSwap(a: Pos, b: Pos): Boolean =
        board.inBounds(a) && board.inBounds(b) && a.isAdjacent(b) && board[a].isMovable && board[b].isMovable

    /**
     * Whether swapping a and b is a legal player move: it creates a match, combines specials, or
     * moves a special (specials fire wherever they are moved to, even without a match).
     */
    fun isValidMove(a: Pos, b: Pos): Boolean {
        if (!canSwap(a, b)) return false
        val pa = board[a].piece!!
        val pb = board[b].piece!!
        if (comboKind(pa, pb) || pa.isSpecial || pb.isSpecial) return true
        swapPieces(a, b)
        val ok = MatchFinder.hasMatchAt(board, a) || MatchFinder.hasMatchAt(board, b)
        swapPieces(a, b)
        return ok
    }

    private fun comboKind(pa: Piece, pb: Piece): Boolean {
        if (pa.isSpecial && pb.isSpecial) return true
        if (pa.special == Special.RAINBOW && pb.isColored && pb.kind != PieceKind.DROP) return true
        if (pb.special == Special.RAINBOW && pa.isColored && pa.kind != PieceKind.DROP) return true
        return false
    }

    /** A special that can be fired by a double tap. */
    fun canActivate(p: Pos): Boolean = board.inBounds(p) && board[p].isMovable && board[p].piece!!.isSpecial

    fun validMoves(): List<Pair<Pos, Pos>> {
        val out = mutableListOf<Pair<Pos, Pos>>()
        for (p in board.positions) for (d in listOf(Dir.RIGHT, Dir.DOWN)) {
            val q = p + d
            if (isValidMove(p, q)) out += p to q
        }
        return out
    }

    fun hasPossibleMove(): Boolean {
        if (board.positions.any { canActivate(it) }) return true
        for (p in board.positions) for (d in listOf(Dir.RIGHT, Dir.DOWN)) {
            if (isValidMove(p, p + d)) return true
        }
        return false
    }

    val allGoalsDone get() = goals.all { it.done }

    /** 1-3 stars based on how many moves were left at the end. */
    fun stars(): Int {
        if (status != GameStatus.WON) return 0
        val ratio = movesLeft.toDouble() / totalMoves.coerceAtLeast(1)
        return when {
            ratio >= 0.30 -> 3
            ratio >= 0.12 -> 2
            else -> 1
        }
    }

    // -------------------------------------------------------------------------------------
    // Player moves
    // -------------------------------------------------------------------------------------

    fun swap(a: Pos, b: Pos): MoveResult {
        if (status != GameStatus.PLAYING) return MoveResult.Invalid("Game is over")
        if (!board.inBounds(a) || !board.inBounds(b)) return MoveResult.Invalid("Out of bounds")
        if (!a.isAdjacent(b)) return MoveResult.Invalid("Cells are not adjacent")
        if (!board[a].isMovable || !board[b].isMovable) return MoveResult.Invalid("Piece cannot move")
        val pa = board[a].piece!!
        val pb = board[b].piece!!
        val steps = mutableListOf<Step>()
        val events = mutableListOf<GameEvent>()

        if (comboKind(pa, pb)) {
            swapPieces(a, b)
            if (recordSteps) steps += Step(StepKind.SWAP, board.copy())
            consumeMove()
            val r = resolver()
            // After the swap the dragged piece (pa) sits at b: that's the combo centre.
            r.combo(a, board[a].piece!!, b, board[b].piece!!)
            commitClear(r, steps, events, cascade = 1)
            cascade(steps, events, emptyList(), startCascade = 1)
        } else if (pa.isSpecial || pb.isSpecial) {
            // A single special fires where it was moved to; the other piece may still match.
            swapPieces(a, b)
            if (recordSteps) steps += Step(StepKind.SWAP, board.copy())
            consumeMove()
            val firePos = if (pa.isSpecial) b else a
            val r = resolver()
            r.hit(firePos, HitKind.BLAST)
            r.drain()
            commitClear(r, steps, events, cascade = 1)
            cascade(steps, events, emptyList(), startCascade = 1)
        } else {
            swapPieces(a, b)
            if (MatchFinder.findMatches(board).isEmpty()) {
                swapPieces(a, b)
                return MoveResult.Invalid("No match")
            }
            if (recordSteps) steps += Step(StepKind.SWAP, board.copy())
            consumeMove()
            cascade(steps, events, listOf(b, a), startCascade = 0, settleFirst = false)
        }
        afterPlayerMove(steps, events)
        return MoveResult.Applied(steps, events, status)
    }

    /** Double tap on a special: fires it in place. Costs a move. */
    fun activate(p: Pos): MoveResult {
        if (status != GameStatus.PLAYING) return MoveResult.Invalid("Game is over")
        if (!canActivate(p)) return MoveResult.Invalid("Not a special piece")
        val steps = mutableListOf<Step>()
        val events = mutableListOf<GameEvent>()
        consumeMove()
        val r = resolver()
        r.hit(p, HitKind.BLAST)
        r.drain()
        commitClear(r, steps, events, cascade = 1)
        cascade(steps, events, emptyList(), startCascade = 1)
        afterPlayerMove(steps, events)
        return MoveResult.Applied(steps, events, status)
    }

    private fun consumeMove() {
        movesLeft--
        movesMade++
    }

    private fun swapPieces(a: Pos, b: Pos) {
        val t = board[a].piece
        board[a].piece = board[b].piece
        board[b].piece = t
    }

    private fun resolver() = Resolver(board, rng, { goals }, ::newId)

    // -------------------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------------------

    private fun commitClear(r: Resolver, steps: MutableList<Step>, events: MutableList<GameEvent>, cascade: Int, kind: StepKind = StepKind.CLEAR) {
        val multiplier = cascade.coerceAtLeast(1)
        var delta = r.clearedPieces * POINTS_PER_PIECE * multiplier
        for (e in r.events) {
            when (e) {
                is GameEvent.SpecialCreated -> delta += POINTS_PER_SPECIAL
                is GameEvent.BoxDamaged, is GameEvent.StoneDamaged, is GameEvent.ChainBroken,
                is GameEvent.WebCleared, is GameEvent.IceCracked, is GameEvent.LockOpened -> delta += POINTS_PER_OBSTACLE
                else -> Unit
            }
            goals.forEach { it.apply(e) }
        }
        score += delta
        goals.forEach { it.addScore(delta) }
        events += r.events
        if (recordSteps) steps += Step(kind, board.copy(), r.touched.toSet(), r.effects.toList(), r.events.toList(), cascade)
    }

    /**
     * Settle → deliver → match loop. Bounded by [MAX_CASCADES]; if the limit is reached the
     * remaining matches are broken up by recolouring so the board is never left mid-resolution.
     */
    private fun cascade(
        steps: MutableList<Step>,
        events: MutableList<GameEvent>,
        preferred: List<Pos>,
        startCascade: Int,
        settleFirst: Boolean = true,
    ) {
        var cascadeIndex = startCascade
        var preferredCells = preferred
        var needSettle = settleFirst
        while (true) {
            if (needSettle) settleAndDeliver(steps, events)
            needSettle = true
            val groups = MatchFinder.findMatches(board)
            if (groups.isEmpty()) break
            if (cascadeIndex >= MAX_CASCADES) {
                breakMatches()
                break
            }
            cascadeIndex++
            val r = resolver()
            for (g in groups) r.clearGroup(g, chooseSpecialCell(g, preferredCells))
            r.drain()
            commitClear(r, steps, events, cascadeIndex)
            preferredCells = emptyList()
        }
    }

    private fun settleAndDeliver(steps: MutableList<Step>, events: MutableList<GameEvent>) {
        var guard = 0
        while (guard++ < 50) {
            val res = Gravity.settle(board, topology, spawner)
            if (res.moved && recordSteps) steps += Step(StepKind.FALL, board.copy(), spawnedIds = res.spawnedIds)
            val delivered = board.positions.filter { topology.isExit(it) && board[it].piece?.kind == PieceKind.DROP }
            if (delivered.isEmpty()) return
            val evs = delivered.map { p ->
                board[p].piece = null
                GameEvent.DropDelivered(p)
            }
            evs.forEach { e -> goals.forEach { it.apply(e) } }
            score += evs.size * 100
            goals.forEach { it.addScore(evs.size * 100) }
            events += evs
            if (recordSteps) steps += Step(StepKind.CLEAR, board.copy(), delivered.toSet(), emptyList(), evs)
        }
    }

    private fun chooseSpecialCell(g: MatchGroup, preferred: List<Pos>): Pos? {
        if (g.createdSpecial == Special.NONE) return null
        fun ok(p: Pos): Boolean {
            val c = board[p]
            val piece = c.piece ?: return false
            return !piece.isSpecial && c.chain == 0
        }
        preferred.firstOrNull { it in g.cells && ok(it) }?.let { return it }
        g.pivot?.let { if (ok(it)) return it }
        val sorted = g.cells.sortedWith(compareBy({ it.y }, { it.x }))
        val mid = sorted[sorted.size / 2]
        if (ok(mid)) return mid
        return sorted.firstOrNull { ok(it) }
    }

    /** Recolour pieces so that no match remains (used only when the cascade cap is hit). */
    private fun breakMatches() {
        var guard = 0
        while (guard++ < 20) {
            val groups = MatchFinder.findMatches(board)
            if (groups.isEmpty()) return
            for (g in groups) {
                val p = g.cells.first()
                val piece = board[p].piece ?: continue
                board[p].piece = piece.copy(color = safeColor(p))
            }
        }
    }

    private fun afterPlayerMove(steps: MutableList<Step>, events: MutableList<GameEvent>) {
        if (!allGoalsDone) {
            val st = stage
            if (st.conveyors.isNotEmpty()) {
                for (loop in st.conveyors) shiftConveyor(loop)
                if (recordSteps) steps += Step(StepKind.CONVEYOR, board.copy())
                cascade(steps, events, emptyList(), 0)
            }
            if (st.gravitySwitchEvery > 0 && st.gravityCycle.isNotEmpty() && movesMade % st.gravitySwitchEvery == 0) {
                gravityCycleIndex = (gravityCycleIndex + 1) % st.gravityCycle.size
                gravity = st.gravityCycle[gravityCycleIndex]
                topology = Topology(board, gravity, st.portals)
                val e = GameEvent.GravityChanged(gravity)
                events += e
                if (recordSteps) steps += Step(StepKind.GRAVITY, board.copy(), events = listOf(e))
                cascade(steps, events, emptyList(), 0)
            }
        }
        updateStatus(steps, events)
    }

    private fun shiftConveyor(loop: List<Pos>) {
        if (loop.size < 2) return
        if (loop.any { !board[it].canHold || board[it].chain > 0 || board[it].web }) return
        val pieces = loop.map { board[it].piece }
        for (i in loop.indices) board[loop[(i + 1) % loop.size]].piece = pieces[i]
    }

    private fun updateStatus(steps: MutableList<Step>, events: MutableList<GameEvent>) {
        if (status != GameStatus.PLAYING) return
        if (allGoalsDone) {
            if (stageIndex < spec.stages.lastIndex) {
                val e = GameEvent.StageCleared(stageIndex)
                events += e
                loadStage(stageIndex + 1)
                movesLeft += stage.bonusMoves
                if (recordSteps) steps += Step(StepKind.STAGE, board.copy(), events = listOf(e))
                return
            }
            status = GameStatus.WON
            return
        }
        if (movesLeft <= 0) {
            status = GameStatus.LOST
            lossReason = LossReason.OUT_OF_MOVES
            return
        }
        if (!hasPossibleMove()) {
            if (shuffleBoard()) {
                events += GameEvent.Shuffled
                if (recordSteps) steps += Step(StepKind.SHUFFLE, board.copy(), events = listOf(GameEvent.Shuffled))
            } else {
                status = GameStatus.LOST
                lossReason = LossReason.NO_POSSIBLE_MOVES
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Shuffle
    // -------------------------------------------------------------------------------------

    /**
     * Rearranges movable pieces until the board has no ready matches and at least one valid
     * move. Falls back to recolouring and finally to constructing a guaranteed move pattern.
     * Returns false only when the board physically cannot host a move.
     */
    fun shuffleBoard(): Boolean {
        val slots = board.positions.filter { board[it].isMovable && board[it].piece!!.kind != PieceKind.DROP }
        if (slots.size < 3) return false
        val pieces = slots.map { board[it].piece!! }.toMutableList()
        repeat(60) {
            rng.shuffle(pieces)
            slots.forEachIndexed { i, p -> board[p].piece = pieces[i] }
            if (MatchFinder.findMatches(board).isEmpty() && hasPossibleMove()) return true
        }
        val plain = slots.filter { !board[it].piece!!.isSpecial }
        repeat(20) {
            for (p in plain) board[p].piece = board[p].piece!!.copy(color = rng.nextInt(spec.colors))
            for (p in plain) board[p].piece = board[p].piece!!.copy(color = safeColor(p))
            if (MatchFinder.findMatches(board).isEmpty() && hasPossibleMove()) return true
        }
        return forceMovePattern(plain.toSet())
    }

    private fun forceMovePattern(plain: Set<Pos>): Boolean {
        for (p0 in plain.sortedWith(compareBy({ it.y }, { it.x }))) {
            for (horizontal in listOf(true, false)) {
                val step = if (horizontal) Dir.RIGHT else Dir.DOWN
                val p1 = p0 + step
                val p2 = p1 + step
                if (p1 !in plain || p2 !in plain) continue
                for (side in step.sides) {
                    val p3 = p2 + side
                    if (p3 !in plain) continue
                    val saved = listOf(p0, p1, p2, p3).associateWith { board[it].piece!! }
                    for (c in 0 until spec.colors) {
                        board[p0].piece = saved[p0]!!.copy(color = c)
                        board[p1].piece = saved[p1]!!.copy(color = c)
                        board[p3].piece = saved[p3]!!.copy(color = c)
                        board[p2].piece = saved[p2]!!.copy(color = safeColor(p2))
                        if (MatchFinder.findMatches(board).isEmpty() && hasPossibleMove()) return true
                    }
                    saved.forEach { (p, piece) -> board[p].piece = piece }
                }
            }
        }
        return false
    }

    // -------------------------------------------------------------------------------------
    // Boosters (do not consume moves)
    // -------------------------------------------------------------------------------------

    private inline fun boosterAction(action: (Resolver) -> Unit): MoveResult {
        if (status != GameStatus.PLAYING) return MoveResult.Invalid("Game is over")
        val steps = mutableListOf<Step>()
        val events = mutableListOf<GameEvent>()
        val r = resolver()
        action(r)
        r.drain()
        commitClear(r, steps, events, 1, StepKind.BOOSTER)
        cascade(steps, events, emptyList(), 1)
        updateStatus(steps, events)
        return MoveResult.Applied(steps, events, status)
    }

    fun useHammer(p: Pos): MoveResult {
        if (!board.inBounds(p) || board[p].void) return MoveResult.Invalid("Invalid target")
        return boosterAction { r ->
            r.effects += Effect.Hammer(p)
            r.hit(p, HitKind.BLAST)
        }
    }

    fun useSuperHammer(p: Pos): MoveResult {
        if (!board.inBounds(p) || board[p].void) return MoveResult.Invalid("Invalid target")
        return boosterAction { r ->
            r.effects += Effect.Hammer(p)
            repeat(3) { r.hit(p, HitKind.BLAST) }
            for (n in p.neighbors4()) r.hit(n, HitKind.BLAST)
        }
    }

    fun useAreaClear(p: Pos): MoveResult {
        if (!board.inBounds(p) || board[p].void) return MoveResult.Invalid("Invalid target")
        return boosterAction { r -> r.square(p, 1) }
    }

    /** Swap two adjacent movable pieces without needing a match and without spending a move. */
    fun useSwapGlove(a: Pos, b: Pos): MoveResult {
        if (status != GameStatus.PLAYING) return MoveResult.Invalid("Game is over")
        if (!canSwap(a, b)) return MoveResult.Invalid("Pieces cannot be swapped")
        val steps = mutableListOf<Step>()
        val events = mutableListOf<GameEvent>()
        val pa = board[a].piece!!
        val pb = board[b].piece!!
        swapPieces(a, b)
        if (recordSteps) steps += Step(StepKind.SWAP, board.copy())
        if (comboKind(pa, pb)) {
            val r = resolver()
            r.combo(a, board[a].piece!!, b, board[b].piece!!)
            commitClear(r, steps, events, 1)
            cascade(steps, events, emptyList(), 1)
        } else {
            cascade(steps, events, listOf(b, a), 0, settleFirst = false)
        }
        updateStatus(steps, events)
        return MoveResult.Applied(steps, events, status)
    }

    fun useShuffle(): MoveResult {
        if (status != GameStatus.PLAYING) return MoveResult.Invalid("Game is over")
        val ok = shuffleBoard()
        if (!ok) return MoveResult.Invalid("Board cannot be shuffled")
        val steps = listOf(Step(StepKind.SHUFFLE, board.copy(), events = listOf(GameEvent.Shuffled)))
        return MoveResult.Applied(steps, listOf(GameEvent.Shuffled), status)
    }

    fun addMoves(n: Int) {
        require(n > 0)
        if (status == GameStatus.PLAYING) movesLeft += n
    }

    /**
     * Pre-level boosters: turns random plain pieces into the given specials. Returns the cells used.
     */
    fun placeSpecials(specials: List<Special>): List<Pos> {
        val used = mutableListOf<Pos>()
        for (s in specials) {
            if (s == Special.NONE) continue
            val candidates = board.positions.filter {
                val c = board[it]
                c.isMovable && c.piece!!.kind == PieceKind.NORMAL && !c.piece!!.isSpecial && it !in used
            }
            if (candidates.isEmpty()) break
            val p = rng.pick(candidates)
            val old = board[p].piece!!
            board[p].piece = old.copy(special = s, color = -1)
            used += p
        }
        return used
    }
}
