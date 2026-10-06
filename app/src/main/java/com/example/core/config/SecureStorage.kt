package com.example.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Handles encrypted storage for sensitive keys (e.g. Gemini API Key)
 * using AndroidX Security MasterKey and EncryptedSharedPreferences.
 *
 * Fails closed: if the keystore cannot be used, secrets are kept in memory only and are never
 * written to disk in plaintext. The user is re-prompted for the key on the next launch.
 */
class SecureStorage(private val context: Context) {

    private val sharedPreferences: SharedPreferences by lazy { createPreferences() }

    @Volatile
    private var usingEncryptedStorage = true

    private fun createPreferences(): SharedPreferences {
        return try {
            createEncrypted()
        } catch (first: Throwable) {
            // Typically a keystore key that was invalidated (restore, lock-screen change). The
            // old file can never be decrypted again, so discard it and start clean: the user is
            // simply asked for the API key again.
            Log.e("SecureStorage", "Encrypted prefs unreadable; resetting them", first)
            try {
                context.deleteSharedPreferences(PREFS_FILE)
                createEncrypted()
            } catch (second: Throwable) {
                // Fail closed: never write secrets to disk unencrypted. The key then lives only
                // in memory for this process (also what headless JVM tests get).
                usingEncryptedStorage = false
                Log.e("SecureStorage", "AndroidKeyStore unavailable; keeping secrets in memory only", second)
                InMemoryPreferences()
            }
        }
    }

    private fun createEncrypted(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** True when secrets are protected by AndroidKeyStore-backed encryption. */
    fun isEncrypted(): Boolean {
        // Touch the lazy so the flag reflects the actual creation outcome.
        sharedPreferences
        return usingEncryptedStorage
    }

    fun getApiKey(): String {
        return sharedPreferences.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(apiKey: String) {
        // commit() rather than apply(): a process death before an async write would lose the key.
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey.trim()).commit()
    }

    fun hasApiKey(): Boolean {
        return sharedPreferences.all[KEY_API_KEY]?.toString()?.isNotEmpty() == true
    }

    fun getSessionHandle(): String? {
        return sharedPreferences.getString(KEY_SESSION_HANDLE, null)
    }

    fun setSessionHandle(handle: String?) {
        if (handle == null) {
            sharedPreferences.edit().remove(KEY_SESSION_HANDLE).apply()
        } else {
            sharedPreferences.edit().putString(KEY_SESSION_HANDLE, handle).apply()
        }
    }

    fun clearSessionHandle() {
        sharedPreferences.edit().remove(KEY_SESSION_HANDLE).apply()
    }

    companion object {
        private const val PREFS_FILE = "jarvis_secure_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_SESSION_HANDLE = "gemini_live_session_handle"
    }
}

/** Non-persistent SharedPreferences used only when encrypted storage is impossible. */
private class InMemoryPreferences : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false
        private fun put(k: String?, v: Any?) = apply { if (k != null) pending[k] = v }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            return true
        }
        override fun apply() { commit() }
    }
}
