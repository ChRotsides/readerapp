package com.readerapp.ui.crawl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.DiscoveryApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

enum class CrawlScope(val wire: String) { NOVEL(CrawlRequest.SCOPE_NOVEL), SITE(CrawlRequest.SCOPE_SITE) }

data class CrawlUiState(
    val url: String = "",
    val scope: CrawlScope = CrawlScope.NOVEL,
    /** "All" (default) or the number typed in [chaptersText]. */
    val allChapters: Boolean = true,
    val chaptersText: String = "",
    val starting: Boolean = false,
    /** Why Start failed (or what is wrong with the form after a tap). */
    val startError: String? = null,
    /** null until the first list arrives. */
    val jobs: List<CrawlJob>? = null,
    /** Last list/poll failure; the previous [jobs] stay visible. */
    val listError: String? = null,
    /** Jobs with a Pause/Resume/Cancel call in flight. */
    val busy: Set<Long> = emptySet(),
) {
    /** The request the form describes, or null while the URL or the chapter count is not valid. */
    fun request(): CrawlRequest? {
        val trimmed = url.trim()
        if (trimmed.toHttpUrlOrNull() == null) return null
        val chapters = if (allChapters) null else chaptersText.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return CrawlRequest(url = trimmed, scope = scope.wire, chaptersPerNovel = chapters)
    }

    val canStart: Boolean get() = !starting && request() != null
}

@HiltViewModel
class CrawlViewModel @Inject constructor(private val api: DiscoveryApi) : ViewModel() {
    private val _state = MutableStateFlow(CrawlUiState())
    val state: StateFlow<CrawlUiState> = _state.asStateFlow()
    private var pollJob: Job? = null

    fun setUrl(text: String) = _state.update { it.copy(url = text, startError = null) }
    fun setScope(scope: CrawlScope) = _state.update { it.copy(scope = scope) }
    fun setAllChapters(all: Boolean) = _state.update { it.copy(allChapters = all, startError = null) }
    fun setChaptersText(text: String) = _state.update { it.copy(chaptersText = text.filter(Char::isDigit), startError = null) }

    /** Polls the job list every [POLL_MS] while the screen is visible; call from the screen's start, stop from its stop. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refresh() {
        try {
            val jobs = api.crawlJobs()
            _state.update { it.copy(jobs = jobs, listError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listError = e.message ?: "Could not load the crawl jobs") }
        }
    }

    fun start() {
        val s = _state.value
        val request = s.request()
        if (request == null) {
            _state.update { it.copy(startError = if (s.url.isBlank()) "Enter the link to crawl." else "Enter a valid http(s) link and chapter count.") }
            return
        }
        if (s.starting) return
        _state.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                val job = api.startCrawl(request)
                _state.update { st -> st.copy(starting = false, url = "", jobs = listOf(job) + st.jobs.orEmpty().filter { it.id != job.id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(starting = false, startError = e.message ?: "Could not start the crawl") }
            }
        }
    }

    fun pause(id: Long) = act(id) { api.pauseCrawl(id) }
    fun resume(id: Long) = act(id) { api.resumeCrawl(id) }
    fun cancel(id: Long) = act(id) { api.cancelCrawl(id) }

    private fun act(id: Long, call: suspend () -> CrawlJob) {
        if (id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            try {
                val job = call()
                _state.update { st -> st.copy(jobs = st.jobs?.map { if (it.id == job.id) job else it }, listError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(listError = e.message ?: "The server did not accept that") }
            } finally {
                _state.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    companion object {
        const val POLL_MS = 5_000L
    }
}
