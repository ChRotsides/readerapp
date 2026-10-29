package com.readerapp.data.repo

import com.readerapp.data.db.AdapterDao
import com.readerapp.data.db.AdapterFixture
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.SiteAdapter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Storage for LLM-written site adapters and their fixtures. Used by the Milestone 2 adapter runtime. */
@Singleton
class AdapterRepository @Inject constructor(private val dao: AdapterDao) {
    fun observeAll(): Flow<List<SiteAdapter>> = dao.observeAll()

    suspend fun activeFor(domain: String): SiteAdapter? = dao.active(domain)

    suspend fun versions(domain: String): List<SiteAdapter> = dao.versions(domain)

    suspend fun save(adapter: SiteAdapter) = dao.upsert(adapter)

    suspend fun setStatus(domain: String, version: Int, status: AdapterStatus) =
        dao.setStatus(domain, version, status)

    suspend fun recordSuccess(adapter: SiteAdapter) =
        dao.recordSuccess(adapter.domain, adapter.version, System.currentTimeMillis())

    suspend fun recordFailure(adapter: SiteAdapter) = dao.recordFailure(adapter.domain, adapter.version)

    suspend fun saveFixture(fixture: AdapterFixture): Long = dao.insertFixture(fixture)

    suspend fun fixture(id: Long): AdapterFixture? = dao.fixture(id)

    suspend fun fixtures(domain: String): List<AdapterFixture> = dao.fixtures(domain)
}
