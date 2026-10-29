package com.readerapp.ui.adapters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.SiteAdapter
import com.readerapp.data.repo.AdapterRepository
import com.readerapp.scrape.heal.WorkRepairScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DomainAdapters(
    val domain: String,
    val versions: List<SiteAdapter>,
    val state: DomainState?,
) {
    val active: SiteAdapter? get() = versions.firstOrNull { it.status == AdapterStatus.ACTIVE }
    val lastSuccessAt: Long? get() = versions.mapNotNull { it.lastSuccessAt }.maxOrNull()
}

@HiltViewModel
class AdaptersViewModel @Inject constructor(
    private val repo: AdapterRepository,
    private val scheduler: WorkRepairScheduler,
) : ViewModel() {
    val domains: StateFlow<List<DomainAdapters>?> =
        combine(repo.observeAll(), repo.observeDomainStates()) { adapters, states ->
            val byDomain = adapters.groupBy { it.domain }
            val stateMap = states.associateBy { it.domain }
            (byDomain.keys + stateMap.keys).sorted().map { d ->
                DomainAdapters(d, byDomain[d].orEmpty().sortedByDescending { it.version }, stateMap[d])
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun regenerate(domain: String) = scheduler.scheduleRegenerate(domain)

    /** Makes [version] the active one; any other ACTIVE version is retired (kept, never deleted). */
    fun activate(domain: String, version: Int) {
        viewModelScope.launch {
            repo.versions(domain).filter { it.status == AdapterStatus.ACTIVE && it.version != version }
                .forEach { repo.setStatus(domain, it.version, AdapterStatus.RETIRED) }
            repo.get(domain, version)?.let { repo.resetFailures(it) }
            repo.setStatus(domain, version, AdapterStatus.ACTIVE)
            repo.domainState(domain)?.let { repo.saveDomainState(it.copy(genericFallback = false)) }
        }
    }
}
