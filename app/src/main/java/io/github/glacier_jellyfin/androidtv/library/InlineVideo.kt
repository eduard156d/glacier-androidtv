package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.player.DecoderInfo
import io.github.glacier_jellyfin.androidtv.player.DecoderListener
import io.github.glacier_jellyfin.androidtv.player.startupTimeoutMs
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * What [InlineVideo] plays: [player] once the stream of [id] is open, and how far it has come, 0 to 1. For the info
 * panel: the server's [source] of the stream (how it is delivered, the file) as soon as it is known, the [playback] the
 * live values are read from, and the [decoders] Media3 picked.
 */
data class InlineVideoState(
    val id: UUID? = null,
    val player: ExoPlayer? = null,
    val progress: Float = 0f,
    val source: PlaybackSource? = null,
    val playback: GlacierPlayback? = null,
    val decoders: DecoderInfo = DecoderInfo(),
    val playing: Boolean = false,
    val failed: Boolean = false,
)

/**
 * One home video playing inside a screen rather than the full player: the photo viewer, the
 * gallery's preview. [onEnded] handles completion. [onFailed], when supplied, handles failures with
 * the item's id, the last usable position and, on a stall, the stalled source.
 * The full player reports playback to the server after the handover.
 */
@OptIn(UnstableApi::class)
class InlineVideo(
    private val context: Context,
    private val playback: PlaybackRepository,
    private val scope: CoroutineScope,
    private val onEnded: () -> Unit = {},
    private val onFailed: ((UUID, Long, PlaybackSource?) -> Unit)? = null,
    /** Whether a portrait video gets a blurred background (Settings), read when a video opens. */
    private val blurredBackground: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(InlineVideoState())
    val state: StateFlow<InlineVideoState> = _state.asStateFlow()

    private var opening: Job? = null
    private var ticker: Job? = null
    private var requestedPosition = 0L
    private var firstFrame = false
    private var playOnReady = true

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (_state.value.player !== player) return
            val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            _state.update { it.copy(playing = playing) }
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            if (onFailed != null && !firstFrame) {
                failPlayer("Video ended before showing a frame")
            } else {
                onEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Inline playback failed", error)
            failPlayer("Could not play the inline video")
        }
    }

    private val decoders = DecoderListener(
        onVideoDecoder = { name -> _state.update { it.copy(decoders = it.decoders.copy(video = name)) } },
        onAudioDecoder = { name -> _state.update { it.copy(decoders = it.decoders.copy(audio = name)) } },
        onAudioOutput = { output -> _state.update { it.copy(decoders = it.decoders.copy(output = output)) } },
    )

    /** The transcoder's state for the stream, for the info panel; null when it is not transcoding or not known. */
    suspend fun transcodeStatus(): TranscodeStatus? = _state.value.source?.let { playback.transcodeStatus(it) }

    /** Where it plays now, or the position to retry if the stream failed. */
    val positionMs: Long get() = _state.value.player?.currentPosition ?: requestedPosition

    /**
     * Plays [id] from [startMs], after [delayMs] in which another [open] or [release] can still call it
     * off (a gallery row the focus only passes through).
     */
    fun open(
        id: UUID,
        startMs: Long = 0,
        delayMs: Long = 0,
        recovering: Boolean = false,
    ) {
        if (_state.value.id == id && (_state.value.player != null || opening?.isActive == true)) return
        release()
        requestedPosition = startMs
        _state.update { it.copy(id = id, playing = playOnReady) }
        opening = scope.launch {
            try {
                delay(delayMs)
                val source = playback.open(id, startMs = startMs, audioIndex = null, subtitleIndex = null)
                _state.update { it.copy(source = source) }
                val created = GlacierPlayer.create(
                    context,
                    StreamRequest(
                        url = source.url,
                        isHls = source.isHls,
                        headers = source.headers,
                        startPositionMs = startMs,
                        playlist = source.ladder.takeIf { recovering },
                        startBitrate = source.streamBitrate?.takeIf { recovering }?.let { (it * 110L / 100).toInt() },
                        patient = recovering && source.isHls,
                        snapStart = startMs > 0,
                        videoRotation = source.file?.video?.rotation,
                        videoWidth = source.file?.video?.width,
                        videoHeight = source.file?.video?.height,
                        blurredBackground = wantsBlurredBackdrop(source.file?.video, blurredBackground()),
                    ),
                )
                val player = created.player
                player.addListener(listener)
                player.addAnalyticsListener(decoders)
                player.playWhenReady = playOnReady
                _state.update { it.copy(player = player, playback = created, progress = 0f, playing = playOnReady) }
                startTicker(player, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the video failed", e)
                fail(id, startMs)
            }
        }
    }

    fun togglePlay() {
        val player = _state.value.player
        if (player == null) {
            if (opening?.isActive == true) {
                playOnReady = !playOnReady
                _state.update { it.copy(playing = playOnReady) }
            }
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
            player.play()
        } else {
            player.playWhenReady = !player.playWhenReady
        }
    }

    fun pause() {
        playOnReady = false
        _state.value.player?.playWhenReady = false
        _state.update { it.copy(playing = false) }
    }

    fun release() {
        opening?.cancel()
        ticker?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.value = InlineVideoState()
        requestedPosition = 0L
        firstFrame = false
        playOnReady = true
    }

    private fun startTicker(player: ExoPlayer, source: PlaybackSource) {
        ticker = scope.launch {
            val timeout = startupTimeoutMs(source.method)
            var lastMovement = SystemClock.elapsedRealtime()
            var lastPosition = player.currentPosition
            var lastFrameCount: Int? = null
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val position = player.currentPosition
                val frames = player.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    it.renderedOutputBufferCount
                }
                val expectedToPlay = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                val pictureAdvanced = frames?.let { it != lastFrameCount } ?: (firstFrame && position != lastPosition)
                if (!expectedToPlay || pictureAdvanced) lastMovement = now
                if (onFailed != null && now - lastMovement >= timeout) {
                    val where = if (firstFrame) position else requestedPosition
                    Log.w(TAG, "No inline video progress for ${timeout / 1000} s (rendered frames: $frames)")
                    fail(source.itemId, where, stalled = source)
                    return@launch
                }
                val duration = player.duration
                if (duration > 0) {
                    _state.update { it.copy(progress = (position.toFloat() / duration).coerceIn(0f, 1f)) }
                }
                lastFrameCount = frames
                lastPosition = position
                delay(TICK_MS)
            }
        }
    }

    private fun failPlayer(message: String) {
        val current = _state.value
        val player = current.player ?: return
        val id = current.id ?: return
        val position = if (firstFrame) player.currentPosition else requestedPosition
        // Releasing or reopening a player belongs outside Media3's callbacks.
        scope.launch {
            yield()
            if (_state.value.player !== player) return@launch
            Log.w(TAG, message)
            fail(id, position)
        }
    }

    private fun fail(
        id: UUID,
        position: Long,
        stalled: PlaybackSource? = null,
    ) {
        release()
        requestedPosition = position
        _state.value = InlineVideoState(id = id, failed = true)
        onFailed?.invoke(id, position, stalled) ?: onEnded()
    }

    private companion object {
        const val TAG = "InlineVideo"
        const val TICK_MS = 250L
    }
}
