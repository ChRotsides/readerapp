package com.readerapp.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.readerapp.ai.resume.DeferredJob
import com.readerapp.ai.resume.DeferredJobDispatcher
import com.readerapp.ai.resume.DeferredWorkStore
import com.readerapp.ai.resume.SpendResumeScheduler
import com.readerapp.ai.resume.SpendResumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

class SpendResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Sched : SpendResumeScheduler {
        var monthly = 0
        var now = 0
        override fun scheduleAtNextPeriod() { monthly++ }
        override fun resumeNow() { now++ }
    }

    private fun store() = DeferredWorkStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { tmp.newFile("d${System.nanoTime()}.preferences_pb") },
    )

    @Test
    fun deferredJobsAreDispatchedOnlyOnceTheCapIsNoLongerReached() = runBlocking {
        val store = store()
        var capped = true
        val dispatched = mutableListOf<DeferredJob>()
        val sched = Sched()
        val resumer = SpendResumer(store, SpendGuard { capped }, DeferredJobDispatcher { dispatched += it }, sched)

        resumer.defer(DeferredJob.Adapter("a.example", "repair"))
        resumer.defer(DeferredJob.Process(1, 10))
        resumer.defer(DeferredJob.Process(1, 12))           // same novel: newest anchor replaces the older one
        assertEquals(3, sched.monthly)

        assertTrue(resumer.run().isEmpty())                 // still capped: nothing runs, wake-up re-armed
        assertEquals(4, sched.monthly)
        assertEquals(2, store.all().size)

        capped = false                                      // cap raised or month rolled over
        val ran = resumer.run()
        assertEquals(2, ran.size)
        assertTrue(DeferredJob.Process(1, 12) in dispatched && DeferredJob.Process(1, 10) !in dispatched)
        assertTrue(store.all().isEmpty())
        assertTrue(resumer.run().isEmpty())
    }

    @Test
    fun nextMonthStartRollsOverTheYear() {
        val zone = ZoneId.of("UTC")
        val dec = ZonedDateTime.of(2026, 12, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val jan = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(jan, ModelPricing.nextMonthStartMillis(dec, zone))
    }
}
