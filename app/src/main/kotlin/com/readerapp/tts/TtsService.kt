package com.readerapp.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.readerapp.MainActivity
import com.readerapp.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Foreground media service: owns the ExoPlayer, the media session (lock screen / notification / headset buttons)
 * and the [SpeechPipeline] that fills the player's playlist with one MediaItem per generated sentence.
 *
 * All player access happens on the main thread. Generation runs on Dispatchers.Default + the engine's own thread, so
 * it continues while the screen is off (the foreground service and a partial wake lock keep the process running).
 */
@AndroidEntryPoint
class TtsService : MediaSessionService() {
    @Inject lateinit var controller: TtsController
    @Inject lateinit var engines: EngineProvider
    @Inject lateinit var source: ChapterSpeechSource
    @Inject lateinit var settings: TtsSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var pipeline: SpeechPipeline
    private lateinit var cache: AudioCache
    private var wakeLock: PowerManager.WakeLock? = null

    /** Playlist mirror: items[i] is the player's media item i. */
    private val window = PlaylistWindow(KEEP_BEHIND)
    private val items: ArrayList<ReadyItem> get() = window.items
    private var starved = false
    private var voice = TtsConfig.DEFAULT_VOICE
    private var speed = TtsConfig.DEFAULT_SPEED
    private var sleepJob: Job? = null
    /** Set while we stop the player ourselves, so the IDLE it reports is not mistaken for an external stop. */
    private var stopping = false

