package com.readerapp.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [LlmClient] every feature gets: refuses to start a call once the monthly spend cap is reached
 * ([LlmException.SpendCapReached]) and delegates to [ClaudeClient] (which records usage) otherwise.
 */
@Singleton
class GuardedLlmClient(
    private val delegate: LlmClient,
    private val spend: SpendGuard,
) : LlmClient {
    @Inject
    constructor(claude: ClaudeClient, spend: SpendGuard) : this(claude as LlmClient, spend)

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (spend.capReached()) throw LlmException.SpendCapReached()
        return delegate.complete(request)
    }

    override fun stream(request: LlmRequest): Flow<String> = flow {
        if (spend.capReached()) throw LlmException.SpendCapReached()
        delegate.stream(request).collect { emit(it) }
    }
}
