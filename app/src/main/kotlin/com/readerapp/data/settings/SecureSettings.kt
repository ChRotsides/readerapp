package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class SecretKeyName(val prefKey: String) {
    ANTHROPIC_API_KEY("secret_anthropic_api_key"),
    JEV_API_KEY("secret_jev_api_key"),
    JEV_BASE_URL("secret_jev_base_url"),
}

/** Decrypted view of the stored secrets. Never log or persist this. */
data class Secrets(
    val anthropicApiKey: String? = null,
    val jevApiKey: String? = null,
    val jevBaseUrl: String? = null,
) {
    fun valueOf(name: SecretKeyName): String? = when (name) {
        SecretKeyName.ANTHROPIC_API_KEY -> anthropicApiKey
        SecretKeyName.JEV_API_KEY -> jevApiKey
        SecretKeyName.JEV_BASE_URL -> jevBaseUrl
    }
}

/** API keys / endpoints, stored in DataStore as Keystore-encrypted (AES/GCM) strings. */
@Singleton
class SecureSettings @Inject constructor(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val secrets: Flow<Secrets> = store.data.map { prefs ->
        fun read(name: SecretKeyName) = prefs[stringPreferencesKey(name.prefKey)]?.let(cipher::decrypt)
        Secrets(
            anthropicApiKey = read(SecretKeyName.ANTHROPIC_API_KEY),
            jevApiKey = read(SecretKeyName.JEV_API_KEY),
            jevBaseUrl = read(SecretKeyName.JEV_BASE_URL),
        )
    }

    suspend fun current(): Secrets = secrets.first()

    suspend fun anthropicApiKey(): String? = current().anthropicApiKey

    /**
     * Stores [value] encrypted. Returns false (nothing stored) when the value is not acceptable, currently only an
     * unusable Jev endpoint; a scheme-less endpoint such as `api.example.com` is stored as `https://api.example.com`.
     */
    suspend fun set(name: SecretKeyName, value: String): Boolean {
        var trimmed = value.trim()
        if (trimmed.isEmpty()) { clear(name); return true }
        if (name == SecretKeyName.JEV_BASE_URL) trimmed = normalizeBaseUrl(trimmed) ?: return false
        val encrypted = cipher.encrypt(trimmed)
        store.edit { it[stringPreferencesKey(name.prefKey)] = encrypted }
        return true
    }

    suspend fun clear(name: SecretKeyName) {
        store.edit { it.remove(stringPreferencesKey(name.prefKey)) }
    }
}

/** An http(s) endpoint with a host, or null. Adds `https://` when the user typed no scheme. */
fun normalizeBaseUrl(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return if (url.scheme == "http" || url.scheme == "https") withScheme else null
}

/** "••••abcd" style display for a stored secret. */
fun maskSecret(value: String): String =
    if (value.length <= 8) "••••••••" else "••••••••" + value.takeLast(4)
