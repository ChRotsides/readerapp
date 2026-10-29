package com.readerapp.data.repo

import com.readerapp.data.db.AdapterDao
import com.readerapp.data.db.AdapterFixture
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.DomainStateDao
import com.readerapp.data.db.SiteAdapter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the adapter runtime / repair logic needs from storage (an interface so tests can use a fake). */
interface AdapterStore {
    suspend fun activeFor(domain: String): SiteAdapter?
    suspend fun versions(domain: String): List<SiteAdapter>
    suspend fun get(domain: String, version: Int): SiteAdapter?
    /** Versions are append-only: callers insert new versions, never overwrite code of an existing one. */
    suspend fun save(adapter: SiteAdapter)
    suspend fun setStatus(domain: String, version: Int, status: AdapterStatus)
    suspend fun recordSuccess(adapter: SiteAdapter)
    suspend fun recordFailure(adapter: SiteAdapter)
    suspend fun resetFailures(adapter: SiteAdapter)
    suspend fun saveFixture(fixture: AdapterFixture): Long
    suspend fun fixture(id: Long): AdapterFixture?
    suspend fun fixtures(domain: String): List<AdapterFixture>
    suspend fun domainState(domain: String): DomainState?
    suspend fun saveDomainState(state: DomainState)
}

/** Storage for LLM-written site adapters and their fixtures. */
@Singleton
class AdapterRepository @Inject constructor(
    private val dao: AdapterDao,
    private val domainStates: DomainStateDao,
) : AdapterStore {
    fun observeAll(): Flow<List<SiteAdapter>> = dao.observeAll()

    fun observeDomainStates(): Flow<List<DomainState>> = domainStates.observeAll()

    override suspend fun activeFor(domain: String): SiteAdapter? = dao.active(domain)

    override suspend fun versions(domain: String): List<SiteAdapter> = dao.versions(domain)

    override suspend fun get(domain: String, version: Int): SiteAdapter? = dao.get(domain, version)

    override suspend fun save(adapter: SiteAdapter) = dao.upsert(adapter)

    override suspend fun setStatus(domain: String, version: Int, status: AdapterStatus) =
        dao.setStatus(domain, version, status)

    override suspend fun recordSuccess(adapter: SiteAdapter) =
        dao.recordSuccess(adapter.domain, adapter.version, System.currentTimeMillis())

    override suspend fun recordFailure(adapter: SiteAdapter) = dao.recordFailure(adapter.domain, adapter.version)

    override suspend fun resetFailures(adapter: SiteAdapter) = dao.resetFailures(adapter.domain, adapter.version)

    override suspend fun saveFixture(fixture: AdapterFixture): Long = dao.insertFixture(fixture)

    override suspend fun fixture(id: Long): AdapterFixture? = dao.fixture(id)

    override suspend fun fixtures(domain: String): List<AdapterFixture> = dao.fixtures(domain)

    override suspend fun domainState(domain: String): DomainState? = domainStates.get(domain)

    override suspend fun saveDomainState(state: DomainState) = domainStates.upsert(state)
}
