package com.readerapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.readerapp.data.settings.ReaderPrefs
import com.readerapp.data.settings.ReaderPrefsRepository
import com.readerapp.ui.nav.AppNavHost
import com.readerapp.ui.theme.ReaderAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefsRepository: ReaderPrefsRepository

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Adapter fixes/failures are reported by notification (Android 13+ needs the runtime permission).
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val prefs by prefsRepository.prefs.collectAsState(initial = ReaderPrefs())
            ReaderAppTheme(themeMode = prefs.themeMode) {
                AppNavHost()
            }
        }
    }
}
