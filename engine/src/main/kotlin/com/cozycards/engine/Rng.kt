package com.cozycards.engine

/**
 * Small deterministic PRNG (SplitMix64). The engine never uses kotlin.random so that a
 * (seed, move list) pair always reproduces the exact same game on every platform.
 */
class Rng(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }
}
