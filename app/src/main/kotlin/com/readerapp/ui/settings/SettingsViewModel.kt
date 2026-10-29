package com.readerapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.settings.ReaderPrefs
import com.readerapp.data.settings.ReaderPrefsRepository
import com.readerapp.data.settings.SecretKeyName
import com.readerapp.data.settings.SecureSettings
import com.readerapp.data.settings.Secrets
import com.readerapp.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secure: SecureSettings,
    private val prefsRepo: ReaderPrefsRepository,
) : ViewModel() {
    val secrets: StateFlow<Secrets> = secure.secrets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Secrets())

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPrefs())

    fun saveSecret(name: SecretKeyName, value: String) {
        viewModelScope.launch { secure.set(name, value) }
    }

    fun clearSecret(name: SecretKeyName) {
        viewModelScope.launch { secure.clear(name) }
    }

    fun setFontSize(sp: Float) = viewModelScope.launch { prefsRepo.setFontSize(sp) }
    fun setLineSpacing(v: Float) = viewModelScope.launch { prefsRepo.setLineSpacing(v) }
    fun setPrefetchCount(n: Int) = viewModelScope.launch { prefsRepo.setPrefetchCount(n) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefsRepo.setThemeMode(mode) }
}
