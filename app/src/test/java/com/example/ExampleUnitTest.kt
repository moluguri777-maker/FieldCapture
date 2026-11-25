package com.example

import com.example.cv.ImageQualityAnalyzer
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.QualityIssue
import com.example.voice.ParsedVoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun voiceParser_matchesAllThreeCommands() {
        val nameplate = VoiceCommandParser.parse("capture nameplate")
        assertTrue(nameplate is VoiceParseResult.Matched)
        assertEquals(CaptureCommandType.NAMEPLATE, (nameplate as VoiceParseResult.Matched).command.commandType)

        val damage = VoiceCommandParser.parse("capture damage basement drywall")
        assertTrue(damage is VoiceParseResult.Matched)
        val dmgCmd = (damage as VoiceParseResult.Matched).command as ParsedVoiceCommand.CaptureDamage
        assertEquals("Basement", dmgCmd.room)
        assertEquals("Drywall", dmgCmd.surface)

        val note = VoiceCommandParser.parse("note: Main breaker lug shows scorch marks")
        assertTrue(note is VoiceParseResult.Matched)
        val noteCmd = (note as VoiceParseResult.Matched).command as ParsedVoiceCommand.TechnicianNote
        assertEquals("Main breaker lug shows scorch marks", noteCmd.noteText)

        val invalid = VoiceCommandParser.parse("hello world")
        assertTrue(invalid is VoiceParseResult.Unrecognized)
    }

    @Test
    fun estimateCatalog_hasFourteenCodesAndMatchesDomainText() {
        assertEquals(14, EstimateCodeCatalog.items.size)
        val hvacMatches = EstimateCodeCatalog.matchFromText("Carrier condenser R-410A refrigerant leak")
        assertTrue(hvacMatches.any { it.code.startsWith("HVC-") })

        val waterMatches = EstimateCodeCatalog.matchFromText("Basement drywall water stain and mold")
        assertTrue(waterMatches.any { it.code == "DRY-RPL-02" || it.code == "WTR-DRY-01" })
    }

    @Test
    fun qualityAnalyzer_flagsDarkAndBlurry_andPassesSharpCentered() {
        val analyzer = ImageQualityAnalyzer()
        val w = 160
        val h = 120

        // 1. Dark flat image -> TOO_DARK & BLURRY
        val darkPixels = IntArray(w * h) { (12 shl 16) or (12 shl 8) or 12 }
        val darkReport = analyzer.analyzePixels(darkPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertFalse(darkReport.passed)
        assertTrue(darkReport.issues.contains(QualityIssue.TOO_DARK))
        assertTrue(darkReport.issues.contains(QualityIssue.BLURRY))

        // 2. Sharp high-contrast centered grid -> PASS
        val sharpPixels = IntArray(w * h) { (215 shl 16) or (215 shl 8) or 215 }
        for (y in 28 until 92) {
            for (x in 36 until 124) {
                if ((x % 8 == 0) || (y % 8 == 0)) {
                    sharpPixels[y * w + x] = (15 shl 16) or (15 shl 8) or 15
                }
            }
        }
        val sharpReport = analyzer.analyzePixels(sharpPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertTrue("Expected sharp centered grid to pass, got: ${sharpReport.summaryMessage}", sharpReport.passed)
    }
}
