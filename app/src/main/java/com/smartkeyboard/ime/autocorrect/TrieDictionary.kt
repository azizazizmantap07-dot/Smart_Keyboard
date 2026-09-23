package com.smartkeyboard.ime.autocorrect

/**
 * Prefix tree (Trie) for O(k) dictionary lookup and fast prefix / fuzzy candidates.
 * Each node stores character edge map, isWord flag, and word frequency.
 */
class TrieDictionary {

    class Node {
        val children = HashMap<Char, Node>(8)
        var isWord: Boolean = false
        var frequency: Int = 0
        var word: String? = null
    }

    val root = Node()
    var size: Int = 0
        private set

    fun insert(word: String, frequency: Int = 1) {
        if (word.isEmpty()) return
        var node = root
        for (c in word) {
            node = node.children.getOrPut(c) { Node() }
        }
        if (!node.isWord) {
            node.isWord = true
            size++
        }
        node.frequency = maxOf(node.frequency, frequency)
        node.word = word
    }

    fun contains(word: String): Boolean {
        val node = walk(word) ?: return false
        return node.isWord
    }

    fun frequencyOf(word: String): Int {
        val node = walk(word) ?: return 0
        return if (node.isWord) node.frequency else 0
    }

    /** Collect up to [limit] words that start with [prefix], ordered by frequency. */
    fun prefixSearch(prefix: String, limit: Int = 8): List<Pair<String, Int>> {
        val node = walk(prefix) ?: return emptyList()
        val out = ArrayList<Pair<String, Int>>(limit)
        collect(node, out, limit)
        out.sortByDescending { it.second }
        return out.take(limit)
    }

    /**
     * Collect words within weighted edit distance of [query] using QWERTY matrix.
     * Bounded candidate pool + adaptive limits keep latency low even with 50k+ word trie.
     * Target: well under 15–20 ms on mid-range devices.
     */
    fun fuzzySearch(
        query: String,
        maxDistance: Float = 2.0f,
        limit: Int = 12
    ): List<Pair<String, Float>> {
        if (query.isEmpty()) return emptyList()
        val first = query[0]
        val neighbors = buildList {
            add(first)
            // keys near the first character on QWERTY
            val all = "qwertyuiopasdfghjklzxcvbnm"
            for (c in all) {
                if (c != first && QwertyProximity.substitutionCost(first, c) <= 0.5f) add(c)
            }
        }
        // Adaptive pool: shorter queries need fewer candidates; long queries are rarer typos
        val perBranch = when {
            query.length <= 3 -> 180
            query.length <= 6 -> 140
            else -> 90
        }
        val pool = ArrayList<String>(perBranch * neighbors.size)
        for (ch in neighbors) {
            val child = root.children[ch] ?: continue
            collectWords(child, pool, perBranch)
        }
        val lenSlack = maxDistance + 0.6f
        val out = ArrayList<Pair<String, Float>>(limit * 2)
        for (cand in pool) {
            if (kotlin.math.abs(cand.length - query.length) > lenSlack) continue
            val cost = QwertyProximity.weightedDistance(query, cand, maxDistance)
            if (cost <= maxDistance && cost > 0f) {
                out.add(cand to cost)
            }
        }
        // Prefer lower edit cost; secondary key frequency when available
        out.sortWith(compareBy<Pair<String, Float>> { it.second }
            .thenByDescending { frequencyOf(it.first) })
        return out.distinctBy { it.first }.take(limit)
    }

    private fun collectWords(node: Node, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        if (node.isWord) node.word?.let { out.add(it) }
        for ((_, child) in node.children) {
            if (out.size >= limit) return
            collectWords(child, out, limit)
        }
    }

    private fun walk(word: String): Node? {
        var node = root
        for (c in word) {
            node = node.children[c] ?: return null
        }
        return node
    }

    private fun collect(node: Node, out: MutableList<Pair<String, Int>>, limit: Int) {
        if (out.size >= limit) return
        if (node.isWord) {
            val w = node.word
            if (w != null) out.add(w to node.frequency)
        }
        for ((_, child) in node.children) {
            if (out.size >= limit) return
            collect(child, out, limit)
        }
    }

    }
