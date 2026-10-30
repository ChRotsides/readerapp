package com.readerapp.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.readerapp.data.db.TextVariant
import com.readerapp.tts.model.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SleepSpec {
    data class Minutes(val minutes: Int) : SleepSpec

    data object EndOfChapter : SleepSpec
}

sealed interface TtsCommand {
    data class Start(val from: SpeechRef, val variant: TextVariant?) : TtsCommand
    data object Play : TtsCommand
    data object Pause : TtsCommand
    data object Toggle : TtsCommand
    data object NextSentence : TtsCommand
    data object PrevSentence : TtsCommand
    data object NextParagraph : TtsCommand
    data object PrevParagraph : TtsCommand
    data object NextChapter : TtsCommand
    data object Stop : TtsCommand
    data class SetSpeed(val speed: Float) : TtsCommand
    data class SetVoice(val voice: Int) : TtsCommand

    /** null cancels the timer. */
    data class Sleep(val spec: SleepSpec?) : TtsCommand
}

/** What the UI shows about reading aloud. Updated by [TtsService]. */
data class TtsState(
    val active: Boolean = false,
    /** The user wants audio (play, not pause). */
    val playing: Boolean = false,
    /** Waiting for the next sentence to be generated. */
    val buffering: Boolean = false,
    val ref: SpeechRef? = null,
    val variant: String? = null,
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val sentenceText: String = "",
    val voice: Int = TtsConfig.DEFAULT_VOICE,
    val speed: Float = TtsConfig.DEFAULT_SPEED,
    val sleepEndsAtMs: Long? = null,
    val sleepEndOfChapter: Boolean = false,
)

/**
 * App-wide handle on reading aloud. The UI sends [TtsCommand]s and observes [state]; the foreground [TtsService]
 * executes the commands and publishes state. The service is started on demand (and stops itself when done).
 */
@Singleton
class TtsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) {
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off user-facing messages (not English, chapter locked, ...). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    internal val commands = Channel<TtsCommand>(Channel.UNLIMITED)

    val modelInstalled: Boolean get() = models.installer.isInstalled()

    fun send(command: TtsCommand) {
        if (command is TtsCommand.Start) {
            if (!modelInstalled) {
                _messages.tryEmit("The speech model isn't installed yet.")
                return
            }
            commands.trySend(command)
            ContextCompat.startForegroundService(context, Intent(context, TtsService::class.java))
        } else if (_state.value.active) {
            commands.trySend(command)
        }
    }

    internal fun update(transform: (TtsState) -> TtsState) {
        _state.value = transform(_state.value)
    }

    internal fun message(text: String) {
        _messages.tryEmit(text)
    }

    internal fun reset() {
        _state.value = TtsState(voice = _state.value.voice, speed = _state.value.speed)
    }
}
