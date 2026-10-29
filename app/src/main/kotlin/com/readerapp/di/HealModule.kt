package com.readerapp.di

import com.readerapp.ai.ClaudeClient
import com.readerapp.ai.LlmClient
import com.readerapp.ai.SpendGuard
import com.readerapp.ai.UsageRepository
import com.readerapp.data.repo.AdapterRepository
import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.adapter.AdapterRuntime
import com.readerapp.scrape.adapter.DefaultAdapterRuntime
import com.readerapp.scrape.heal.AdapterNotifier
import com.readerapp.scrape.heal.AndroidAdapterNotifier
import com.readerapp.scrape.heal.DefaultPageSource
import com.readerapp.scrape.heal.ExtractionVerifier
import com.readerapp.scrape.heal.PageSource
import com.readerapp.scrape.heal.RepairScheduler
import com.readerapp.scrape.heal.VerifierProvider
import com.readerapp.scrape.heal.WorkRepairScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class HealModule {
    @Binds abstract fun adapterStore(impl: AdapterRepository): AdapterStore
    @Binds abstract fun adapterRuntime(impl: DefaultAdapterRuntime): AdapterRuntime
    @Binds abstract fun llmClient(impl: ClaudeClient): LlmClient
    @Binds abstract fun spendGuard(impl: UsageRepository): SpendGuard
    @Binds abstract fun verifier(impl: VerifierProvider): ExtractionVerifier
    @Binds abstract fun pageSource(impl: DefaultPageSource): PageSource
    @Binds abstract fun notifier(impl: AndroidAdapterNotifier): AdapterNotifier
    @Binds abstract fun scheduler(impl: WorkRepairScheduler): RepairScheduler
}
