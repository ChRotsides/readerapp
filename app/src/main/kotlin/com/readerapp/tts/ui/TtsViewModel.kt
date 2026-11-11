package com.readerapp.tts.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.TextVariant
import com.readerapp.tts.SleepSpec
import com.readerapp.tts.SpeechRef
import com.readerapp.tts.TtsCommand
import com.readerapp.tts.TtsController
import com.readerapp.tts.TtsModel
import com.readerapp.tts.TtsModels
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

private data class PendingStart(val from: SpeechRef, val variant: TextVariant?, val modelId: String)

@HiltViewModel
class TtsViewModel @Inject constructor(
    private val controller: TtsController,
    private val models: ModelManager,
    private val settings: TtsSettings,
) : ViewModel() {
    val state: StateFlow<TtsState> = controller.state

    /** Install state of every model, keyed by model id. */
    val modelStates: StateFlow<Map<String, ModelState>> = models.states
    val messages: SharedFlow<String> = controller.messages
    val prefs: StateFlow<TtsPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsPrefs())

    private var pending: PendingStart? = null
    private val _confirmDownload = MutableStateFlow<TtsModel?>(null)

    /** The model the "download the speech model?" dialog is about, or null when it should not be shown. */
    val confirmDownload: StateFlow<TtsModel?> = _confirmDownload.asStateFlow()

    init {
        // A start requested before the model existed is carried out as soon as the install finishes.
        viewModelScope.launch {
            models.states.collect { all ->
                val p = pending ?: return@collect
                if (all[p.modelId] is ModelState.Installed) {
                    pending = null
                    controller.send(TtsCommand.Start(p.from, p.variant))
                }
            }
        }
    }

    /** Play button: toggles if this chapter is being read, otherwise starts reading it at [paragraph]. */
    fun onPlayButton(chapterId: Long, variant: TextVariant, paragraph: Int) {
        val s = state.value
        if (isSamePlayback(s, chapterId, variant)) send(TtsCommand.Toggle) else readFrom(chapterId, variant, paragraph)
    }

    fun readFrom(chapterId: Long, variant: TextVariant, paragraph: Int) {
        val from = SpeechRef(chapterId, paragraph.coerceAtLeast(0), 0)
        viewModelScope.launch {
            val model = TtsModels.byId(settings.current().modelId)
            if (models.installer(model.id).isInstalled()) {
                controller.send(TtsCommand.Start(from, variant))
            } else {
                pending = PendingStart(from, variant, model.id)
                val s = models.state(model.id).value
                if (s is ModelState.Downloading || s == ModelState.Verifying || s == ModelState.Extracting) return@launch
                _confirmDownload.value = model
            }
        }
    }

    /** Download the model the dialog asks about, and make it the selected one (unless something is being read). */
    fun confirmDownload() {
        val model = _confirmDownload.value ?: return
        _confirmDownload.value = null
        models.startDownload(model.id)
        if (!state.value.active) viewModelScope.launch { settings.setModel(model.id) }
    }

    fun dismissDownload() {
        _confirmDownload.value = null
        pending = null
    }

    fun startDownload(modelId: String) = models.startDownload(modelId)

    fun cancelDownload(modelId: String) {
        if (pending?.modelId == modelId) pending = null
        models.cancelDownload(modelId)
    }

    /** Deletes a model; if it is the one being read, reading stops first. */
    fun deleteModel(modelId: String) {
        if (state.value.active && state.value.modelId == modelId) send(TtsCommand.Stop)
        models.installer(modelId).delete()
    }

    /**
     * Settings picker: an installed model becomes the selected one, otherwise the user is offered its download
     * (which selects it when confirmed).
     */
    fun selectModel(modelId: String) {
        val model = TtsModels.byId(modelId)
        if (models.installer(model.id).isInstalled()) setModel(model.id) else _confirmDownload.value = model
    }

    /**
     * Changes the model. While reading, an installed model restarts generation at the current sentence; one that is
     * not installed is refused with a message. When idle the preference is just saved.
     */
    fun setModel(modelId: String) {
        val model = TtsModels.byId(modelId)
        when {
            state.value.active && models.installer(model.id).isInstalled() -> send(TtsCommand.SetModel(model.id))
            state.value.active -> controller.message("${model.name} needs to be downloaded first.")
            else -> viewModelScope.launch { settings.setModel(model.id) }
        }
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

/** The audio in progress is this chapter AND this text variant (a different shown variant needs a restart). */
internal fun isSamePlayback(s: TtsState, chapterId: Long, variant: TextVariant): Boolean =
    s.active && s.ref?.chapterId == chapterId && (s.variant == null || s.variant == variant.name)
