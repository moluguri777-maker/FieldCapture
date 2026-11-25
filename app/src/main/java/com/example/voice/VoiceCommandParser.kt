package com.example.voice

import com.example.domain.model.CaptureCommandType

sealed interface ParsedVoiceCommand {
    val rawTranscript: String
    val commandType: CaptureCommandType

    data class CaptureNameplate(
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.NAMEPLATE
    }

    data class CaptureDamage(
        val room: String,
        val surface: String,
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.DAMAGE
    }

    data class TechnicianNote(
        val noteText: String,
        override val rawTranscript: String
    ) : ParsedVoiceCommand {
        override val commandType: CaptureCommandType = CaptureCommandType.NOTE
    }
}

sealed interface VoiceParseResult {
    data class Matched(val command: ParsedVoiceCommand) : VoiceParseResult
    data class Unrecognized(val rawTranscript: String, val hint: String) : VoiceParseResult
}

object VoiceCommandParser {

    private val nameplatePattern = Regex(
        """^(capture|take|scan|snap)?\s*(nameplate|name\s*plate|data\s*plate|serial\s*plate|rating\s*plate)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private val damagePattern = Regex(
        """^(capture|take|log)?\s*damage(?:\s+(?:in|on|at))?\s*([a-zA-Z0-9_-]+)?(?:\s+([a-zA-Z0-9_ -]+))?$""",
        RegexOption.IGNORE_CASE
    )

    private val notePattern = Regex(
        """^note\s*[:\-]?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(transcript: String): VoiceParseResult {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            return VoiceParseResult.Unrecognized(
                rawTranscript = transcript,
                hint = "Say: 'capture nameplate', 'capture damage [room] [surface]', or 'note: [text]'"
            )
        }

        if (nameplatePattern.matches(cleaned)) {
            return VoiceParseResult.Matched(ParsedVoiceCommand.CaptureNameplate(cleaned))
        }

        val noteMatch = notePattern.find(cleaned)
        if (noteMatch != null) {
            val body = noteMatch.groupValues[1].trim()
            if (body.isNotEmpty()) {
                return VoiceParseResult.Matched(ParsedVoiceCommand.TechnicianNote(body, cleaned))
            }
        }

        val damageMatch = damagePattern.find(cleaned)
        if (damageMatch != null) {
            val room = damageMatch.groupValues[2].trim().ifEmpty { "Unspecified" }
            val surface = damageMatch.groupValues[3].trim().ifEmpty { "Surface" }
            return VoiceParseResult.Matched(
                ParsedVoiceCommand.CaptureDamage(
                    room = room.replaceFirstChar { it.uppercase() },
                    surface = surface.replaceFirstChar { it.uppercase() },
                    rawTranscript = cleaned
                )
            )
        }

        return VoiceParseResult.Unrecognized(
            rawTranscript = cleaned,
            hint = "Unrecognized command '$cleaned'. Expected 'capture nameplate', 'capture damage [room] [surface]', or 'note: [free text]'."
        )
    }
}
