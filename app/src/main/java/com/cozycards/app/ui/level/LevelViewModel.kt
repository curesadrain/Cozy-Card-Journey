package com.cozycards.app.ui.level

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cozycards.app.AppContainer
import com.cozycards.app.audio.Sfx
import com.cozycards.engine.Board
import com.cozycards.engine.GameEvent
import com.cozycards.engine.GameStatus
import com.cozycards.engine.GoalProgress
import com.cozycards.engine.LossReason
import com.cozycards.engine.Match3Game
import com.cozycards.engine.MoveResult
import com.cozycards.engine.Pos
import com.cozycards.engine.Special
import com.cozycards.engine.Step
import com.cozycards.engine.StepKind
import com.cozycards.game.LevelResult
import com.cozycards.game.RewardSummary
import com.cozycards.game.economy.BoosterType
import com.cozycards.levels.LevelDefinition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One step being animated: from [from] to [step.board]. */
data class StepAnim(val id: Int, val from: Board, val step: Step)

data class LevelUiState(
    val board: Board,
    val anim: StepAnim? = null,
    val movesLeft: Int,
    val score: Int,
    val goals: List<GoalProgress>,
    val stageIndex: Int,
    val stageCount: Int,
    val status: GameStatus = GameStatus.PLAYING,
    val lossReason: LossReason? = null,
    val activeBooster: BoosterType? = null,
    val gloveFirst: Pos? = null,
    val selected: Pos? = null,
    val reward: RewardSummary? = null,
    val stars: Int = 0,
    val finished: Boolean = false,
    val paused: Boolean = false,
    val message: String? = null,
    val hint: Pair<Pos, Pos>? = null,
    val gravity: com.cozycards.engine.Dir,
    val shuffledNotice: Boolean = false,
    val wiggle: Wiggle? = null,
)

