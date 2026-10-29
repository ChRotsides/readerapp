package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    suspend fun set(name: SecretKeyName, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return clear(name)
        val encrypted = cipher.encrypt(trimmed)
        store.edit { it[stringPreferencesKey(name.prefKey)] = encrypted }
    }

    suspend fun clear(name: SecretKeyName) {
        store.edit { it.remove(stringPreferencesKey(name.prefKey)) }
    }
}

/** "••••abcd" style display for a stored secret. */
fun maskSecret(value: String): String =
    if (value.length <= 8) "••••••••" else "••••••••" + value.takeLast(4)
