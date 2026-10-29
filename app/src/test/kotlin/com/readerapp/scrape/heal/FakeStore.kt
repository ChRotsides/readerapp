package com.readerapp.scrape.heal

import com.readerapp.data.db.AdapterFixture
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.SiteAdapter
import com.readerapp.data.repo.AdapterStore

class FakeStore(initial: List<SiteAdapter> = emptyList()) : AdapterStore {
    val adapters = initial.associateBy { it.domain to it.version }.toMutableMap()
    val fixtureList = mutableListOf<AdapterFixture>()
    val states = mutableMapOf<String, DomainState>()

    override suspend fun activeFor(domain: String) =
        adapters.values.filter { it.domain == domain && it.status == AdapterStatus.ACTIVE }.maxByOrNull { it.version }
    override suspend fun versions(domain: String) =
        adapters.values.filter { it.domain == domain }.sortedByDescending { it.version }
    override suspend fun get(domain: String, version: Int) = adapters[domain to version]
    override suspend fun save(adapter: SiteAdapter) { adapters[adapter.domain to adapter.version] = adapter }
    override suspend fun setStatus(domain: String, version: Int, status: AdapterStatus) {
        adapters[domain to version]?.let { adapters[domain to version] = it.copy(status = status) }
    }
    override suspend fun recordSuccess(adapter: SiteAdapter) {
        adapters[adapter.domain to adapter.version]?.let {
            adapters[adapter.domain to adapter.version] = it.copy(successCount = it.successCount + 1, consecutiveFailures = 0)
        }
    }
    override suspend fun recordFailure(adapter: SiteAdapter) {
        adapters[adapter.domain to adapter.version]?.let {
            adapters[adapter.domain to adapter.version] =
                it.copy(failCount = it.failCount + 1, consecutiveFailures = it.consecutiveFailures + 1)
        }
    }
    override suspend fun resetFailures(adapter: SiteAdapter) {
        adapters[adapter.domain to adapter.version]?.let { adapters[adapter.domain to adapter.version] = it.copy(consecutiveFailures = 0) }
    }
    override suspend fun saveFixture(fixture: AdapterFixture): Long {
        val saved = fixture.copy(id = fixtureList.size + 1L)
        fixtureList += saved
        return saved.id
    }
    override suspend fun fixture(id: Long) = fixtureList.firstOrNull { it.id == id }
    override suspend fun fixtures(domain: String) = fixtureList.filter { it.domain == domain }.sortedByDescending { it.id }
    override suspend fun domainState(domain: String) = states[domain]
    override suspend fun saveDomainState(state: DomainState) { states[state.domain] = state }
}

fun adapter(version: Int, status: AdapterStatus, code: String = "good", domain: String = "site.example") =
    SiteAdapter(domain, version, com.readerapp.data.db.AdapterKind.JS, "function extract(d){/*$code*/}", "claude-sonnet-5-5", status)
