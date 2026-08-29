package com.example.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun InAppMediaPlayerDialog(
    title: String,
    mediaPath: String?,
    contentUri: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                PlayerCore(
                    title = title,
                    mediaPath = mediaPath,
                    contentUri = contentUri,
                    isFullscreen = true,
                    onToggleFullscreen = { isFullscreen = false },
                    onClose = onDismiss
                )
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 16.dp),
            title = null,
            text = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    PlayerCore(
                        title = title,
                        mediaPath = mediaPath,
                        contentUri = contentUri,
                        isFullscreen = false,
                        onToggleFullscreen = { isFullscreen = true },
                        onClose = onDismiss
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun PlayerCore(
    title: String,
    mediaPath: String?,
    contentUri: String?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // Player States
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewPosition by remember { mutableFloatStateOf(0f) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Audio & Playback Controls
    var volume by remember { mutableFloatStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    // UI Overlay & Gestures
    var areControlsVisible by remember { mutableStateOf(true) }
    var lastUserInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var doubleTapSeekFeedback by remember { mutableStateOf<String?>(null) }

    // Auto-hide controls timer
    LaunchedEffect(areControlsVisible, isPlaying, lastUserInteractionTime) {
        if (areControlsVisible && isPlaying) {
            delay(3500)
            areControlsVisible = false
        }
    }

    // Progress polling loop
    LaunchedEffect(isPlaying, isPrepared) {
        while (isPrepared && isPlaying) {
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying && !isSeeking) {
                        currentPosition = mp.currentPosition.toLong()
                        val dur = mp.duration.toLong()
                        if (dur > 0) duration = dur
                    }
                } catch (_: Exception) {}
            }
            delay(250)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null
        }
    }

    fun triggerInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        areControlsVisible = true
    }

    fun seekRelative(offsetMs: Long) {
        triggerInteraction()
        mediaPlayer?.let { mp ->
            try {
                val newPos = (currentPosition + offsetMs).coerceIn(0L, duration)
                mp.seekTo(newPos.toInt())
                currentPosition = newPos
                if (isCompleted && offsetMs < 0) {
                    isCompleted = false
                    mp.start()
                    isPlaying = true
                }
            } catch (_: Exception) {}
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        mediaPlayer?.let { mp ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = mp.playbackParams ?: PlaybackParams()
                    params.speed = speed
                    mp.playbackParams = params
                }
            } catch (_: Exception) {}
        }
    }

    fun updateVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        isMuted = newVol <= 0f
        mediaPlayer?.let { mp ->
            try {
                val actual = if (isMuted) 0f else volume
                mp.setVolume(actual, actual)
            } catch (_: Exception) {}
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        mediaPlayer?.let { mp ->
            try {
                val actual = if (isMuted) 0f else volume
                mp.setVolume(actual, actual)
            } catch (_: Exception) {}
        }
    }

    val displayTitle = title.ifBlank { stringResource(R.string.media_player_title) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. Native Surface & MediaPlayer rendering
        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = isFullscreen),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                try {
                                    val mp = MediaPlayer().apply {
                                        setDisplay(holder)
                                        setAudioAttributes(
                                            AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                                .build()
                                        )

                                        var sourceLoaded = false

                                        // Try ContentUri first
                                        if (!contentUri.isNullOrBlank()) {
                                            try {
                                                val uri = Uri.parse(contentUri)
                                                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                                    setDataSource(pfd.fileDescriptor)
                                                    sourceLoaded = true
                                                } ?: run {
                                                    setDataSource(ctx, uri)
                                                    sourceLoaded = true
                                                }
                                            } catch (_: Exception) {}
                                        }

                                        // Fallback to mediaPath File
                                        if (!sourceLoaded && !mediaPath.isNullOrBlank()) {
                                            val file = File(mediaPath)
                                            if (file.exists()) {
                                                setDataSource(file.absolutePath)
                                                sourceLoaded = true
                                            }
                                        }

                                        if (!sourceLoaded) {
                                            errorMessage = "Video file could not be opened."
                                            return
                                        }

                                        setOnVideoSizeChangedListener { _, width, height ->
                                            if (width > 0 && height > 0) {
                                                videoAspectRatio = width.toFloat() / height.toFloat()
                                            }
                                        }

                                        setOnPreparedListener { player ->
                                            isPrepared = true
                                            duration = player.duration.toLong().coerceAtLeast(1L)
                                            player.start()
                                            isPlaying = true
                                            val actual = if (isMuted) 0f else volume
                                            player.setVolume(actual, actual)
                                            if (currentSpeed != 1.0f) {
                                                setPlaybackSpeed(currentSpeed)
                                            }
                                        }

                                        setOnCompletionListener {
                                            isPlaying = false
                                            isCompleted = true
                                            currentPosition = duration
                                        }

                                        setOnErrorListener { _, what, extra ->
                                            errorMessage = "Playback error (code $what, $extra)"
                                            isPrepared = false
                                            isPlaying = false
                                            true
                                        }

                                        prepareAsync()
                                    }
                                    mediaPlayer = mp
                                } catch (e: Exception) {
                                    errorMessage = e.localizedMessage ?: "Failed to initialize video player"
                                }
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                mediaPlayer?.setDisplay(null)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Gesture Detector for tap to show/hide controls and double tap to seek -10s / +10s
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            areControlsVisible = !areControlsVisible
                            if (areControlsVisible) triggerInteraction()
                        },
                        onDoubleTap = { offset ->
                            triggerInteraction()
                            val isLeft = offset.x < (size.width / 2)
                            if (isLeft) {
                                seekRelative(-10000L)
                                doubleTapSeekFeedback = "-10s"
                            } else {
                                seekRelative(10000L)
                                doubleTapSeekFeedback = "+10s"
                            }
                        }
                    )
                }
        )

        // 3. Double-tap Seek Animation Badge
        LaunchedEffect(doubleTapSeekFeedback) {
            if (doubleTapSeekFeedback != null) {
                delay(650)
                doubleTapSeekFeedback = null
            }
        }
        AnimatedVisibility(
            visible = doubleTapSeekFeedback != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (doubleTapSeekFeedback?.startsWith("-") == true) Icons.Default.Replay10 else Icons.Default.Forward10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = doubleTapSeekFeedback.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Loading Spinner
        if (!isPrepared && errorMessage == null) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        // 5. Error View
        if (errorMessage != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.Center)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.player_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = errorMessage.orEmpty(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.btn_close), color = Color.White)
                    }
                }
            }
        }

        // 6. Complete HUD Controls Overlay
        AnimatedVisibility(
            visible = areControlsVisible && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Speed Selector Button
                    Box {
                        IconButton(onClick = {
                            triggerInteraction()
                            showSpeedMenu = !showSpeedMenu
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = stringResource(R.string.player_speed),
                                    tint = if (currentSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${currentSpeed}x",
                                    color = if (currentSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false }
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (speed == 1.0f) "Normal (1.0x)" else "${speed}x",
                                            fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        setPlaybackSpeed(speed)
                                        showSpeedMenu = false
                                        triggerInteraction()
                                    }
                                )
                            }
                        }
                    }

                    // Loop Toggle Button
                    IconButton(onClick = {
                        triggerInteraction()
                        isLooping = !isLooping
                        mediaPlayer?.isLooping = isLooping
                    }) {
                        Icon(
                            imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = stringResource(R.string.player_loop),
                            tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Fullscreen Toggle Button
                    IconButton(onClick = {
                        triggerInteraction()
                        onToggleFullscreen()
                    }) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) stringResource(R.string.player_exit_fullscreen) else stringResource(R.string.player_fullscreen),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Close Button
                    IconButton(onClick = {
                        mediaPlayer?.let { mp ->
                            try {
                                if (mp.isPlaying) mp.stop()
                                mp.reset()
                                mp.release()
                            } catch (_: Exception) {}
                        }
                        mediaPlayer = null
                        onClose()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.btn_close),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Center Action Controls: Seek -10s, Play/Pause, Seek +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Rewind 10s
                    FilledTonalIconButton(
                        onClick = { seekRelative(-10000L) },
                        modifier = Modifier.size(50.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = stringResource(R.string.player_seek_backward),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Main Center Play / Pause / Replay Button
                    FilledIconButton(
                        onClick = {
                            triggerInteraction()
                            mediaPlayer?.let { mp ->
                                try {
                                    if (isCompleted) {
                                        mp.seekTo(0)
                                        mp.start()
                                        isPlaying = true
                                        isCompleted = false
                                        currentPosition = 0
                                    } else if (mp.isPlaying) {
                                        mp.pause()
                                        isPlaying = false
                                    } else {
                                        mp.start()
                                        isPlaying = true
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = when {
                                isCompleted -> Icons.Default.Replay
                                isPlaying -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = when {
                                isCompleted -> "Replay"
                                isPlaying -> "Pause"
                                else -> "Play"
                            },
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Fast Forward 10s
                    FilledTonalIconButton(
                        onClick = { seekRelative(10000L) },
                        modifier = Modifier.size(50.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.2f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = stringResource(R.string.player_seek_forward),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Bottom Bar: Scrubber Timeline, Timestamps & Volume
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Timeline Progress Slider
                    val effectivePosition = if (isSeeking) (seekPreviewPosition * duration).toLong() else currentPosition
                    val progressFraction = (effectivePosition.toFloat() / duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)

                    Slider(
                        value = progressFraction,
                        onValueChange = { frac ->
                            triggerInteraction()
                            isSeeking = true
                            seekPreviewPosition = frac
                        },
                        onValueChangeFinished = {
                            val targetMs = (seekPreviewPosition * duration).toLong()
                            mediaPlayer?.let { mp ->
                                try {
                                    mp.seekTo(targetMs.toInt())
                                    currentPosition = targetMs
                                    if (isCompleted) {
                                        isCompleted = false
                                        mp.start()
                                        isPlaying = true
                                    }
                                } catch (_: Exception) {}
                            }
                            isSeeking = false
                            triggerInteraction()
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Bottom info and sub-controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current Elapsed / Total Duration Text
                        Text(
                            text = "${formatDuration(effectivePosition)} / ${formatDuration(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Quick Volume Control
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    triggerInteraction()
                                    toggleMute()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                val volIcon = when {
                                    isMuted || volume == 0f -> Icons.Default.VolumeOff
                                    volume < 0.5f -> Icons.Default.VolumeDown
                                    else -> Icons.Default.VolumeUp
                                }
                                Icon(
                                    imageVector = volIcon,
                                    contentDescription = stringResource(R.string.player_volume),
                                    tint = if (isMuted) MaterialTheme.colorScheme.error else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Compact Volume slider
                            Slider(
                                value = if (isMuted) 0f else volume,
                                onValueChange = { newVol ->
                                    triggerInteraction()
                                    updateVolume(newVol)
                                },
                                modifier = Modifier.width(80.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
