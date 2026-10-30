package com.readerapp.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.ai.LlmException
import com.readerapp.ai.qa.QaPromptBuilder
import com.readerapp.ai.qa.QaService
import com.readerapp.ai.qa.QaTurn
import com.readerapp.data.db.TextVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QaMessage(val fromUser: Boolean, val text: String)

data class QaError(val text: String, val openSettings: Boolean = false)

data class QaUiState(
    val open: Boolean = false,
    val selected: String = "",
    val messages: List<QaMessage> = emptyList(),
    /** Writing summaries of earlier chapters / waiting for the first tokens. */
    val busy: Boolean = false,
    val error: QaError? = null,
)

/**
 * One highlight-Q&A thread. The thread (and the context built for it) is kept for the session: re-opening the same
 * highlight in the same chapter continues it; a new highlight or chapter starts a new thread. The context block is
 * built once per thread and reused verbatim, so follow-up questions hit the prompt cache.
 */
@HiltViewModel
class QaViewModel @Inject constructor(private val qa: QaService) : ViewModel() {
    private val _state = MutableStateFlow(QaUiState())
    val state: StateFlow<QaUiState> = _state.asStateFlow()

    private var chapterId = -1L
    private var variant = TextVariant.ORIGINAL
    private var paragraphs: List<String> = emptyList()
    private var contextText: String? = null
    private val turns = ArrayList<QaTurn>()
    private var job: Job? = null

    fun open(selected: String, chapterId: Long, variant: TextVariant, paragraphs: List<String>) {
        val sameThread = chapterId == this.chapterId && variant == this.variant && selected.trim() == _state.value.selected
        if (!sameThread) {
            job?.cancel()
            this.chapterId = chapterId
            this.variant = variant
            this.paragraphs = paragraphs
            contextText = null
            turns.clear()
            _state.value = QaUiState(open = true, selected = selected.trim())
        } else {
            _state.update { it.copy(open = true) }
        }
    }

    fun dismiss() {
        _state.update { it.copy(open = false) }
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.busy) return
        val selected = _state.value.selected
        val first = turns.isEmpty()
        val userText = if (first) QaPromptBuilder.questionText(selected, q) else "<question>\n$q\n</question>"
        _state.update { it.copy(messages = it.messages + QaMessage(true, q) + QaMessage(false, ""), busy = true, error = null) }
        job = viewModelScope.launch {
            val answer = StringBuilder()
            try {
                val context = contextText ?: qa.buildContext(chapterId, variant, paragraphs).also { contextText = it }
                qa.ask(context, userText, turns.toList()).collect { delta ->
                    answer.append(delta)
                    setAnswer(answer.toString())
                }
                turns += QaTurn(userText, answer.toString())
                _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                fail(answer, errorFor(e))
            } catch (e: Exception) {
                fail(answer, QaError(e.message ?: "Something went wrong."))
            }
        }
    }

    /** Removes the empty answer placeholder (a partial answer stays visible but is not added to the thread). */
    private fun fail(partial: StringBuilder, error: QaError) {
        _state.update { st ->
            st.copy(messages = if (partial.isEmpty()) st.messages.dropLast(1) else st.messages, busy = false, error = error)
        }
    }

    private fun setAnswer(text: String) {
        _state.update { st ->
            val m = st.messages.toMutableList()
            if (m.isNotEmpty()) m[m.lastIndex] = QaMessage(false, text)
            st.copy(messages = m)
        }
    }

    private fun errorFor(e: LlmException): QaError = when (e) {
        is LlmException.SpendCapReached -> QaError("The monthly AI spend cap has been reached. Raise it in Settings to ask more questions.", openSettings = true)
        is LlmException.NotConfigured -> QaError("Add your Anthropic API key in Settings to use Ask AI.", openSettings = true)
        is LlmException.Refused -> QaError("The model declined to answer this one.")
        is LlmException.Truncated -> QaError("The answer was cut off. Try a narrower question.")
        else -> QaError(e.message ?: "The AI request failed.")
    }
}
