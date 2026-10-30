package com.readerapp.ai

import com.readerapp.data.db.AiUsage
import com.readerapp.data.db.AiUsageDao
import com.readerapp.data.settings.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Decides whether more paid AI work may start. */
fun interface SpendGuard {
    suspend fun capReached(): Boolean
}

/** Month-to-date spend and the cap in micro-USD, for atomic budget reservations. */
interface BudgetSource {
    suspend fun spentMicros(): Long
    suspend fun capMicros(): Long
}

/** Records per-call token usage and answers month-to-date spend questions. */
@Singleton
class UsageRepository @Inject constructor(
    private val dao: AiUsageDao,
    private val settings: AiSettings,
) : SpendGuard, BudgetSource {
    suspend fun record(model: String, purpose: String, inputTokens: Long, outputTokens: Long) {
        dao.insert(
            AiUsage(
                createdAt = System.currentTimeMillis(),
                model = model,
                purpose = purpose,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costMicros = ModelPricing.costMicros(model, inputTokens, outputTokens),
            ),
        )
    }

    override suspend fun spentMicros(): Long = dao.costSince(ModelPricing.monthStartMillis(System.currentTimeMillis()))

    override suspend fun capMicros(): Long = Math.round(settings.currentCapUsd() * 1_000_000)

    suspend fun monthToDateUsd(): Double =
        ModelPricing.microsToUsd(dao.costSince(ModelPricing.monthStartMillis(System.currentTimeMillis())))

    fun observeMonthToDateUsd(): Flow<Double> =
        dao.observeCostSince(ModelPricing.monthStartMillis(System.currentTimeMillis())).map(ModelPricing::microsToUsd)

    override suspend fun capReached(): Boolean = monthToDateUsd() >= settings.currentCapUsd()
}