    private val sink = object : PlaylistSink {
        override suspend fun append(item: ReadyItem) = withContext(Dispatchers.Main.immediate) {
            window.append(item)
            cache.pin(item.file.name)
            player.addMediaItem(toMediaItem(item))
            val idx = items.lastIndex
            if (idx == 0) {
                starved = false
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                else if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            } else if (starved) {
                // Playback had caught up with generation (STATE_ENDED): continue with the new sentence.
                starved = false
                player.seekTo(idx, 0)
            }
            if (!starved) controller.update { it.copy(buffering = false) }
        }

        override suspend fun clear() = withContext(Dispatchers.Main.immediate) {
            releaseWindow()
            player.clearMediaItems()
            starved = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        cache = AudioCache(File(cacheDir, "tts"), TtsConfig.DEFAULT_CACHE_MB * MB)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setMediaButtonPreferences(ImmutableList.of(nextChapterButton()))
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.tts_channel_name)
                .build(),
        )
        // Register the session with the service explicitly: no MediaController connects to it, so without this the
        // notification manager would never observe it (stuck "preparing" notification, wrong task-removal handling).
        addSession(checkNotNull(session))
        pipeline = SpeechPipeline(workScope, source, { engines.get() }, cache, sink)
        scope.launch { for (cmd in controller.commands) handle(cmd) }
        scope.launch {
            pipeline.events.collect { e ->
                when (e) {
                    is PipelineEvent.StartFailed -> {
                        controller.message(e.reason)
                        stopReading()
                    }
                    is PipelineEvent.Finished -> {
                        e.reason?.let { controller.message(it) }
                        // Let the queued audio finish; the player's ENDED event then stops the service.
                        if (items.isEmpty() || player.playbackState == Player.STATE_ENDED) stopReading()
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must be answered quickly; the media notification replaces this once audio plays.
        val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Preparing to read aloud...")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        super.onStartCommand(intent, flags, startId)
        // A null intent is the system restarting us after process death: there is no reading state or queued
        // command any more, so do not linger as an idle foreground service.
        if (intent == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        pipeline.stop()
        workScope.cancel()
        releaseWakeLock()
        session?.release()
        session = null
        player.release()
        controller.reset()
        runBlocking(Dispatchers.Default) { engines.release() }
        super.onDestroy()
    }

    // ---- commands -------------------------------------------------------------------------------------------

    private suspend fun handle(cmd: TtsCommand) {
        when (cmd) {
            is TtsCommand.Start -> start(cmd)
            TtsCommand.Play -> player.play()
            TtsCommand.Pause -> player.pause()
            TtsCommand.Toggle -> if (player.playWhenReady) player.pause() else player.play()
            TtsCommand.NextSentence -> if (player.currentMediaItemIndex + 1 < items.size) player.seekToNextMediaItem()
            TtsCommand.PrevSentence -> prevSentence()
            TtsCommand.NextParagraph -> skipParagraph(forward = true)
            TtsCommand.PrevParagraph -> skipParagraph(forward = false)
            TtsCommand.NextChapter -> nextChapter()
            TtsCommand.Stop -> stopReading()
            is TtsCommand.SetSpeed -> {
                settings.setSpeed(cmd.speed)
                speed = cmd.speed
                controller.update { it.copy(speed = cmd.speed) }
                restartHere()
            }
            is TtsCommand.SetVoice -> {
                settings.setVoice(cmd.voice)
                voice = cmd.voice
                controller.update { it.copy(voice = cmd.voice) }
                restartHere()
            }
            is TtsCommand.Sleep -> setSleep(cmd.spec)
        }
    }

    private suspend fun start(cmd: TtsCommand.Start) {
        val prefs = settings.current()
        voice = prefs.voice
        speed = prefs.speed
        cache.maxBytes = prefs.cacheLimitMb * MB
        source.selected = cmd.variant
        controller.update {
            it.copy(active = true, playing = true, buffering = true, ref = cmd.from, voice = voice, speed = speed, sentenceText = "")
        }
        player.playWhenReady = true
        acquireWakeLock()
        pipeline.start(cmd.from, voice, speed)
    }

    /** Restarts generation at the sentence being played (after a voice / speed change). */
    private fun restartHere() {
        val ref = currentItem()?.ref ?: controller.state.value.ref ?: return
        controller.update { it.copy(buffering = true) }
        pipeline.start(ref, voice, speed)
    }

    private fun skipParagraph(forward: Boolean) {
        val idx = player.currentMediaItemIndex
        val cur = items.getOrNull(idx)?.ref ?: return
        fun same(i: Int) = items[i].ref.chapterId == cur.chapterId && items[i].ref.paragraph == cur.paragraph
        if (forward) {
            val next = (idx + 1 until items.size).firstOrNull { !same(it) }
            if (next != null) player.seekTo(next, 0)
            else pipeline.start(SpeechRef(cur.chapterId, cur.paragraph + 1, 0), voice, speed).also { markBuffering() }
        } else {
            var first = idx
            while (first > 0 && same(first - 1)) first--
            if (idx > first) { player.seekTo(first, 0); return }
            if (first == 0 && window.hasTrimmedHead) {
                // The window no longer holds what precedes this item: regenerate from the paragraph start (or the
                // paragraph before it when we are already at its start).
                val target = if (cur.sentence > 0) cur.paragraph else cur.paragraph - 1
                if (target >= 0) {
                    pipeline.start(SpeechRef(cur.chapterId, target, 0), voice, speed).also { markBuffering() }
                } else player.seekTo(0, 0)
                return
            }
            if (first > 0) {
                var prev = first - 1
                while (prev > 0 && items[prev - 1].ref.chapterId == items[prev].ref.chapterId &&
                    items[prev - 1].ref.paragraph == items[prev].ref.paragraph) prev--
                player.seekTo(prev, 0)
            } else if (cur.paragraph > 0) {
                pipeline.start(SpeechRef(cur.chapterId, cur.paragraph - 1, 0), voice, speed).also { markBuffering() }
            } else player.seekTo(0, 0)
        }
    }

    private fun prevSentence() {
        val idx = player.currentMediaItemIndex
        if (idx > 0) { player.seekTo(idx - 1, 0); return }
        val cur = items.firstOrNull()?.ref
        if (window.hasTrimmedHead && cur != null) {
            val target = if (cur.sentence > 0) cur.paragraph else cur.paragraph - 1
            if (target >= 0) pipeline.start(SpeechRef(cur.chapterId, target, 0), voice, speed).also { markBuffering() }
        } else player.seekTo(0, 0)
    }

    /** Unpins and forgets every playlist item (the player's own list is cleared by the caller). */
    private fun releaseWindow() {
        items.forEach { cache.unpin(it.file.name) }
        window.clear()
        player.clearMediaItems()
        starved = false
    }

    /** Drops played items far behind the playhead so the playlist (and the files it pins) stays bounded. */
    private fun trimBehind() {
        val n = window.excess(player.currentMediaItemIndex)
        if (n <= 0) return
        player.removeMediaItems(0, n)
        window.drop(n).forEach { cache.unpin(it.file.name) }
    }

    private fun markBuffering() = controller.update { it.copy(buffering = true) }

    private fun nextChapter() {
        val cur = currentItem()?.ref ?: controller.state.value.ref ?: return
        scope.launch {
            val next = withContext(Dispatchers.Default) { source.nextChapterId(cur.chapterId) }
            if (next == null) controller.message("This is the last known chapter.") else {
                player.playWhenReady = true
                markBuffering()
                pipeline.start(SpeechRef(next, 0, 0), voice, speed)
            }
        }
    }

    private fun stopReading() {
        sleepJob?.cancel()
        pipeline.stop()
        stopping = true
        player.stop()
        stopping = false
        releaseWindow()
        controller.reset()
        releaseWakeLock()
        stopSelf()
    }

    // ---- sleep timer ----------------------------------------------------------------------------------------

    private fun setSleep(spec: SleepSpec?) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = false
        when (spec) {
            null -> controller.update { it.copy(sleepEndsAtMs = null, sleepEndOfChapter = false) }
            is SleepSpec.Minutes -> {
                val endsAt = System.currentTimeMillis() + spec.minutes * 60_000L
                controller.update { it.copy(sleepEndsAtMs = endsAt, sleepEndOfChapter = false) }
                sleepJob = scope.launch {
                    delay(spec.minutes * 60_000L)
                    player.pause()
                    controller.update { it.copy(sleepEndsAtMs = null) }
                }
            }
            SleepSpec.EndOfChapter -> {
                controller.update { it.copy(sleepEndsAtMs = null, sleepEndOfChapter = true) }
                player.pauseAtEndOfMediaItems = currentItem()?.lastInChapter == true
            }
        }
    }

    // ---- player / session -----------------------------------------------------------------------------------

    private fun currentItem(): ReadyItem? = items.getOrNull(player.currentMediaItemIndex)

    private fun toMediaItem(item: ReadyItem): MediaItem = MediaItem.Builder()
        .setMediaId("${item.ref.chapterId}:${item.ref.paragraph}:${item.ref.sentence}")
        .setUri(Uri.fromFile(item.file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.chapterTitle)
                .setArtist(item.novelTitle)
                .setExtras(Bundle().apply {
                    putLong("chapterId", item.ref.chapterId)
                    putInt("paragraph", item.ref.paragraph)
                    putInt("sentence", item.ref.sentence)
                })
                .build(),
        )
        .build()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val idx = player.currentMediaItemIndex
            val item = items.getOrNull(idx) ?: return
            trimBehind()
            pipeline.playhead(window.absolute(player.currentMediaItemIndex))
            controller.update {
                it.copy(
                    ref = item.ref, variant = item.variant, novelTitle = item.novelTitle,
                    chapterTitle = item.chapterTitle, sentenceText = item.text, buffering = false,
                )
            }
            if (controller.state.value.sleepEndOfChapter) player.pauseAtEndOfMediaItems = item.lastInChapter
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            controller.update { it.copy(playing = playWhenReady) }
            if (playWhenReady) acquireWakeLock()
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                // The "end of chapter" sleep timer fired.
                player.pauseAtEndOfMediaItems = false
                controller.update { it.copy(sleepEndOfChapter = false) }
            }
            if (!playWhenReady) releaseWakeLockLater()
        }

        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "player state=$state idx=${player.currentMediaItemIndex} items=${items.size} done=${pipeline.done.value}")
            if (state == Player.STATE_IDLE && !stopping && controller.state.value.active && items.isNotEmpty()) {
                // Something outside the app stopped the player (e.g. Samsung drops the media notification when the
                // screen dozes, and its dismiss action is a STOP media button). Reading is still on: re-prepare,
                // otherwise generation keeps queueing audio that never plays.
                Log.w(TAG, "player stopped externally; re-preparing")
                player.prepare()
                return
            }
            if (state != Player.STATE_ENDED) return
            val next = player.currentMediaItemIndex + 1
            when {
                next < items.size -> { player.seekTo(next, 0); player.prepare() }
                pipeline.done.value -> stopReading()
                else -> {
                    // Playback caught up with generation: pause-buffer until the next sentence arrives.
                    starved = true
                    controller.update { it.copy(buffering = true) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            controller.message("Playback error: ${error.errorCodeName}")
            stopReading()
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controllerInfo: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_NEXT_CHAPTER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            if (playerCommand != Player.COMMAND_STOP) return super.onPlayerCommandRequest(session, controller, playerCommand)
            return rejectStop("command from ${controller.packageName}")
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key?.keyCode != KeyEvent.KEYCODE_MEDIA_STOP) return super.onMediaButtonEvent(session, controllerInfo, intent)
            if (key.action == KeyEvent.ACTION_DOWN) rejectStop("media button from ${controllerInfo.packageName}")
            return true
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_NEXT_CHAPTER) nextChapter()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * External STOP requests (the media notification's dismiss action, Bluetooth/watch stop buttons) never put the
     * player into IDLE behind our back. While reading they are ignored: the system sends one when it drops the
     * notification on screen doze. While paused they end the reading session properly.
     */
    private fun rejectStop(source: String): Int {
        Log.i(TAG, "external stop ($source), playWhenReady=${player.playWhenReady}")
        if (!player.playWhenReady) scope.launch { stopReading() }
        return SessionResult.RESULT_ERROR_NOT_SUPPORTED
    }

    private fun nextChapterButton(): CommandButton =
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Next chapter")
            .setSessionCommand(SessionCommand(ACTION_NEXT_CHAPTER, Bundle.EMPTY))
            .build()

    // ---- wake lock / channel --------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "readerapp:tts").apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private var wakeLockRelease: Job? = null

    /** Keep the CPU awake briefly after pausing so lookahead generation can finish, then let it sleep. */
    private fun releaseWakeLockLater() {
        wakeLockRelease?.cancel()
        wakeLockRelease = scope.launch {
            delay(60_000)
            if (!player.playWhenReady) releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.tts_channel_name), NotificationManager.IMPORTANCE_LOW))
    }

    private companion object {
        const val TAG = "ReaderTts"
        const val CHANNEL_ID = "tts_playback"
        const val NOTIFICATION_ID = 4100
        const val ACTION_NEXT_CHAPTER = "readerapp.tts.NEXT_CHAPTER"
        const val MB = 1024L * 1024L
        /** Sentences kept behind the playhead for previous-sentence / previous-paragraph navigation. */
        const val KEEP_BEHIND = 40
        const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
