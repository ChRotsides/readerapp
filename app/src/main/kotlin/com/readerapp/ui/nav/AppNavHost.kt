package com.readerapp.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.readerapp.ui.browser.BrowserScreen
import com.readerapp.ui.detail.NovelDetailScreen
import com.readerapp.ui.library.LibraryScreen
import com.readerapp.ui.reader.ReaderScreen
import com.readerapp.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class BrowserRoute(val url: String? = null)
@Serializable data class NovelRoute(val novelId: Long)
@Serializable data class ReaderRoute(val chapterId: Long)
@Serializable object SettingsRoute

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onOpenNovel = { nav.navigate(NovelRoute(it)) },
                onOpenBrowser = { nav.navigate(BrowserRoute()) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
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
            )
        }
        composable<ReaderRoute> {
            ReaderScreen(
                onBack = { nav.popBackStack() },
                onOpenInBrowser = { nav.navigate(BrowserRoute(it)) },
            )
        }
        composable<SettingsRoute> { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
