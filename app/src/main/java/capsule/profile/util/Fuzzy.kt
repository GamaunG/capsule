package capsule.profile.util

/**
 * Fuzzy app search: scores each app against the query over both its display
 * label and its package name, then keeps matches sorted by relevance. The
 * scoring is a compact variant of the product search used elsewhere: exact,
 * prefix, word-start, substring and in-order subsequence matches, from best
 * to worst.
 */
object Fuzzy {
    private const val SCORE_EXACT = 10000
    private const val SCORE_PREFIX = 8000
    private const val SCORE_WORD_START = 6000
    private const val SCORE_CONTAINS = 4000
    private const val SCORE_SUBSEQUENCE = 1000

    /**
     * Returns the relevance of [label] / [packageName] for [query]; 0 means no
     * match. Package-name matches rank slightly below label matches so a hit
     * on the visible name wins ties.
     */
    fun score(
        label: String,
        packageName: String,
        query: String,
    ): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 1
        val tokens = q.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 1

        // Package-name matches rank slightly below label matches so a hit on
        // the visible name wins ties between the two fields.
        val labelScore = scoreField(label.lowercase(), tokens)
        val pkgScore =
            scoreField(packageName.lowercase(), tokens).let {
                if (it > 0) (it - 200).coerceAtLeast(1) else 0
            }
        return maxOf(labelScore, pkgScore)
    }

    private fun scoreField(
        field: String,
        tokens: List<String>,
    ): Int {
        var total = 0
        for (token in tokens) {
            val s = singleTokenScore(field, token)
            if (s <= 0) return 0
            total += s
        }
        return total
    }

    private fun singleTokenScore(
        field: String,
        token: String,
    ): Int {
        if (token.isEmpty()) return 1
        if (field == token) return SCORE_EXACT
        if (field.startsWith(token)) return SCORE_PREFIX + shortnessBonus(field)

        val wordStart = wordStartsAt(field, token)
        if (wordStart >= 0) return SCORE_WORD_START + shortnessBonus(field) - (wordStart / 100)

        val index = field.indexOf(token)
        if (index >= 0) return SCORE_CONTAINS - (index * 10).coerceAtMost(3000)

        if (isSubsequence(field, token)) {
            val spread = subsequenceSpread(field, token)
            return (SCORE_SUBSEQUENCE - (spread - token.length) * 5).coerceAtLeast(1)
        }
        return 0
    }

    /** Position where [token] starts at a word boundary (letter or after . _ - space). */
    private fun wordStartsAt(
        field: String,
        token: String,
    ): Int {
        var i = 0
        while (i + token.length <= field.length) {
            if (field.regionMatches(i, token, 0, token.length)) {
                if (i == 0 || isDelimiter(field[i - 1])) return i
            }
            i++
        }
        return -1
    }

    private fun isDelimiter(c: Char) = c == ' ' || c == '.' || c == '_' || c == '-' || c == '/'

    private fun shortnessBonus(field: String) = (60 - field.length).coerceAtLeast(0)

    /** True when every character of [token] appears in [field] in order. */
    private fun isSubsequence(
        field: String,
        token: String,
    ): Boolean {
        var i = 0
        for (c in field) {
            if (i < token.length && c == token[i]) i++
        }
        return i == token.length
    }

    /** Distance between the first and last matched character of the greedy match. */
    private fun subsequenceSpread(
        field: String,
        token: String,
    ): Int {
        var first = -1
        var last = -1
        var i = 0
        for (index in field.indices) {
            if (i < token.length && field[index] == token[i]) {
                if (first == -1) first = index
                last = index
                i++
            }
        }
        return if (first != -1) last - first + 1 else field.length
    }
}
