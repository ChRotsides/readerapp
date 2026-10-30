package com.readerapp.tts.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.TextVariant
import com.readerapp.tts.SleepSpec
import com.readerapp.tts.SpeechRef
import com.readerapp.tts.TtsCommand
import com.readerapp.tts.TtsController
import com.readerapp.tts.TtsPrefs
import com.readerapp.tts.TtsSettings
import com.readerapp.tts.TtsState
import com.readerapp.tts.model.ModelManager
import com.readerapp.tts.model.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class PendingStart(val from: SpeechRef, val variant: TextVariant?)

@HiltViewModel
class TtsViewModel @Inject constructor(
    private val controller: TtsController,
    private val models: ModelManager,
    private val settings: TtsSettings,
) : ViewModel() {
    val state: StateFlow<TtsState> = controller.state
    val modelState: StateFlow<ModelState> = models.state
    val messages: SharedFlow<String> = controller.messages
    val prefs: StateFlow<TtsPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsPrefs())

    private var pending: PendingStart? = null
    private val _confirmDownload = MutableStateFlow(false)

    /** True while the "download the speech model?" dialog should be shown. */
    val confirmDownload: StateFlow<Boolean> = _confirmDownload.asStateFlow()

    init {
        // A start requested before the model existed is carried out as soon as the install finishes.
        viewModelScope.launch {
            models.state.collect { s ->
                if (s is ModelState.Installed) pending?.let { p -> pending = null; controller.send(TtsCommand.Start(p.from, p.variant)) }
            }
        }
    }

    /** Play button: toggles if this chapter is being read, otherwise starts reading it at [paragraph]. */
    fun onPlayButton(chapterId: Long, variant: TextVariant, paragraph: Int) {
        val s = state.value
        if (s.active && s.ref?.chapterId == chapterId) send(TtsCommand.Toggle) else readFrom(chapterId, variant, paragraph)
    }

    fun readFrom(chapterId: Long, variant: TextVariant, paragraph: Int) {
        val from = SpeechRef(chapterId, paragraph.coerceAtLeast(0), 0)
        if (models.installer.isInstalled()) {
            controller.send(TtsCommand.Start(from, variant))
        } else {
            pending = PendingStart(from, variant)
            val s = modelState.value
            if (s is ModelState.Downloading || s == ModelState.Verifying || s == ModelState.Extracting) return
            _confirmDownload.value = true
        }
    }

    fun confirmDownload() {
        _confirmDownload.value = false
        models.startDownload()
    }

    fun dismissDownload() {
        _confirmDownload.value = false
        pending = null
    }

    fun startDownload() = models.startDownload()

    fun cancelDownload() {
        pending = null
        models.cancelDownload()
    }

    fun deleteModel() {
        send(TtsCommand.Stop)
        models.installer.delete()
    }

    fun send(command: TtsCommand) = controller.send(command)

    fun setSpeed(speed: Float) {
        if (state.value.active) send(TtsCommand.SetSpeed(speed)) else viewModelScope.launch { settings.setSpeed(speed) }
    }

    fun setVoice(voice: Int) {
        if (state.value.active) send(TtsCommand.SetVoice(voice)) else viewModelScope.launch { settings.setVoice(voice) }
    }

    fun setSleep(spec: SleepSpec?) = send(TtsCommand.Sleep(spec))

    fun setNumThreads(n: Int) = viewModelScope.launch { settings.setNumThreads(n) }

    fun setCacheLimitMb(mb: Int) = viewModelScope.launch { settings.setCacheLimitMb(mb) }
}
