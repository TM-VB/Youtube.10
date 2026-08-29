package com.example.ui.components

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

/**
 * Modern In-App Media Player Dialog powered by AndroidX Media3 ExoPlayer.
 *
 * Guarantees:
 * 1. Play/Pause preserves current playback position (resumes from 00:25 -> 00:26).
 * 2. Accurate video duration reading (displays 00:25 / 01:00, never '-:--').
 * 3. Exact UI controls synchronization with real-time player states.
 * 4. Fast forward (+10s), rewind (-10s), double-tap seek, speed control, loop, and fullscreen.
 */
@Composable
fun InAppMediaPlayerDialog(
    title: String,
    mediaPath: String?,
    contentUri: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    // Resolve media URI safely from ContentResolver or File
    val mediaUri = remember(contentUri, mediaPath) {
        when {
            !contentUri.isNullOrBlank() -> Uri.parse(contentUri)
            !mediaPath.isNullOrBlank() -> {
                if (mediaPath.startsWith("content://") || mediaPath.startsWith("file://")) {
                    Uri.parse(mediaPath)
                } else {
                    val f = File(mediaPath)
                    if (f.exists()) Uri.fromFile(f) else Uri.parse(mediaPath)
                }
            }
            else -> null
        }
    }

    // Try reading metadata duration immediately from container header
    val initialMetadataDuration = remember(contentUri, mediaPath) {
        extractMediaDuration(context, contentUri, mediaPath)
    }

    // Persistent single ExoPlayer instance for this dialog
    val exoPlayer = remember(mediaUri) {
        if (mediaUri != null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, true)
                .build().apply {
                    setMediaItem(MediaItem.fromUri(mediaUri))
                    prepare()
                    playWhenReady = true
                }
        } else null
    }

    // Release player strictly on Dialog disposal
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    // High-level playback state observables
    var isPlaying by remember { mutableStateOf(exoPlayer?.isPlaying ?: false) }
    var playbackState by remember { mutableIntStateOf(exoPlayer?.playbackState ?: Player.STATE_IDLE) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(if (initialMetadataDuration > 0) initialMetadataDuration else 0L) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var errorMessage by remember { mutableStateOf<String?>(if (exoPlayer == null) "Could not load video source" else null) }

    // Interactive UI controls state
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewPosition by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var lastUserInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var doubleTapSeekFeedback by remember { mutableStateOf<String?>(null) }

    // Register ExoPlayer Listener for comprehensive real-time synchronization
    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0) {
                    duration = dur
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0) {
                    duration = dur
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0) {
                    duration = dur
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    videoAspectRatio = ratio.coerceIn(0.4f, 2.8f)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "Playback error occurred"
            }
        }

        exoPlayer.addListener(listener)

        // Seed current player state immediately
        isPlaying = exoPlayer.isPlaying
        playbackState = exoPlayer.playbackState
        val dur = exoPlayer.duration
        if (dur != C.TIME_UNSET && dur > 0) {
            duration = dur
        }

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // High-precision timeline & duration polling loop (100ms interval)
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            exoPlayer?.let { player ->
                if (!isSeeking) {
                    currentPosition = player.currentPosition.coerceAtLeast(0L)
                }
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0 && dur != duration) {
                    duration = dur
                }
                isPlaying = player.isPlaying
                playbackState = player.playbackState
            }
            delay(100)
        }
    }

    // Auto-hide HUD controls after 3.5 seconds of user inactivity when playing
    LaunchedEffect(areControlsVisible, isPlaying, lastUserInteractionTime) {
        if (areControlsVisible && isPlaying) {
            delay(3500)
            areControlsVisible = false
        }
    }

    // Helper functions for user actions
    fun triggerInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        areControlsVisible = true
    }

    fun togglePlayPause() {
        triggerInteraction()
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
                player.play()
            } else if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun seekRelative(offsetMs: Long) {
        triggerInteraction()
        exoPlayer?.let { player ->
            val cur = player.currentPosition
            val maxDur = if (duration > 0) duration else Long.MAX_VALUE
            val target = (cur + offsetMs).coerceIn(0L, maxDur)
            player.seekTo(target)
            currentPosition = target
            if (player.playbackState == Player.STATE_ENDED && offsetMs < 0) {
                player.play()
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun updateVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        isMuted = newVol <= 0f
        exoPlayer?.volume = if (isMuted) 0f else volume
    }

    fun toggleMute() {
        isMuted = !isMuted
        exoPlayer?.volume = if (isMuted) 0f else volume
    }

    val displayTitle = title.ifBlank { stringResource(R.string.media_player_title) }

    // Single unified Dialog container for seamless fullscreen and windowed playback
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = !isFullscreen
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isFullscreen) Modifier.background(Color.Black)
                    else Modifier
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Player Container (Card in windowed mode, Fullscreen in expanded mode)
            val containerModifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            }

            Box(
                modifier = containerModifier,
                contentAlignment = Alignment.Center
            ) {
                // 1. Video Surface View (ExoPlayer PlayerView)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isFullscreen) Modifier.fillMaxSize()
                            else Modifier.aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = false)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (exoPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                }
                            },
                            update = { playerView ->
                                if (playerView.player != exoPlayer) {
                                    playerView.player = exoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 2. Gesture Detector for Tap & Double-Tap Seeks
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

                // 3. Double-tap Seek Feedback Popup
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

                // 4. Buffering / Loading Indicator
                val isBuffering = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && exoPlayer != null)
                if (isBuffering && errorMessage == null) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                // 5. Error Overlay
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
                            TextButton(onClick = onDismiss) {
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
                        // Top Bar: Title, Speed, Loop, Fullscreen & Close
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

                            // Speed Selector
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

                            // Repeat / Loop Toggle
                            IconButton(onClick = {
                                triggerInteraction()
                                isLooping = !isLooping
                                exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            }) {
                                Icon(
                                    imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = stringResource(R.string.player_loop),
                                    tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Fullscreen Toggle Button
                            IconButton(
                                onClick = {
                                    triggerInteraction()
                                    isFullscreen = !isFullscreen
                                },
                                modifier = Modifier.testTag("btn_player_fullscreen")
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isFullscreen) stringResource(R.string.player_exit_fullscreen) else stringResource(R.string.player_fullscreen),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Close Button
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("btn_player_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.btn_close),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Center Controls: Rewind 10s, Play/Pause/Replay, Fast-Forward 10s
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Rewind 10s
                            FilledTonalIconButton(
                                onClick = { seekRelative(-10000L) },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("btn_player_rewind"),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = stringResource(R.string.player_seek_backward),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Main Play / Pause Button
                            FilledIconButton(
                                onClick = { togglePlayPause() },
                                modifier = Modifier
                                    .size(72.dp)
                                    .testTag("btn_player_play_pause"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = when {
                                        playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                                        isPlaying -> Icons.Default.Pause
                                        else -> Icons.Default.PlayArrow
                                    },
                                    contentDescription = when {
                                        playbackState == Player.STATE_ENDED -> "Replay"
                                        isPlaying -> "Pause"
                                        else -> "Play"
                                    },
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            // Fast Forward 10s
                            FilledTonalIconButton(
                                onClick = { seekRelative(10000L) },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("btn_player_forward"),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = stringResource(R.string.player_seek_forward),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Bottom Controls: Timeline Slider, Timestamps & Volume
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Timeline Progress Slider
                            val effectivePosition = if (isSeeking) {
                                (seekPreviewPosition * (if (duration > 0) duration else 1L)).toLong()
                            } else {
                                currentPosition
                            }

                            val progressFraction = if (duration > 0) {
                                (effectivePosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                            Slider(
                                value = progressFraction,
                                onValueChange = { frac ->
                                    triggerInteraction()
                                    isSeeking = true
                                    seekPreviewPosition = frac
                                },
                                onValueChangeFinished = {
                                    if (duration > 0) {
                                        val targetMs = (seekPreviewPosition * duration).toLong().coerceIn(0L, duration)
                                        exoPlayer?.seekTo(targetMs)
                                        currentPosition = targetMs
                                    }
                                    isSeeking = false
                                    triggerInteraction()
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("slider_player_timeline")
                            )

                            // Timestamp and Volume row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Current Elapsed / Total Duration Text
                                val currentFormatted = formatDuration(effectivePosition)
                                val durationFormatted = if (duration > 0) formatDuration(duration) else formatDuration(0)
                                Text(
                                    text = "$currentFormatted / $durationFormatted",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("text_player_timestamps")
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                // Quick Volume Slider & Mute Toggle
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            triggerInteraction()
                                            toggleMute()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("btn_player_mute")
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

                                    Slider(
                                        value = if (isMuted) 0f else volume,
                                        onValueChange = { newVol ->
                                            triggerInteraction()
                                            updateVolume(newVol)
                                        },
                                        modifier = Modifier
                                            .width(80.dp)
                                            .testTag("slider_player_volume"),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extracts accurate media duration from video file header using MediaMetadataRetriever and FileDescriptors.
 */
private fun extractMediaDuration(context: Context, contentUri: String?, mediaPath: String?): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        if (!contentUri.isNullOrBlank()) {
            val uri = Uri.parse(contentUri)
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: run {
                    retriever.setDataSource(context, uri)
                }
            } catch (_: Throwable) {
                retriever.setDataSource(context, uri)
            }
        } else if (!mediaPath.isNullOrBlank()) {
            val file = File(mediaPath)
            if (file.exists()) {
                retriever.setDataSource(file.absolutePath)
            } else {
                return 0L
            }
        } else {
            return 0L
        }
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        durationStr?.toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    } finally {
        try {
            retriever.release()
        } catch (_: Throwable) {}
    }
}

/**
 * Formats milliseconds into clean string representations:
 * - 00:25 (25 seconds)
 * - 01:00 (1 minute)
 * - 10:35 (10 minutes 35 seconds)
 * - 1:25:42 (1 hour 25 minutes 42 seconds)
 */
private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
