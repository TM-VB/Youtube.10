package com.example.domain.validator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun testValidHttpAndHttpsUrls() {
        assertTrue(UrlValidator.isValidUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(UrlValidator.isValidUrl("http://youtu.be/dQw4w9WgXcQ"))
        assertTrue(UrlValidator.isValidUrl("https://www.tiktok.com/@user/video/123456789"))
        assertTrue(UrlValidator.isValidUrl("https://twitter.com/user/status/123456"))
        assertTrue(UrlValidator.isValidUrl("https://x.com/user/status/123456"))
        assertTrue(UrlValidator.isValidUrl("https://www.instagram.com/reel/C12345/"))
        assertTrue(UrlValidator.isValidUrl("https://vimeo.com/123456789"))
        assertTrue(UrlValidator.isValidUrl("https://fb.watch/abcdef123/"))
    }

    @Test
    fun testInvalidUrls() {
        assertFalse(UrlValidator.isValidUrl(""))
        assertFalse(UrlValidator.isValidUrl("   "))
        assertFalse(UrlValidator.isValidUrl("not a url"))
        assertFalse(UrlValidator.isValidUrl("ftp://example.com/video.mp4"))
        assertFalse(UrlValidator.isValidUrl("file:///android_asset/video.mp4"))
        assertFalse(UrlValidator.isValidUrl("javascript:alert(1)"))
        assertFalse(UrlValidator.isValidUrl("https:// bad url with spaces.com"))
        assertFalse(UrlValidator.isValidUrl("http://localhost"))
    }

    @Test
    fun testPlatformDetection() {
        assertTrue(UrlValidator.isCommonVideoPlatform("https://www.youtube.com/watch?v=123"))
        assertTrue(UrlValidator.isCommonVideoPlatform("https://youtu.be/123"))
        assertTrue(UrlValidator.isCommonVideoPlatform("https://tiktok.com/@test/video/1"))
        assertTrue(UrlValidator.isCommonVideoPlatform("https://x.com/post/1"))
        assertFalse(UrlValidator.isCommonVideoPlatform("https://unknown-domain-test.com/file.mp4"))
    }
}
