package com.readerapp.data.repo

import androidx.room.withTransaction
import com.readerapp.data.db.AdapterDao
import com.readerapp.data.db.AppDatabase
import com.readerapp.data.db.AdapterFixture
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.DomainStateDao
import com.readerapp.data.db.SiteAdapter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The swap may proceed: nothing is ACTIVE when none was expected, else the expected version still is (extras are healed). */
internal fun swapAllowed(currentActive: List<Int>, expectedActive: Int?): Boolean =
    if (expectedActive == null) currentActive.isEmpty() else expectedActive in currentActive

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

    /**
     * Makes [version] the only ACTIVE one (other ACTIVE versions are RETIRED, never deleted). Implementations that
     * persist must make this atomic; it is the single path for manual activation.
     */
    suspend fun activateExclusive(domain: String, version: Int) {
        versions(domain).filter { it.status == AdapterStatus.ACTIVE && it.version != version }
            .forEach { setStatus(domain, it.version, AdapterStatus.RETIRED) }
        setStatus(domain, version, AdapterStatus.ACTIVE)
    }

    /**
     * Compare-and-swap used by repair: only when the domain's ACTIVE version is still [expectedActive] (null = there is
     * none) does [to] become ACTIVE, with [expectedActive] set to [retireAs]; otherwise nothing changes and false is
     * returned (the world moved on, e.g. the user activated a version meanwhile, so the repair result is stale).
     * Implementations that persist must make this atomic with [activateExclusive].
     */
    suspend fun swapActive(domain: String, expectedActive: Int?, to: Int, retireAs: AdapterStatus): Boolean {
        val current = versions(domain).filter { it.status == AdapterStatus.ACTIVE }.map { it.version }
        if (!swapAllowed(current, expectedActive)) return false
        current.filter { it != to }.forEach { setStatus(domain, it, if (it == expectedActive) retireAs else AdapterStatus.RETIRED) }
        setStatus(domain, to, AdapterStatus.ACTIVE)
        return true
    }
}

/** Storage for LLM-written site adapters and their fixtures. */
@Singleton
class AdapterRepository @Inject constructor(
    private val db: AppDatabase,
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

    override suspend fun activateExclusive(domain: String, version: Int) = db.withTransaction {
        dao.versions(domain).filter { it.status == AdapterStatus.ACTIVE && it.version != version }
            .forEach { dao.setStatus(domain, it.version, AdapterStatus.RETIRED) }
        dao.resetFailures(domain, version)
        dao.setStatus(domain, version, AdapterStatus.ACTIVE)
    }

    override suspend fun swapActive(domain: String, expectedActive: Int?, to: Int, retireAs: AdapterStatus): Boolean =
        db.withTransaction {
            val current = dao.versions(domain).filter { it.status == AdapterStatus.ACTIVE }.map { it.version }
            if (!swapAllowed(current, expectedActive)) return@withTransaction false
            current.filter { it != to }.forEach { dao.setStatus(domain, it, if (it == expectedActive) retireAs else AdapterStatus.RETIRED) }
            dao.setStatus(domain, to, AdapterStatus.ACTIVE)
            true
        }
}
