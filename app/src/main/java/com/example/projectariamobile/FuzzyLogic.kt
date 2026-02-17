package com.example.projectariamobile

import kotlin.math.min

object FuzzyLogic {
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
     * Words that change the meaning of the target room.
     * If the target is found but preceded by these, the match is rejected.
     */
    private val EXCLUDED_MODIFIERS = setOf(
        "studio", "stuudio", "salu", "sss", "study"
    )

    fun isMatch(ocrText: String, target: String): Boolean {
        // 1. Tokenize into distinct words to respect boundaries
        val targetTokens = tokenize(target)
        if (targetTokens.isEmpty()) return false
        val cleanTargetString = targetTokens.joinToString("")

        val ocrTokens = tokenize(ocrText)
        val windowSize = targetTokens.size

        // If OCR has fewer words than the target, impossible to match
        if (ocrTokens.size < windowSize) return false

        val threshold = calculateThreshold(cleanTargetString)

        // 2. Sliding Window over WORDS (Tokens), not characters
        for (i in 0..ocrTokens.size - windowSize) {
            var totalCost = 0.0
            var isExactMatch = true

            // Calculate Levenshtein distance token-by-token
            for (j in 0 until windowSize) {
                val ocrToken = ocrTokens[i + j]
                val targetToken = targetTokens[j]

                if (ocrToken != targetToken) {
                    isExactMatch = false
                    totalCost += weightedLevenshtein(ocrToken, targetToken)
                }
            }

            // If it falls within our acceptable fuzzy threshold
            if (isExactMatch || totalCost <= threshold) {

                // 3. Contextual filtering: Ignore if preceded by an excluded modifier
                val previousWord = if (i > 0) ocrTokens[i - 1] else ""

                if (EXCLUDED_MODIFIERS.contains(previousWord)) {
                    continue // Skip this match; it's a Studio/Lab, not the base room!
                }

                return true // Valid Match found!
            }
        }

        return false
    }

    /**
     * Tokenizes text by splitting on any non-alphanumeric character.
     * E.g., "Aale-R1B" -> ["aale", "r1b"]
     * E.g., "Salu StuUdio R1" -> ["salu", "stuudio", "r1"]
     */
    private fun tokenize(input: String): List<String> {
        return input.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }

    private fun weightedLevenshtein(lhs: CharSequence, rhs: CharSequence): Double {
        val lhsLen = lhs.length
        val rhsLen = rhs.length

        var cost = DoubleArray(lhsLen + 1) { it.toDouble() }
        var newCost = DoubleArray(lhsLen + 1) { 0.0 }

        for (j in 1..rhsLen) {
            newCost[0] = j.toDouble()
            for (i in 1..lhsLen) {
                val charA = lhs[i - 1]
                val charB = rhs[j - 1]

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

    private fun substitutionCost(a: Char, b: Char): Double {
        val variants = CONFUSIONS[a] ?: emptyList()
        return if (variants.contains(b)) 0.2 else 1.0
    }

    private fun calculateThreshold(target: String): Double {
        return when {
            target.length <= 3 -> 0.4
            target.length <= 5 -> 1.5
            else -> 2.5
        }
    }
}