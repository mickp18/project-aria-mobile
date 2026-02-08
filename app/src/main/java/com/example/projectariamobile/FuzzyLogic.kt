package com.example.projectariamobile

import kotlin.math.min

object FuzzyLogic {
    /**
     * Map of common OCR visual confusions.
     * These pairs will have a very low "cost" in the distance calculation.
     */
    private val CONFUSIONS = mapOf(
        '0' to listOf('o', 'd', 'q', 'c'),
        'o' to listOf('0', 'd', 'q', 'c'),
        'c' to listOf('0', 'o'),
        '1' to listOf('i', 'l', '|', '!', 't', 'f'),
        'i' to listOf('1', 'l', '|', '!', 't', 'f'),
        'l' to listOf('1', 'i', '|', '!', 't', 'f'),
        '5' to listOf('s', '$'),
        's' to listOf('5', '$'),
        't' to listOf('1', 'l'),
        '2' to listOf('z'),
        'z' to listOf('2'),
        '8' to listOf('b'),
        'b' to listOf('8'),
        '3' to listOf('e'),
        'e' to listOf('3'),
        'a' to listOf('4'),
        '4' to listOf('a')
    )

    /**
     * Determines if the [target] matches the [ocrText] using robust fuzzy logic.
     *
     * @param ocrText The raw text detected by OCR (e.g., "-> RI")
     * @param target The user's desired destination (e.g., "R1")
     * @return True if a match is found.
     */
    fun isMatch(ocrText: String, target: String): Boolean {
        // Normalize: Lowercase and remove all non-alphanumeric characters.
        //    "Room 101 ->" becomes "room101"
        val cleanOCR = normalize(ocrText)
        val cleanTarget = normalize(target)

        if (cleanTarget.isEmpty()) return false

        // Quick check: Exact substring match (fastest)
        if (cleanOCR.contains(cleanTarget)) return true

        // 2. Adaptive Threshold Calculation
        //    Short targets (R1, 101) need strict matching.
        //    Long targets (Secretariat) can tolerate more errors.
        val threshold = calculateThreshold(cleanTarget)

        // 3. Sliding Window Search
        //    We scan the OCR string looking for the best sub-match.
        val windowSize = cleanTarget.length

        // If OCR is shorter than target, it's impossible to match
        if (cleanOCR.length < windowSize) return false

        for (i in 0..cleanOCR.length - windowSize) {
            val substring = cleanOCR.substring(i, i + windowSize)
            val score = weightedLevenshtein(substring, cleanTarget)

            if (score <= threshold) {
                return true // Match found!
            }
        }

        return false
    }

    /**
     * Calculates the edit distance between two strings, applying lower costs
     * for known visual confusions (e.g. '5' vs 'S').
     */
    private fun weightedLevenshtein(lhs: CharSequence, rhs: CharSequence): Double {
        val lhsLen = lhs.length
        val rhsLen = rhs.length

        // We use Double for fractional costs (0.1, 0.2, etc.)
        var cost = DoubleArray(lhsLen + 1) { it.toDouble() }
        var newCost = DoubleArray(lhsLen + 1) { 0.0 }

        for (j in 1..rhsLen) {
            newCost[0] = j.toDouble()
            for (i in 1..lhsLen) {
                val charA = lhs[i - 1]
                val charB = rhs[j - 1]

                // COST LOGIC:
                // Match = 0.0
                // Confusion (1 vs I) = 0.2 (Low Penalty)
                // Total Mismatch = 1.0 (Full Penalty)
                val matchCost = if (charA == charB) 0.0 else substitutionCost(charA, charB)

                val costReplace = cost[i - 1] + matchCost
                val costInsert = cost[i] + 1.0
                val costDelete = newCost[i - 1] + 1.0

                newCost[i] = min(costInsert, min(costDelete, costReplace))
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    /**
     * Returns the cost of swapping char A for char B.
     */
    private fun substitutionCost(a: Char, b: Char): Double {
        val variants = CONFUSIONS[a] ?: emptyList()
        // If 'b' is a known visual twin of 'a', cost is low (0.2). Otherwise 1.0.
        return if (variants.contains(b)) 0.2 else 1.0
    }

    /**
     * Determines how many errors are allowed based on target length.
     */
    private fun calculateThreshold(target: String): Double {
        return when {
            target.length <= 3 -> 0.4  // Very strict (e.g. "101" -> "IOI" is ok, but "102" is not)
            target.length <= 5 -> 1.5  // "Study" -> "5tudy" (approx 0.4 cost) is ok
            else -> 2.5                // "Laboratory" -> "Laboretory" is ok
        }
    }

    private fun normalize(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}