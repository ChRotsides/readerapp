package com.readerapp.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.readerapp.search.SimilarSeed
import com.readerapp.ui.browser.BrowserScreen
import com.readerapp.ui.detail.NovelDetailScreen
import com.readerapp.ui.crawl.CrawlScreen
import com.readerapp.ui.discover.DiscoverScreen
import com.readerapp.ui.favorites.FavoritesScreen
import com.readerapp.ui.discover.WorkScreen
import com.readerapp.ui.glossary.GlossaryScreen
import com.readerapp.ui.library.LibraryScreen
import com.readerapp.ui.reader.ReaderScreen
import com.readerapp.ui.adapters.AdaptersScreen
import com.readerapp.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class BrowserRoute(val url: String? = null)
@Serializable data class NovelRoute(val novelId: Long)
@Serializable data class ReaderRoute(val chapterId: Long)
@Serializable data class GlossaryRoute(val novelId: Long)
@Serializable object SettingsRoute
@Serializable object AdaptersRoute
/** Discover; with a `similar*` argument it opens straight into a "Find similar" search (a library novel, or a corpus work). */
@Serializable data class DiscoverRoute(val similarNovelId: Long? = null, val similarChapterId: Long? = null, val similarWorkId: String? = null)
@Serializable object CrawlRoute
@Serializable object FavoritesRoute
@Serializable data class WorkRoute(val workId: String)

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onOpenNovel = { nav.navigate(NovelRoute(it)) },
                onOpenBrowser = { nav.navigate(BrowserRoute()) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenDiscover = { nav.navigate(DiscoverRoute()) },
                onFindSimilar = { nav.navigate(DiscoverRoute(similarNovelId = it)) },
            )
        }
        composable<DiscoverRoute> { entry ->
            val route = entry.toRoute<DiscoverRoute>()
            DiscoverScreen(
                onBack = { nav.popBackStack() },
                onOpenWork = { nav.navigate(WorkRoute(it)) },
                onOpenNovel = { nav.navigate(NovelRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenCrawl = { nav.navigate(CrawlRoute) },
                onOpenFavorites = { nav.navigate(FavoritesRoute) },
                similarSeed = when {
                    route.similarNovelId != null -> SimilarSeed.LibraryNovel(route.similarNovelId, route.similarChapterId)
                    route.similarWorkId != null -> SimilarSeed.CorpusWork(route.similarWorkId)
                    else -> null
                },
            )
        }
        composable<FavoritesRoute> {
            FavoritesScreen(
                onBack = { nav.popBackStack() },
                onOpenWork = { nav.navigate(WorkRoute(it)) },
                onOpenNovel = { nav.navigate(NovelRoute(it)) },
                onOpenInBrowser = { nav.navigate(BrowserRoute(it)) },
            )
        }
        composable<CrawlRoute> {
            CrawlScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SettingsRoute) })
        }
        composable<WorkRoute> {
            WorkScreen(
                onBack = { nav.popBackStack() },
                onOpenInBrowser = { nav.navigate(BrowserRoute(it)) },
                onOpenNovel = { nav.navigate(NovelRoute(it)) },
                onMoreLikeThis = { nav.navigate(DiscoverRoute(similarWorkId = it)) },
            )
        }
        composable<BrowserRoute> {
            BrowserScreen(
                onBack = { nav.popBackStack() },
                onImported = { novelId, chapterId ->
                    nav.navigate(NovelRoute(novelId)) { popUpTo<LibraryRoute>() }
                    nav.navigate(ReaderRoute(chapterId))
                },
            )
        }
        composable<NovelRoute> {
            NovelDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenChapter = { nav.navigate(ReaderRoute(it)) },
                onOpenInBrowser = { nav.navigate(BrowserRoute(it)) },
                onOpenGlossary = { nav.navigate(GlossaryRoute(it)) },
            )
        }
        composable<GlossaryRoute> { GlossaryScreen(onBack = { nav.popBackStack() }) }
        composable<ReaderRoute> {
            ReaderScreen(
                onBack = { nav.popBackStack() },
                onOpenInBrowser = { nav.navigate(BrowserRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onFindSimilar = { novelId, chapterId -> nav.navigate(DiscoverRoute(similarNovelId = novelId, similarChapterId = chapterId)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAdapters = { nav.navigate(AdaptersRoute) },
                onOpenCrawl = { nav.navigate(CrawlRoute) },
            )
        }
        composable<AdaptersRoute> { AdaptersScreen(onBack = { nav.popBackStack() }) }
    }
}
