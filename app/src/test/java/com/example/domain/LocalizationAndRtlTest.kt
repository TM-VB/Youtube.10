package com.example.domain

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalizationAndRtlTest {

    @Test
    fun testEnglishStringsLoading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val enLocale = Locale.forLanguageTag("en")
        val config = Configuration(context.resources.configuration)
        config.setLocale(enLocale)
        val enContext = context.createConfigurationContext(config)

        val appName = enContext.getString(R.string.app_name)
        val homeTab = enContext.getString(R.string.tab_home)
        val downloadsTab = enContext.getString(R.string.tab_downloads)
        val historyTab = enContext.getString(R.string.tab_history)

        assertNotNull(appName)
        assertEquals("Home", homeTab)
        assertEquals("Downloads", downloadsTab)
        assertEquals("History", historyTab)
    }

    @Test
    fun testArabicStringsLoadingAndRtlLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arLocale = Locale.forLanguageTag("ar")
        val config = Configuration(context.resources.configuration)
        config.setLocale(arLocale)
        config.setLayoutDirection(arLocale)
        val arContext = context.createConfigurationContext(config)

        val homeTab = arContext.getString(R.string.tab_home)
        val downloadsTab = arContext.getString(R.string.tab_downloads)
        val historyTab = arContext.getString(R.string.tab_history)
        val startDownload = arContext.getString(R.string.btn_start_download)

        assertEquals("الرئيسية", homeTab)
        assertEquals("التنزيلات", downloadsTab)
        assertEquals("السجل", historyTab)
        assertTrue(startDownload.contains("تنزيل") || startDownload.contains("بدء"))
    }
}
