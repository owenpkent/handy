package com.handyvoice.app.voice

sealed class VoiceCommand {
    object Start : VoiceCommand()
    object Stop : VoiceCommand()
    object Faster : VoiceCommand()
    object Slower : VoiceCommand()
    data class SetSpeed(val percent: Int) : VoiceCommand()
    data class SetStroke(val percent: Int) : VoiceCommand()
    object Unknown : VoiceCommand()
}

object VoiceCommandParser {
    
    private val startPatterns = listOf("start", "go", "begin", "play")
    private val stopPatterns = listOf("stop", "halt", "pause", "end")
    private val fasterPatterns = listOf("faster", "speed up", "quicker", "increase speed")
    private val slowerPatterns = listOf("slower", "slow down", "decrease speed")
    
    private val numberWords = mapOf(
        "zero" to 0, "ten" to 10, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )
    
    fun parse(transcript: String): VoiceCommand {
        val text = transcript.lowercase().trim()
        
        // Check for speed setting: "set speed to 50" or "speed 50"
        val speedMatch = Regex("""(?:set\s+)?speed\s+(?:to\s+)?(\w+)""").find(text)
        if (speedMatch != null) {
            val value = parseNumber(speedMatch.groupValues[1])
            if (value != null && value in 0..100) {
                return VoiceCommand.SetSpeed(value)
            }
        }
        
        // Check for stroke setting: "set stroke to 50" or "stroke length 50"
        val strokeMatch = Regex("""(?:set\s+)?stroke(?:\s+length)?\s+(?:to\s+)?(\w+)""").find(text)
        if (strokeMatch != null) {
            val value = parseNumber(strokeMatch.groupValues[1])
            if (value != null && value in 0..100) {
                return VoiceCommand.SetStroke(value)
            }
        }
        
        // Check simple commands
        if (startPatterns.any { text.contains(it) }) {
            return VoiceCommand.Start
        }
        
        if (stopPatterns.any { text.contains(it) }) {
            return VoiceCommand.Stop
        }
        
        if (fasterPatterns.any { text.contains(it) }) {
            return VoiceCommand.Faster
        }
        
        if (slowerPatterns.any { text.contains(it) }) {
            return VoiceCommand.Slower
        }
        
        return VoiceCommand.Unknown
    }
    
    private fun parseNumber(text: String): Int? {
        // Try parsing as digit
        text.toIntOrNull()?.let { return it }
        
        // Try parsing as word
        numberWords[text.lowercase()]?.let { return it }
        
        return null
    }
}
