package com.handyvoice.app.voice

sealed class VoiceCommand {
    object Start : VoiceCommand()
    object Stop : VoiceCommand()
    object Faster : VoiceCommand()
    object Slower : VoiceCommand()
    data class SetSpeed(val percent: Int) : VoiceCommand()
    data class SetStroke(val percent: Int) : VoiceCommand()
    data class Preset(val speed: String, val position: String) : VoiceCommand()
    data class SetGlobalMin(val percent: Int) : VoiceCommand()
    data class SetGlobalMax(val percent: Int) : VoiceCommand()
    object Unknown : VoiceCommand()
}

object VoiceCommandParser {

    // --- Known single-word commands (matched against individual words) ---
    private val singleWordCommands = listOf(
        "start" to VoiceCommand.Start,
        "go" to VoiceCommand.Start,
        "begin" to VoiceCommand.Start,
        "play" to VoiceCommand.Start,
        "stop" to VoiceCommand.Stop,
        "halt" to VoiceCommand.Stop,
        "pause" to VoiceCommand.Stop,
        "faster" to VoiceCommand.Faster,
        "quicker" to VoiceCommand.Faster,
        "slower" to VoiceCommand.Slower
    )

    // --- Known two-word commands (matched against consecutive word pairs) ---
    private val twoWordCommands = listOf(
        "speed up" to VoiceCommand.Faster,
        "slow down" to VoiceCommand.Slower,
        "emergency stop" to VoiceCommand.Stop
    )

    // --- Preset commands: speed + position (matched against word pairs) ---
    private data class PresetPhrase(val speedWord: String, val posWord: String, val command: VoiceCommand)

    private val presetPhrases: List<PresetPhrase> = buildList {
        val speeds = listOf("slow", "medium", "med", "fast")
        val positions = listOf("top", "mid", "bot", "full", "bottom", "middle")
        for (s in speeds) {
            for (p in positions) {
                // Normalise "med"→"medium", "bottom"→"bot", "middle"→"mid"
                val normSpeed = if (s == "med") "medium" else s
                val normPos = when (p) {
                    "bottom" -> "bot"
                    "middle" -> "mid"
                    else -> p
                }
                add(PresetPhrase(s, p, VoiceCommand.Preset(normSpeed, normPos)))
            }
        }
    }

    // --- Speed number commands (e.g. "speed fifty") ---
    private val numberWords = mapOf(
        "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90, "hundred" to 100
    )

    // Max Levenshtein ratio to accept a word match
    private const val WORD_THRESHOLD = 0.40f

    fun parse(transcript: String): VoiceCommand {
        val text = transcript.lowercase().trim()
        if (text.isBlank()) return VoiceCommand.Unknown

        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return VoiceCommand.Unknown

        var bestCommand: VoiceCommand = VoiceCommand.Unknown
        var bestScore = Float.MAX_VALUE // lower is better

        // 1. Check presets — find best (speed word, position word) pair in transcript
        for (i in words.indices) {
            for (j in words.indices) {
                if (i == j) continue
                for (pp in presetPhrases) {
                    val sDist = wordRatio(words[i], pp.speedWord)
                    val pDist = wordRatio(words[j], pp.posWord)
                    if (sDist <= WORD_THRESHOLD && pDist <= WORD_THRESHOLD) {
                        val combined = (sDist + pDist) / 2f
                        if (combined < bestScore) {
                            bestScore = combined
                            bestCommand = pp.command
                        }
                    }
                }
            }
        }

        // 2. Check two-word commands (matched on consecutive word pairs)
        for (i in 0 until words.size - 1) {
            val pair = "${words[i]} ${words[i + 1]}"
            for ((phrase, cmd) in twoWordCommands) {
                val ratio = wordRatio(pair, phrase)
                if (ratio <= WORD_THRESHOLD && ratio < bestScore) {
                    bestScore = ratio
                    bestCommand = cmd
                }
            }
        }

        // 3. Check single-word commands (each word individually)
        for (word in words) {
            for ((phrase, cmd) in singleWordCommands) {
                val ratio = wordRatio(word, phrase)
                if (ratio <= WORD_THRESHOLD && ratio < bestScore) {
                    bestScore = ratio
                    bestCommand = cmd
                }
            }
        }

        // 4. Check "speed <number>" patterns
        for (i in 0 until words.size - 1) {
            if (wordRatio(words[i], "speed") <= WORD_THRESHOLD) {
                val numWord = words[i + 1]
                numWord.toIntOrNull()?.let { n ->
                    if (n in 0..100) {
                        val ratio = wordRatio(words[i], "speed")
                        if (ratio < bestScore) {
                            bestScore = ratio
                            bestCommand = VoiceCommand.SetSpeed(n)
                        }
                    }
                }
                numberWords[numWord]?.let { n ->
                    val ratio = wordRatio(words[i], "speed")
                    if (ratio < bestScore) {
                        bestScore = ratio
                        bestCommand = VoiceCommand.SetSpeed(n)
                    }
                }
            }
        }

        // 5. Check "global maximum/minimum <number>" patterns
        for (i in 0 until words.size - 1) {
            val word = words[i]
            val isMax = wordRatio(word, "maximum") <= WORD_THRESHOLD || wordRatio(word, "max") <= WORD_THRESHOLD
            val isMin = wordRatio(word, "minimum") <= WORD_THRESHOLD || wordRatio(word, "min") <= WORD_THRESHOLD
            if (isMax || isMin) {
                val numWord = words[i + 1]
                val num = numWord.toIntOrNull() ?: numberWords[numWord]
                if (num != null && num in 0..100) {
                    val ratio = if (isMax) wordRatio(word, "maximum") else wordRatio(word, "minimum")
                    if (ratio < bestScore) {
                        bestScore = ratio
                        bestCommand = if (isMax) VoiceCommand.SetGlobalMax(num) else VoiceCommand.SetGlobalMin(num)
                    }
                }
            }
        }

        return bestCommand
    }

    private fun wordRatio(a: String, b: String): Float {
        val dist = levenshtein(a, b)
        return dist.toFloat() / maxOf(a.length, b.length, 1)
    }

    // Standard Levenshtein edit distance
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }
}
