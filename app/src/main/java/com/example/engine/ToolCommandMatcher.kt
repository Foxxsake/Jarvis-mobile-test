package com.example.engine

data class ToolMatchResult(
    val tool: Tool,
    val matchedTerm: String,
    val followUp: String? = null,
    val matchType: MatchType
) {
    enum class MatchType {
        EXACT,
        NORMALIZED,
        FUZZY
    }
}

sealed class ToolMatchOutcome {
    data class Success(val result: ToolMatchResult) : ToolMatchOutcome()
    data class Ambiguous(val candidateTools: List<Tool>) : ToolMatchOutcome()
    object NoMatch : ToolMatchOutcome()
}

class ToolCommandMatcher(
    private val toolsProvider: () -> List<Tool> = { ToolRegistry.STANDARD_TOOLS }
) {
    companion object {
        private val ACTION_PREFIXES = listOf("open ", "launch ", "start ", "go to ")
        private val FILLER_SUFFIXES = listOf(" please", " for me", " thanks")
        private val CONJUNCTIONS = listOf(
            " and then ", ", then ", " then ", ", and ", " and ", " to ", ", "
        )

        fun levenshteinDistance(s1: String, s2: String): Int {
            val a = s1.lowercase()
            val b = s2.lowercase()
            val dp = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) dp[i][0] = i
            for (j in 0..b.length) dp[0][j] = j

            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }
            return dp[a.length][b.length]
        }

        fun normalize(s: String): String =
            s.lowercase().replace(" ", "").replace("-", "").replace("_", "")
    }

    /**
     * Matches a raw command string (e.g., "open Pydroid 3 and start coding", "launch Termux", "Pyroid").
     * Returns ToolMatchOutcome if a registered tool is identified.
     */
    fun match(rawInput: String): ToolMatchOutcome {
        var text = rawInput.trim()
        if (text.isBlank()) return ToolMatchOutcome.NoMatch

        // Remove polite leading words
        if (text.lowercase().startsWith("please ")) {
            text = text.substring(7).trim()
        }

        // Check if there is an action verb prefix
        var targetPhrase = text
        for (prefix in ACTION_PREFIXES) {
            if (text.lowercase().startsWith(prefix)) {
                targetPhrase = text.substring(prefix.length).trim()
                break
            }
        }

        return matchAppPhrase(targetPhrase)
    }

    /**
     * Matches the app phrase portion, extracting the tool and any trailing follow-up intent text.
     */
    fun matchAppPhrase(phrase: String): ToolMatchOutcome {
        var cleaned = phrase.trim()
        if (cleaned.isBlank()) return ToolMatchOutcome.NoMatch

        // Strip trailing filler words
        var strippedFiller = true
        while (strippedFiller) {
            strippedFiller = false
            for (suffix in FILLER_SUFFIXES) {
                if (cleaned.lowercase().endsWith(suffix)) {
                    cleaned = cleaned.substring(0, cleaned.length - suffix.length).trim()
                    strippedFiller = true
                }
            }
        }

        if (cleaned.isBlank()) return ToolMatchOutcome.NoMatch

        val tools = toolsProvider()

        // 1. Direct Exact Match on the entire cleaned phrase (no follow-up)
        val directExact = findExactMatchesOutcome(cleaned, tools)
        if (directExact is ToolMatchOutcome.Success) {
            return directExact
        } else if (directExact is ToolMatchOutcome.Ambiguous) {
            return directExact
        }

        // 2. Direct Normalized Match on the entire cleaned phrase
        val directNorm = findNormalizedTool(cleaned, tools)
        if (directNorm is ToolMatchOutcome.Success) {
            return directNorm
        } else if (directNorm is ToolMatchOutcome.Ambiguous) {
            return directNorm
        }

        // 3. Known tool prefix match (e.g., "Pydroid 3 and start coding", "Pydroid start coding")
        val prefixMatch = findToolByPrefix(cleaned, tools)
        if (prefixMatch is ToolMatchOutcome.Success) {
            return prefixMatch
        } else if (prefixMatch is ToolMatchOutcome.Ambiguous) {
            return prefixMatch
        }

        // 4. Conjunction split (e.g., "Pyroid and start coding", "Git Hub and start coding")
        for (conj in CONJUNCTIONS) {
            if (cleaned.contains(conj, ignoreCase = true)) {
                val conjIndex = cleaned.indexOf(conj, ignoreCase = true)
                val appPart = cleaned.substring(0, conjIndex).trim()
                val followUpRaw = cleaned.substring(conjIndex + conj.length).trim()
                val followUpCleaned = cleanFollowUp(followUpRaw)

                val appMatch = matchSingleTarget(appPart, tools)
                if (appMatch is ToolMatchOutcome.Success) {
                    return ToolMatchOutcome.Success(
                        appMatch.result.copy(followUp = followUpCleaned)
                    )
                } else if (appMatch is ToolMatchOutcome.Ambiguous) {
                    return appMatch
                }
            }
        }

        // 5. Single target matching on cleaned phrase (word matches, fuzzy)
        return matchSingleTarget(cleaned, tools)
    }

    /**
     * Matches a single token or app name without conjunctions.
     * Order of precedence:
     * 1. Exact alias/name match
     * 2. Normalized exact match (e.g. "Git Hub" -> "GitHub")
     * 3. Word-level / generic name match (e.g. "calculator" -> "Samsung Calculator", "Google Calculator")
     * 4. Conservative, unique fuzzy match (e.g. "Pyroid" -> "Pydroid")
     */
    fun matchSingleTarget(query: String, tools: List<Tool> = toolsProvider()): ToolMatchOutcome {
        val clean = query.trim()
        if (clean.isBlank()) return ToolMatchOutcome.NoMatch

        // 1. Exact match
        val exact = findExactMatchesOutcome(clean, tools)
        if (exact !is ToolMatchOutcome.NoMatch) {
            return exact
        }

        // 2. Normalized match
        val norm = findNormalizedTool(clean, tools)
        if (norm !is ToolMatchOutcome.NoMatch) {
            return norm
        }

        // 3. Word-level / generic term match
        val wordMatch = findWordMatches(clean, tools)
        if (wordMatch !is ToolMatchOutcome.NoMatch) {
            return wordMatch
        }

        // 4. Conservative Fuzzy match
        return findFuzzyTool(clean, tools)
    }

    private fun findExactMatchesOutcome(query: String, tools: List<Tool>): ToolMatchOutcome {
        val lower = query.lowercase()
        val matches = mutableListOf<Pair<Tool, String>>()
        for (tool in tools) {
            if (tool.name.lowercase() == lower) {
                matches.add(Pair(tool, tool.name))
            } else if (tool.id.lowercase() == lower) {
                matches.add(Pair(tool, tool.id))
            } else {
                for (alias in tool.aliases) {
                    if (alias.lowercase() == lower) {
                        matches.add(Pair(tool, alias))
                        break
                    }
                }
            }
        }

        val distinctTools = matches.map { it.first }.distinctBy { it.id }
        return resolveMatchesOutcome(matches, ToolMatchResult.MatchType.EXACT)
    }

    private fun resolveMatchesOutcome(
        matches: List<Pair<Tool, String>>,
        matchType: ToolMatchResult.MatchType
    ): ToolMatchOutcome {
        if (matches.isEmpty()) return ToolMatchOutcome.NoMatch

        val distinctTools = matches.map { it.first }.distinctBy { it.id }
        val activeTools = distinctTools.filter { it.enabled && it.policy != com.example.data.AccessPolicy.BLOCK }

        return when {
            activeTools.size == 1 -> {
                val target = activeTools.first()
                val matchedTerm = matches.first { it.first.id == target.id }.second
                ToolMatchOutcome.Success(
                    ToolMatchResult(target, matchedTerm, null, matchType)
                )
            }
            activeTools.size > 1 -> ToolMatchOutcome.Ambiguous(activeTools)
            distinctTools.size == 1 -> {
                val target = distinctTools.first()
                val matchedTerm = matches.first { it.first.id == target.id }.second
                ToolMatchOutcome.Success(
                    ToolMatchResult(target, matchedTerm, null, matchType)
                )
            }
            distinctTools.isNotEmpty() -> ToolMatchOutcome.Ambiguous(distinctTools)
            else -> ToolMatchOutcome.NoMatch
        }
    }

    private fun findWordMatches(query: String, tools: List<Tool>): ToolMatchOutcome {
        val q = query.lowercase().trim()
        if (q.length < 3) return ToolMatchOutcome.NoMatch

        val matches = mutableListOf<Pair<Tool, String>>()
        for (tool in tools) {
            val nameWords = tool.name.lowercase().split(Regex("[\\s\\-_]+")).filter { it.isNotBlank() }
            val aliasWords = tool.aliases.flatMap { it.lowercase().split(Regex("[\\s\\-_]+")) }.filter { it.isNotBlank() }
            
            // Check if any word equals query or query matches tool name
            if (nameWords.any { it == q } || tool.name.lowercase() == q) {
                matches.add(Pair(tool, tool.name))
            } else if (aliasWords.any { it == q }) {
                val matchedAlias = tool.aliases.firstOrNull { it.lowercase().split(Regex("[\\s\\-_]+")).contains(q) } ?: tool.name
                matches.add(Pair(tool, matchedAlias))
            } else if (tool.name.lowercase().contains(q) && q.length >= 4) {
                matches.add(Pair(tool, tool.name))
            }
        }

        return resolveMatchesOutcome(matches, ToolMatchResult.MatchType.NORMALIZED)
    }

    private fun findNormalizedTool(query: String, tools: List<Tool>): ToolMatchOutcome {
        val normQuery = normalize(query)
        if (normQuery.isBlank()) return ToolMatchOutcome.NoMatch

        val matches = mutableListOf<Pair<Tool, String>>()
        for (tool in tools) {
            var matched = false
            if (normalize(tool.name) == normQuery) {
                matches.add(Pair(tool, tool.name))
                matched = true
            }
            if (!matched && normalize(tool.id) == normQuery) {
                matches.add(Pair(tool, tool.id))
                matched = true
            }
            if (!matched) {
                for (alias in tool.aliases) {
                    if (normalize(alias) == normQuery) {
                        matches.add(Pair(tool, alias))
                        break
                    }
                }
            }
        }

        return resolveMatchesOutcome(matches, ToolMatchResult.MatchType.NORMALIZED)
    }

    private fun findToolByPrefix(cleanedPhrase: String, tools: List<Tool>): ToolMatchOutcome? {
        val lowerPhrase = cleanedPhrase.lowercase()

        // Collect all (tool, candidateTerm) pairs sorted by term length descending
        val candidates = mutableListOf<Pair<Tool, String>>()
        for (tool in tools) {
            candidates.add(Pair(tool, tool.name))
            candidates.add(Pair(tool, tool.id))
            for (alias in tool.aliases) {
                candidates.add(Pair(tool, alias))
            }
        }
        candidates.sortByDescending { it.second.length }

        val matches = mutableListOf<Pair<Tool, String>>()
        var longestMatchLen = 0

        for ((tool, term) in candidates) {
            val termLower = term.lowercase()
            if (lowerPhrase.startsWith(termLower)) {
                // Must have word boundary or end of string
                if (lowerPhrase.length == termLower.length || lowerPhrase[termLower.length].isWhitespace() || lowerPhrase[termLower.length] == ',') {
                    if (termLower.length > longestMatchLen) {
                        longestMatchLen = termLower.length
                        matches.clear()
                        matches.add(Pair(tool, term))
                    } else if (termLower.length == longestMatchLen) {
                        matches.add(Pair(tool, term))
                    }
                }
            }
        }

        val distinctTools = matches.map { it.first }.distinctBy { it.id }
        return when {
            distinctTools.size == 1 -> {
                val match = matches.first()
                val rawAfter = cleanedPhrase.substring(match.second.length).trim()
                val followUp = cleanFollowUp(rawAfter)
                ToolMatchOutcome.Success(
                    ToolMatchResult(
                        tool = match.first,
                        matchedTerm = match.second,
                        followUp = followUp,
                        matchType = ToolMatchResult.MatchType.EXACT
                    )
                )
            }
            distinctTools.size > 1 -> ToolMatchOutcome.Ambiguous(distinctTools)
            else -> null
        }
    }

    private fun cleanFollowUp(rawAfter: String): String? {
        var after = rawAfter.trim()
        if (after.lowercase().startsWith("and ")) {
            after = after.substring(4).trim()
        } else if (after.lowercase().startsWith("then ")) {
            after = after.substring(5).trim()
        } else if (after.lowercase().startsWith("to ")) {
            after = after.substring(3).trim()
        }

        var stripped = true
        while (stripped) {
            stripped = false
            for (suffix in FILLER_SUFFIXES) {
                if (after.lowercase().endsWith(suffix)) {
                    after = after.substring(0, after.length - suffix.length).trim()
                    stripped = true
                }
            }
        }

        if (after.equals("please", ignoreCase = true) ||
            after.equals("for me", ignoreCase = true) ||
            after.equals("thanks", ignoreCase = true)
        ) {
            after = ""
        }

        return after.ifBlank { null }
    }

    private fun findFuzzyTool(query: String, tools: List<Tool>): ToolMatchOutcome {
        val word = query.lowercase().trim()
        // Do not fuzzy match short tokens (< 4 chars) to prevent false positives
        if (word.length < 4) return ToolMatchOutcome.NoMatch

        val maxAllowedDistance = if (word.length <= 6) 1 else 2

        data class ToolDistCandidate(val tool: Tool, val term: String, val distance: Int, val similarity: Double)

        val candidates = mutableListOf<ToolDistCandidate>()

        for (tool in tools) {
            val terms = listOf(tool.name, tool.id) + tool.aliases
            var bestTerm: String? = null
            var bestDist = Int.MAX_VALUE
            var bestSim = 0.0

            for (term in terms) {
                // Ignore very short aliases for fuzzy calculation
                if (term.length < 4 && word.length >= 4) continue

                val dist = levenshteinDistance(word, term)
                val sim = 1.0 - (dist.toDouble() / maxOf(word.length, term.length))
                if (dist < bestDist) {
                    bestDist = dist
                    bestTerm = term
                    bestSim = sim
                }
            }

            if (bestTerm != null && bestDist <= maxAllowedDistance && bestSim >= 0.75) {
                candidates.add(ToolDistCandidate(tool, bestTerm, bestDist, bestSim))
            }
        }

        if (candidates.isEmpty()) return ToolMatchOutcome.NoMatch

        val minDist = candidates.minOf { it.distance }
        val bestCandidates = candidates.filter { it.distance == minDist }.distinctBy { it.tool.id }

        return when {
            bestCandidates.size == 1 -> {
                val candidate = bestCandidates.first()
                ToolMatchOutcome.Success(
                    ToolMatchResult(
                        tool = candidate.tool,
                        matchedTerm = candidate.term,
                        followUp = null,
                        matchType = ToolMatchResult.MatchType.FUZZY
                    )
                )
            }
            bestCandidates.size > 1 -> {
                // Ambiguous between multiple tools - do not guess
                ToolMatchOutcome.Ambiguous(bestCandidates.map { it.tool })
            }
            else -> ToolMatchOutcome.NoMatch
        }
    }
}
