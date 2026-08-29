package com.example.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAndSanitizationTest {

    @Test
    fun testPathTraversalProtection() {
        val dangerousInput = "../../../etc/passwd"
        val sanitized = FileNameSanitizer.sanitize(dangerousInput, "mp4")
        assertFalse("Filename must not contain path traversal slashes", sanitized.contains("/"))
        assertFalse("Filename must not contain path traversal backslashes", sanitized.contains("\\"))
        assertEquals("etc_passwd.mp4", sanitized)
    }

    @Test
    fun testCommandInjectionCharactersSanitized() {
        val dangerousInput = "video; rm -rf /; $(whoami) `calc` | cat < foo > bar"
        val sanitized = FileNameSanitizer.sanitize(dangerousInput, "mp4")
        assertFalse("Must not contain pipe", sanitized.contains("|"))
        assertFalse("Must not contain redirect greater than", sanitized.contains(">"))
        assertFalse("Must not contain redirect less than", sanitized.contains("<"))
        assertFalse("Must not contain question mark or colon", sanitized.contains(":"))
        assertFalse("Must not contain asterisk", sanitized.contains("*"))
    }

    @Test
    fun testArabicFilenamesPreservedSafely() {
        val arabicTitle = "فيديو تعليمي رائع عن لغة كوتلن والأندرويد"
        val sanitized = FileNameSanitizer.sanitize(arabicTitle, "mp4")
        assertTrue("Arabic letters should be preserved", sanitized.startsWith("فيديو تعليمي رائع"))
        assertEquals("$arabicTitle.mp4", sanitized)
    }

    @Test
    fun testMixedArabicEnglishAndSpecialChars() {
        val mixedTitle = "درس 01: مقدمة في Compose / Material 3 *HD*"
        val sanitized = FileNameSanitizer.sanitize(mixedTitle, "mp4")
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("*"))
        assertTrue(sanitized.endsWith(".mp4"))
    }

    @Test
    fun testMaxLengthTruncation() {
        val veryLongTitle = "a".repeat(300)
        val sanitized = FileNameSanitizer.sanitize(veryLongTitle, "mp4")
        // Base title truncated to 120 + ".mp4" = 124 chars
        assertTrue(sanitized.length <= 125)
        assertTrue(sanitized.endsWith(".mp4"))
    }
}
