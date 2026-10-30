package com.readerapp.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.scrape.UserAgents
import com.readerapp.scrape.WebViewFetcher

/** Turns address-bar input into a URL: explicit URL, bare host, or a search query. */
fun normalizeUrlInput(input: String): String {
    val text = input.trim()
    return when {
        text.startsWith("http://", true) || text.startsWith("https://", true) -> text
        !text.contains(' ') && text.contains('.') -> "https://$text"
        else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(text, "UTF-8")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    onImported: (novelId: Long, chapterId: Long) -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var addressText by remember { mutableStateOf(viewModel.startUrl) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    LaunchedEffect(state.imported) {
        state.imported?.let { (novelId, chapterId) -> viewModel.importHandled(); onImported(novelId, chapterId) }
    }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focus.clearFocus()
                            webView?.loadUrl(normalizeUrlInput(addressText))
                        }),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close browser") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (progress in 0.01f..0.99f || state.working) {
                    LinearProgressIndicator(
                        progress = { if (state.working) 0f else progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                    }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
                    Spacer()
                    TextButton(
                        enabled = !state.working,
                        onClick = {
                            val wv = webView ?: return@TextButton
                            val url = wv.url ?: return@TextButton
                            wv.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                                val html = WebViewFetcher.decodeJsString(raw)
                                if (html.isNullOrEmpty()) viewModel.fail("Page is empty") else viewModel.prepareImport(url, html)
                            }
                        },
                    ) { Text("Add to library") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = UserAgents.DESKTOP
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            url?.let { addressText = it }
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            url?.let { addressText = it }
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            CookieManager.getInstance().flush()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                    }
                    loadUrl(viewModel.startUrl)
                    webView = this
                }
            },
        )
    }

    state.draft?.let { draft ->
        var title by remember(draft) { mutableStateOf(draft.suggestedTitle) }
        AlertDialog(
            onDismissRequest = viewModel::dismissDraft,
            title = { Text("Add to library") },
            text = {
                Column {
                    Text(
                        "${draft.extraction.chapter.paragraphs.size} paragraphs found" +
                            (draft.extraction.chapter.title?.let { " in \"$it\"" } ?: "") + ".",
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Novel title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmImport(title) }) { Text("Add & read") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDraft) { Text("Cancel") } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Spacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
}
