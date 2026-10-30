package com.readerapp.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atomic budget admission. Before a paid call starts, its worst-case cost is reserved under a mutex: the call is
 * admitted only when month-to-date spend + reservations of calls still in flight + this estimate stay within the cap.
 * Concurrent callers (repairs for several domains, verification, the reader) can therefore not all slip in under the
 * cap together. The reservation is released once the call has finished and its real usage has been recorded.
 */
class SpendReservations(private val budget: BudgetSource) {
    private val mutex = Mutex()
    private var reserved = 0L

    /** True when [estimateMicros] was reserved; the caller must then [release] the same amount. */
    suspend fun tryReserve(estimateMicros: Long): Boolean = mutex.withLock {
        if (budget.spentMicros() + reserved + estimateMicros > budget.capMicros()) {
            false
        } else {
            reserved += estimateMicros
            true
        }
    }

    suspend fun release(estimateMicros: Long) {
        withContext(NonCancellable) { mutex.withLock { reserved -= estimateMicros } }
    }

    suspend fun reservedMicros(): Long = mutex.withLock { reserved }
}

/**
 * The [LlmClient] every feature gets: refuses to start a call once the monthly spend cap is reached or would be
 * exceeded by the call's worst case ([LlmException.SpendCapReached]) and delegates to [ClaudeClient] (which records
 * usage) otherwise.
 */
@Singleton
class GuardedLlmClient(
    private val delegate: LlmClient,
    private val spend: SpendGuard,
    budget: BudgetSource? = spend as? BudgetSource,
) : LlmClient {
    @Inject
    constructor(claude: ClaudeClient, spend: SpendGuard) : this(claude as LlmClient, spend, spend as? BudgetSource)

    private val reservations = budget?.let { SpendReservations(it) }

    /** Returns the reserved amount (0 when no budget source is wired); throws when the call may not start. */
    private suspend fun admit(request: LlmRequest): Long {
        if (spend.capReached()) throw LlmException.SpendCapReached()
        val reservations = reservations ?: return 0
        val estimate = ModelPricing.estimateMaxMicros(
            request.model, ModelPricing.inputBytes(request), request.maxTokens, fallback = request.serverFallback,
        )
        if (!reservations.tryReserve(estimate)) throw LlmException.SpendCapReached()
        return estimate
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val ticket = admit(request)
        try {
            return delegate.complete(request)
        } finally {
            if (ticket > 0) reservations?.release(ticket)
        }
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val ticket = admit(request)
        try {
            delegate.stream(request).collect { emit(it) }
        } finally {
            if (ticket > 0) reservations?.release(ticket)
        }
    }
}
