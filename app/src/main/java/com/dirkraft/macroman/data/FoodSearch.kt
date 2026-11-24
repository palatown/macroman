package com.dirkraft.macroman.data

import java.text.Normalizer

/**
 * A forgiving search over past food, by name and amount text. Every word typed has to match some word of the name
 * or the amount, in any order. A word matches if the other starts with it, contains it (3+ letters), has its
 * letters in order with the same first letter (an abbreviation), or is within a typo or two of it (one for 4-6
 * letters, two for 7+). "grk yogrt" finds "Greek Yogurt Bowl"; "oat milk" finds a latte logged as "with oat milk".
 */
object FoodSearch {
    /** One way a food was logged: a name and amount, represented by its most recent entry. */
    class Food(val entry: FoodEntry, val count: Int, internal val nameKey: String) {
        internal val words = nameKey.split(' ').filter { it.isNotEmpty() }
        internal val joined = words.joinToString("")
        internal val amountWords = normalize(entry.quantity).split(' ').filter { it.isNotEmpty() }
    }

    /** A result. [byAmount] when some word only matched the amount, so the amount is worth showing. */
    class Match(val entry: FoodEntry, val byAmount: Boolean)

    /** Collapses history to one item per name and amount, most recently eaten first. */
    fun distinct(entries: List<FoodEntry>): List<Food> = entries
        .groupBy { normalize(it.name) to normalize(it.quantity) }
        .filterKeys { it.first.isNotEmpty() }
        .map { (key, logs) -> Food(logs.maxBy { it.time }, logs.size, key.first) }
        .sortedByDescending { it.entry.time }

    private class Hit(val food: Food, val close: Boolean, val score: Int, val byAmount: Boolean)

    /**
     * Matching foods, one per food name (the amount that matched best, or the most recent), in two tiers each
     * newest first: close matches, where every word typed starts or appears inside a word of the name, then the
     * rest (typos, abbreviations, amount-only matches). A blank query gives the most recent foods.
     */
    fun search(foods: List<Food>, query: String, limit: Int = 20): List<Match> {
        val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return foods.distinctBy { it.nameKey }.take(limit).map { Match(it.entry, false) }
        return foods
            .mapNotNull { food ->
                var total = 0
                var close = true
                var byAmount = false
                for (t in tokens) {
                    val inName = maxOf(food.words.maxOfOrNull { score(t, it) } ?: 0, if (t.length >= 3 && t in food.joined) 2 else 0)
                    val inAmount = food.amountWords.maxOfOrNull { score(t, it) } ?: 0
                    if (inName == 0 && inAmount == 0) return@mapNotNull null
                    if (inName == 0) byAmount = true
                    if (inName < 2) close = false
                    total += maxOf(inName * 2, inAmount) // a name hit counts double
                }
                Hit(food, close, total, byAmount)
            }
            // For each name keep its best-matching amount (the list is newest first, so the newest among equals).
            .sortedWith(compareByDescending<Hit> { it.close }.thenByDescending { it.score })
            .distinctBy { it.food.nameKey }
            .sortedWith(compareByDescending<Hit> { it.close }.thenByDescending { it.food.entry.time })
            .take(limit)
            .map { Match(it.food.entry, it.byAmount) }
    }

    private fun score(token: String, word: String): Int {
        if (word.startsWith(token)) return 3
        if (token.length >= 3 && token in word) return 2
        // Abbreviations and dropped vowels: same first letter, the rest in order ("grk" for "greek", "chkn").
        if (token.length >= 2 && token[0] == word[0] && isSubsequence(token, word)) return 1
        if (token.length < 4) return 0
        val allowed = if (token.length >= 7) 2 else 1
        // Against the whole word, and against its start so a half-typed word with a typo still matches.
        val d = minOf(distance(token, word), distance(token, word.take(token.length)))
        return if (d <= allowed) 1 else 0
    }

    private fun isSubsequence(token: String, word: String): Boolean {
        var i = 0
        for (c in word) if (i < token.length && c == token[i]) i++
        return i == token.length
    }

    internal fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** Edit distance counting a swap of two neighbouring letters as one typo. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
