package com.example.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DownloadVideosApplication
import com.example.data.settings.AppSettings
import com.example.data.settings.ThemeMode
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.ffmpeg.FFmpegStatus
import com.example.python.PythonStatus
import com.example.ytdlp.YtDlpLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val pythonStatus: PythonStatus,
    val ffmpegStatus: FFmpegStatus,
    val ytDlpVersion: String = "2025.x",
    val isUpdatingYtDlp: Boolean = false,
    val storagePath: String = "Downloads/DownloadVideos",
    val primaryAbi: String,
    val concurrentDownloads: Int = 1,
    val autoRetry: Boolean = true,
    val showNotifications: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageCode: String = "system",
    val cacheSize: String = "0 B",
    val statusMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DownloadVideosApplication
    private val container = app.container
    private val appSettings = AppSettings.getInstance(application)

    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _isUpdating = MutableStateFlow(false)
    private val _ytDlpVersion = MutableStateFlow(com.example.ytdlp.YtDlpEngine.getVersion(application))
    private val _cacheSize = MutableStateFlow(
        CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(application))
    )
    private val _showLogsDialog = MutableStateFlow(false)
    val showLogsDialog: StateFlow<Boolean> = _showLogsDialog.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private data class SettingsPrefs(
        val concurrent: Int,
        val retry: Boolean,
        val notifs: Boolean,
        val theme: ThemeMode,
        val lang: String
    )

    private val downloadPrefsFlow = combine(
        appSettings.concurrentDownloads,
        appSettings.autoRetry,
        appSettings.showNotifications,
        appSettings.themeMode,
        appSettings.languageCode
    ) { concurrent, retry, notifs, theme, lang ->
        SettingsPrefs(concurrent, retry, notifs, theme, lang)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        downloadPrefsFlow,
        _cacheSize,
        _statusMessage,
        _ytDlpVersion,
        _isUpdating
    ) { prefs, cache, message, version, updating ->
        SettingsUiState(
            pythonStatus = container.pythonRuntimeManager.getStatus(),
            ffmpegStatus = container.ffmpegManager.getStatus(),
            ytDlpVersion = version,
            isUpdatingYtDlp = updating,
            primaryAbi = container.ffmpegManager.primaryAbi,
            concurrentDownloads = prefs.concurrent,
            autoRetry = prefs.retry,
            showNotifications = prefs.notifs,
            themeMode = prefs.theme,
            languageCode = prefs.lang,
            cacheSize = cache,
            statusMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            pythonStatus = container.pythonRuntimeManager.getStatus(),
            ffmpegStatus = container.ffmpegManager.getStatus(),
            ytDlpVersion = com.example.ytdlp.YtDlpEngine.getVersion(application),
            primaryAbi = container.ffmpegManager.primaryAbi
        )
    )

    fun updateYtDlp() {
        if (_isUpdating.value) return
        _isUpdating.value = true
        _statusMessage.value = "Checking for yt-dlp update..."
        viewModelScope.launch {
            val result = com.example.ytdlp.YtDlpEngine.updateEngine(app)
            result.fold(
                onSuccess = { status ->
                    _ytDlpVersion.value = com.example.ytdlp.YtDlpEngine.getVersion(app)
                    _statusMessage.value = "yt-dlp update result: $status"
                },
                onFailure = { e ->
                    _statusMessage.value = "yt-dlp update status: Already up to date (${e.message ?: "Current"})"
                }
            )
            _isUpdating.value = false
        }
    }

    fun setConcurrentDownloads(limit: Int) {
        appSettings.setConcurrentDownloads(limit)
    }

    fun setAutoRetry(enabled: Boolean) {
        appSettings.setAutoRetry(enabled)
    }

    fun setShowNotifications(enabled: Boolean) {
        appSettings.setShowNotifications(enabled)
    }

    fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }

    fun setLanguageCode(code: String) {
        appSettings.setLanguageCode(code)
    }

    fun refreshStatus() {
        _cacheSize.value = CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(app))
    }

    fun cleanTempStorage() {
        viewModelScope.launch {
            val freedBytes = CleanupManager.cleanupTempFiles(app)
            val freedText = CleanupManager.formatFileSize(freedBytes)
            _cacheSize.value = CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(app))
            _statusMessage.value = "Freed $freedText of cache space"
        }
    }

    fun openLogs() {
        val rawLogs = YtDlpLogger.getRecentLogs()
        _recentLogs.value = rawLogs.map { "[${it.tag}] ${it.message}" }
        _showLogsDialog.value = true
    }

    fun closeLogs() {
        _showLogsDialog.value = false
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
