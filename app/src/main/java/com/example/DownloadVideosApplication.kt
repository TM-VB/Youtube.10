package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.ytdlp.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main Application class for Download Videos.
 * Initializes the dependency container and begins background preparation of native/Python engines.
 */
class DownloadVideosApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize Dependency Container
        container = AppContainer(this)

        // Asynchronously initialize Python and yt-dlp native engines in background
        applicationScope.launch {
            try {
                container.pythonRuntimeManager.initialize()
                YtDlpEngine.init(applicationContext)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
}