class LevelViewModel(
    private val container: AppContainer,
    val def: LevelDefinition,
    private val preBoosters: Set<BoosterType>,
) : ViewModel() {
    private val service = container.repository.service
    private lateinit var game: Match3Game
    private val pending = ArrayDeque<Step>()
    private var stepCounter = 0
    private var watchdog: Job? = null
    private var hintJob: Job? = null

    // Session statistics reported when the level ends.
    private var piecesCleared = 0
    private var specialsCreated = 0
    private var combos = 0
    private var obstaclesCleared = 0

    private val _state = MutableStateFlow(newGame(preBoosters))
    val state: StateFlow<LevelUiState> = _state

    private fun newGame(boosters: Set<BoosterType>): LevelUiState {
        game = Match3Game(def.toGameSpec(), System.nanoTime())
        val specials = buildList {
            if (BoosterType.ROCKET in boosters) add(if (System.nanoTime() % 2 == 0L) Special.ROCKET_H else Special.ROCKET_V)
            if (BoosterType.BOMB in boosters) add(Special.BOMB)
            if (BoosterType.RAINBOW in boosters) add(Special.RAINBOW)
            if (BoosterType.ROCKET_BOMB in boosters) { add(Special.ROCKET_V); add(Special.BOMB) }
        }
        game.placeSpecials(specials)
        if (BoosterType.EXTRA_MOVES in boosters) game.addMoves(5)
        piecesCleared = 0; specialsCreated = 0; combos = 0; obstaclesCleared = 0
        pending.clear()
        scheduleHint()
        return snapshot(game.board)
    }

    private fun snapshot(board: Board, anim: StepAnim? = null) = LevelUiState(
        board = board,
        anim = anim,
        movesLeft = game.movesLeft,
        score = game.score,
        goals = game.goals.map { it.copy() },
        stageIndex = game.stageIndex,
        stageCount = game.stageCount,
        status = game.status,
        lossReason = game.lossReason,
        gravity = game.gravity,
    )

    /** Test hook: legal moves on the current board. */
    internal fun validMovesForTest() = game.validMoves()

    val busy get() = _state.value.anim != null || pending.isNotEmpty() || _state.value.finished

    // ------------------------------------------------------------------ input

    fun onSwap(a: Pos, b: Pos) {
        if (busy || _state.value.status != GameStatus.PLAYING || _state.value.paused) return
        val booster = _state.value.activeBooster
        if (booster == BoosterType.SWAP_GLOVE) { useGlove(a, b); return }
        if (booster != null) return
        when (val r = game.swap(a, b)) {
            is MoveResult.Invalid -> {
                container.audio.play(Sfx.INVALID)
                _state.update { it.copy(selected = null, wiggle = Wiggle(a, b, ++stepCounter), message = if (r.reason == "No match") null else r.reason) }
            }
            is MoveResult.Applied -> enqueue(r)
        }
    }

    fun onTap(p: Pos) {
        val s = _state.value
        if (busy || s.status != GameStatus.PLAYING || s.paused) return
        when (val booster = s.activeBooster) {
            BoosterType.HAMMER, BoosterType.SUPER_HAMMER, BoosterType.AREA_CLEAR -> useTargetBooster(booster, p)
            BoosterType.SWAP_GLOVE -> {
                val first = s.gloveFirst
                when {
                    first == null -> _state.update { it.copy(gloveFirst = p) }
                    first == p -> _state.update { it.copy(gloveFirst = null) }
                    first.isAdjacent(p) -> useGlove(first, p)
                    else -> _state.update { it.copy(gloveFirst = p) }
                }
            }
            null -> {
                val sel = s.selected
                when {
                    sel == null -> _state.update { it.copy(selected = p) }
                    sel == p -> _state.update { it.copy(selected = null) }
                    sel.isAdjacent(p) -> { _state.update { it.copy(selected = null) }; onSwap(sel, p) }
                    else -> _state.update { it.copy(selected = p) }
                }
            }
            else -> Unit
        }
    }

    fun selectBooster(type: BoosterType?) {
        if (busy) return
        val s = _state.value
        if (type == null || s.activeBooster == type) { _state.update { it.copy(activeBooster = null, gloveFirst = null) }; return }
        if ((container.repository.state.value.boosters[type] ?: 0) <= 0) {
            _state.update { it.copy(message = "No ${type.title} left – get more in the Shop") }
            return
        }
        if (type == BoosterType.SHUFFLE) { useShuffle(); return }
        _state.update { it.copy(activeBooster = type, gloveFirst = null, selected = null) }
    }

    private fun useTargetBooster(type: BoosterType, p: Pos) {
        val result = when (type) {
            BoosterType.HAMMER -> game.useHammer(p)
            BoosterType.SUPER_HAMMER -> game.useSuperHammer(p)
            else -> game.useAreaClear(p)
        }
        if (result is MoveResult.Applied) {
            consumeBooster(type)
            container.audio.play(Sfx.BOOSTER)
            _state.update { it.copy(activeBooster = null) }
            enqueue(result)
        }
    }

    private fun useGlove(a: Pos, b: Pos) {
        val r = game.useSwapGlove(a, b)
        if (r is MoveResult.Applied) {
            consumeBooster(BoosterType.SWAP_GLOVE)
            container.audio.play(Sfx.BOOSTER)
            _state.update { it.copy(activeBooster = null, gloveFirst = null) }
            enqueue(r)
        } else {
            container.audio.play(Sfx.INVALID)
            _state.update { it.copy(gloveFirst = null) }
        }
    }

    private fun useShuffle() {
        val r = game.useShuffle()
        if (r is MoveResult.Applied) {
            consumeBooster(BoosterType.SHUFFLE)
            container.audio.play(Sfx.SWISH)
            enqueue(r)
        }
    }

    private fun consumeBooster(type: BoosterType) {
        viewModelScope.launch { runCatching { service.useInLevelBooster(type) } }
    }

    fun togglePause(p: Boolean) = _state.update { it.copy(paused = p) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun restart(boosters: Set<BoosterType> = emptySet()) {
        viewModelScope.launch {
            val ok = runCatching { service.startLevel(def.id, boosters) }.isSuccess
            if (!ok) return@launch
            restartWith(boosters)
        }
    }

    private fun restartWith(boosters: Set<BoosterType>) {
        watchdog?.cancel()
        _state.value = newGame(boosters)
    }

    // ------------------------------------------------------------------ animation queue

    private fun enqueue(r: MoveResult.Applied) {
        hintJob?.cancel()
        trackStats(r.events)
        pending.addAll(r.steps)
        _state.update { it.copy(hint = null, selected = null) }
        advance()
    }

    private fun trackStats(events: List<GameEvent>) {
        for (e in events) when (e) {
            is GameEvent.PieceCleared -> piecesCleared++
            is GameEvent.SpecialCreated -> specialsCreated++
            is GameEvent.Combo -> combos++
            is GameEvent.IceCracked -> if (e.remaining == 0) obstaclesCleared++
            is GameEvent.BoxDamaged -> if (e.remaining == 0) obstaclesCleared++
            is GameEvent.StoneDamaged -> if (e.remaining == 0) obstaclesCleared++
            is GameEvent.ChainBroken -> if (e.remaining == 0) obstaclesCleared++
            is GameEvent.WebCleared, is GameEvent.LockOpened -> obstaclesCleared++
            else -> Unit
        }
    }

    /** Called by the board when the current step's animation finished (or by the watchdog). */
    fun stepDone(id: Int) {
        if (_state.value.anim?.id != id) return
        advance()
    }

    private fun advance() {
        watchdog?.cancel()
        val cur = _state.value
        val from = cur.anim?.step?.board ?: cur.board
        val next = pending.removeFirstOrNull()
        if (next == null) {
            _state.value = snapshot(from).copy(
                activeBooster = cur.activeBooster, gloveFirst = cur.gloveFirst, paused = cur.paused,
                shuffledNotice = false,
            )
            onSettled()
            return
        }
        playSound(next)
        val anim = StepAnim(++stepCounter, from, next)
        _state.value = cur.copy(
            board = from, anim = anim, shuffledNotice = next.kind == StepKind.SHUFFLE,
            movesLeft = game.movesLeft, score = game.score, goals = game.goals.map { it.copy() },
            stageIndex = game.stageIndex, gravity = game.gravity,
        )
        // Watchdog: never let a missing animation callback block the game.
        val id = anim.id
        watchdog = viewModelScope.launch {
            delay(2500)
            stepDone(id)
        }
    }

    private fun playSound(step: Step) {
        val a = container.audio
        val ev = step.events
        when {
            ev.any { it is GameEvent.Combo } -> a.play(Sfx.RAINBOW)
            ev.any { it is GameEvent.SpecialActivated && it.special == Special.RAINBOW } -> a.play(Sfx.RAINBOW)
            ev.any { it is GameEvent.SpecialActivated && it.special == Special.BOMB } -> a.play(Sfx.BOMB)
            ev.any { it is GameEvent.SpecialActivated && (it.special.isRocket || it.special == Special.PLANE) } -> a.play(Sfx.ROCKET)
            ev.any { it is GameEvent.SpecialCreated } -> a.play(Sfx.SPECIAL)
            step.kind == StepKind.CLEAR && step.cascade > 1 -> a.play(Sfx.CASCADE, 1f + (step.cascade - 2) * 0.08f)
            step.kind == StepKind.CLEAR || step.kind == StepKind.BOOSTER -> a.play(Sfx.MATCH)
            step.kind == StepKind.SHUFFLE || step.kind == StepKind.STAGE -> a.play(Sfx.SWISH)
            else -> Unit
        }
    }

    private fun onSettled() {
        if (game.status == GameStatus.PLAYING) {
            scheduleHint()
            return
        }
        if (_state.value.finished) return
        _state.update { it.copy(finished = true) }
        val won = game.status == GameStatus.WON
        container.audio.play(if (won) Sfx.WIN else Sfx.LOSE)
        val result = LevelResult(def.id, won, game.stars(), game.score, game.movesLeft, piecesCleared, specialsCreated, combos, obstaclesCleared)
        viewModelScope.launch {
            val reward = runCatching { service.completeLevel(result) }.getOrNull()
            _state.update { it.copy(reward = reward, stars = game.stars()) }
            if (won) container.audio.play(Sfx.COIN)
        }
    }

    private fun scheduleHint() {
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            delay(6000)
            if (!busy && game.status == GameStatus.PLAYING) {
                val m = game.validMoves().maxByOrNull { (a, b) -> a.y + b.y }
                _state.update { it.copy(hint = m) }
            }
        }
    }

    override fun onCleared() {
        watchdog?.cancel(); hintJob?.cancel()
    }
}
