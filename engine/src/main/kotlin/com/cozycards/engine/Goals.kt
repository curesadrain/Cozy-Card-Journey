package com.cozycards.engine

class GoalProgress(val goal: Goal) {
    var current: Int = 0
        internal set
    val done get() = current >= goal.target
    val remaining get() = (goal.target - current).coerceAtLeast(0)

    internal fun addScore(delta: Int) {
        if (goal.type == GoalType.SCORE) current += delta
    }

    internal fun apply(e: GameEvent) {
        when (goal.type) {
            GoalType.COLLECT_COLOR -> if (e is GameEvent.PieceCleared && e.color == goal.color) current++
            GoalType.CLEAR_ICE -> if (e is GameEvent.IceCracked && e.remaining == 0) current++
            GoalType.BREAK_BOXES -> if (e is GameEvent.BoxDamaged && e.remaining == 0) current++
            GoalType.BREAK_STONES -> if (e is GameEvent.StoneDamaged && e.remaining == 0) current++
            GoalType.BREAK_CHAINS -> if (e is GameEvent.ChainBroken && e.remaining == 0) current++
            GoalType.CLEAR_WEBS -> if (e is GameEvent.WebCleared) current++
            GoalType.DELIVER_DROPS -> if (e is GameEvent.DropDelivered) current++
            GoalType.OPEN_LOCKS -> if (e is GameEvent.LockOpened) current++
            GoalType.SCORE -> Unit
        }
    }

    fun copy() = GoalProgress(goal).also { it.current = current }
}
