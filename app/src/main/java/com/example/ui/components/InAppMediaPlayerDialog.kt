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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import kotlinx.coroutines.delay
import java.io.File

/**
 * In-App Media Player Dialog powered by AndroidX Media3 ExoPlayer.
 *
 * Guarantees:
 * 1. Play/Pause preserves current playback position (never resets to 00:00).
 * 2. Accurate video duration reading and display formatted (e.g. 00:25 / 01:00).
 * 3. Exact UI controls synchronization with player state.
 * 4. Smooth seeking, speed adjustment, volume control, and fullscreen switching.
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

    // Resolve media URI
    val mediaUri = remember(contentUri, mediaPath) {
        when {
            !contentUri.isNullOrBlank() -> Uri.parse(contentUri)
            !mediaPath.isNullOrBlank() -> {
                val f = File(mediaPath)
                if (f.exists()) Uri.fromFile(f) else null
            }
            else -> null
        }
    }

    // Try reading metadata duration immediately from file
    val metadataDuration = remember(contentUri, mediaPath) {
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

    // Release player on disposal
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

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
                    exoPlayer = exoPlayer,
                    initialDuration = metadataDuration,
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
                .fillMaxWidth(0.96f)
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
                        exoPlayer = exoPlayer,
                        initialDuration = metadataDuration,
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
    exoPlayer: ExoPlayer?,
    initialDuration: Long,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    // Player playback state observation
    var isPlaying by remember { mutableStateOf(exoPlayer?.isPlaying ?: false) }
    var playbackState by remember { mutableIntStateOf(exoPlayer?.playbackState ?: Player.STATE_IDLE) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(if (initialDuration > 0) initialDuration else 0L) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var errorMessage by remember { mutableStateOf<String?>(if (exoPlayer == null) "Video source could not be resolved" else null) }

    // User interaction / controls
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

    // Register Player.Listener for real-time synchronization
    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) {
                    val playerDur = exoPlayer.duration
                    if (playerDur != C.TIME_UNSET && playerDur > 0) {
                        duration = playerDur
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                val playerDur = player.duration
                if (playerDur != C.TIME_UNSET && playerDur > 0) {
                    duration = playerDur
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "Playback error"
            }
        }

        exoPlayer.addListener(listener)

        // Seed initial values
        isPlaying = exoPlayer.isPlaying
        playbackState = exoPlayer.playbackState
        val initialDur = exoPlayer.duration
        if (initialDur != C.TIME_UNSET && initialDur > 0) {
            duration = initialDur
        }

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Auto-hide controls timer (after 3.5s of inactivity when playing)
    LaunchedEffect(areControlsVisible, isPlaying, lastUserInteractionTime) {
        if (areControlsVisible && isPlaying) {
            delay(3500)
            areControlsVisible = false
        }
    }

    // High-precision timeline polling
    LaunchedEffect(exoPlayer, isPlaying, isSeeking) {
        while (exoPlayer != null) {
            if (!isSeeking) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentPosition = pos
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0 && dur != duration) {
                    duration = dur
                }
            }
            delay(200)
        }
    }

    fun triggerInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        areControlsVisible = true
    }

    fun seekRelative(offsetMs: Long) {
        triggerInteraction()
        exoPlayer?.let { player ->
            val cur = player.currentPosition
            val maxDur = if (duration > 0) duration else Long.MAX_VALUE
            val target = (cur + offsetMs).coerceIn(0L, maxDur)
            player.seekTo(target)
            currentPosition = target
            if (playbackState == Player.STATE_ENDED && offsetMs < 0) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // 1. ExoPlayer Video Surface View
        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = isFullscreen),
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

        // 4. Loading / Buffering Spinner
        val isBuffering = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && exoPlayer != null)
        if (isBuffering && errorMessage == null) {
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
                            onToggleFullscreen()
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
                        onClick = onClose,
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

                // Center Action Controls: Seek -10s, Play/Pause, Seek +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Rewind 10s
                    FilledTonalIconButton(
                        onClick = { seekRelative(-10000L) },
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("btn_player_rewind"),
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
                            exoPlayer?.let { player ->
                                if (playbackState == Player.STATE_ENDED) {
                                    player.seekTo(0)
                                    player.play()
                                } else if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    player.play()
                                }
                            }
                        },
                        modifier = Modifier
                            .size(68.dp)
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
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Fast Forward 10s
                    FilledTonalIconButton(
                        onClick = { seekRelative(10000L) },
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("btn_player_forward"),
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
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slider_player_timeline")
                    )

                    // Bottom info and sub-controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current Elapsed / Total Duration Text
                        val durationDisplay = if (duration > 0) formatDuration(duration) else "--:--"
                        Text(
                            text = "${formatDuration(effectivePosition)} / $durationDisplay",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("text_player_timestamps")
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

                            // Compact Volume slider
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

/**
 * Reads video duration immediately from container metadata if available on disk / content resolver.
 */
private fun extractMediaDuration(context: Context, contentUri: String?, mediaPath: String?): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        if (!contentUri.isNullOrBlank()) {
            val uri = Uri.parse(contentUri)
            retriever.setDataSource(context, uri)
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

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
