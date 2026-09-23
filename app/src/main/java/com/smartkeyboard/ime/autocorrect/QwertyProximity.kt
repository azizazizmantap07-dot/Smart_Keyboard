package com.smartkeyboard.ime.autocorrect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * QWERTY physical key proximity for weighted Levenshtein.
 *
 * Penalty weights:
 *  - same key            = 0.0
 *  - physical neighbor   = 0.5
 *  - near (dist ~2)      = 1.0
 *  - far                 = 1.5
 */
object QwertyProximity {

    /** Approximate key center coordinates on a standard QWERTY row layout. */
    private val COORDS: Map<Char, Pair<Float, Float>> = buildMap {
        val rows = listOf(
            "qwertyuiop" to 0f,
            "asdfghjkl" to 1f,
            "zxcvbnm" to 2f
        )
        for ((row, y) in rows) {
            val offset = when (y) {
                1f -> 0.5f
                2f -> 1.0f
                else -> 0f
            }
            row.forEachIndexed { i, c ->
                put(c, (i + offset) to y)
            }
        }
    }

    fun substitutionCost(a: Char, b: Char): Float {
        if (a == b) return 0f
        val ca = COORDS[a.lowercaseChar()] ?: return 1.5f
        val cb = COORDS[b.lowercaseChar()] ?: return 1.5f
        val dx = ca.first - cb.first
        val dy = ca.second - cb.second
        val dist = sqrt(dx * dx + dy * dy)
        return when {
            dist <= 1.15f -> 0.5f   // adjacent
            dist <= 2.2f -> 1.0f    // near
            else -> 1.5f            // far
        }
    }

    /**
     * Weighted Levenshtein distance between [a] and [b].
     * Early-exits when partial cost exceeds [maxCost].
     */
    fun weightedDistance(a: String, b: String, maxCost: Float = 3.0f): Float {
        val m = a.length
        val n = b.length
        if (abs(m - n) > maxCost + 0.5f) return maxCost + 1f
        if (m == 0) return n.toFloat()
        if (n == 0) return m.toFloat()

        var prev = FloatArray(n + 1) { it.toFloat() }
        var curr = FloatArray(n + 1)

        for (i in 1..m) {
            curr[0] = i.toFloat()
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..n) {
                val cb = b[j - 1]
                val sub = if (ca == cb) 0f else substitutionCost(ca, cb)
                val del = prev[j] + 1f
                val ins = curr[j - 1] + 1f
                val repl = prev[j - 1] + sub
                curr[j] = minOf(del, ins, repl)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxCost) return maxCost + 1f
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
