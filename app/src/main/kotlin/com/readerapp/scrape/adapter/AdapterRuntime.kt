package com.readerapp.scrape.adapter

import com.readerapp.data.db.AdapterKind
import com.readerapp.data.db.SiteAdapter
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Executes a stored adapter version against a page. Never throws (except cancellation). */
interface AdapterRuntime {
    suspend fun execute(adapter: SiteAdapter, page: FetchedPage): ExtractionResult
}

object AdapterTag {
    fun of(domain: String, version: Int) = "adapter:$domain:v$version"

    /** (domain, version) if [extractor] names an adapter. */
    fun parse(extractor: String): Pair<String, Int>? {
        if (!extractor.startsWith("adapter:")) return null
        val rest = extractor.removePrefix("adapter:")
        val idx = rest.lastIndexOf(":v")
        if (idx < 0) return null
        val version = rest.substring(idx + 2).toIntOrNull() ?: return null
        return rest.substring(0, idx) to version
    }
}

@Singleton
class DefaultAdapterRuntime @Inject constructor(sandbox: WebViewJsSandbox) : AdapterRuntime {
    private val js = JsAdapterRunner(sandbox)

    override suspend fun execute(adapter: SiteAdapter, page: FetchedPage): ExtractionResult {
        val tag = AdapterTag.of(adapter.domain, adapter.version)
        return try {
            when (adapter.kind) {
                AdapterKind.JSON -> {
                    val spec = try {
                        JsonAdapterSpec.parse(adapter.code)
                    } catch (e: Exception) {
                        return ExtractionResult.Failure("Adapter JSON is invalid: ${e.message}")
                    }
                    val problems = spec.validate()
                    if (problems.isNotEmpty()) return ExtractionResult.Failure("Adapter spec invalid: ${problems.first()}")
                    withContext(Dispatchers.Default) { JsonAdapterRunner.run(spec, page, tag) }
                }
                AdapterKind.JS -> js.run(adapter.code, page, tag)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExtractionResult.Failure("Adapter crashed: ${e.message}")
        }
    }
}
