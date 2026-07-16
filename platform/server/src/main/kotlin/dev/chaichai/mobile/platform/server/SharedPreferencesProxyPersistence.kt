package dev.chaichai.mobile.platform.server

import android.content.Context

/** SharedPreferences-backed [RegistryPersistence] for the non-secret proxy config store. */
class SharedPreferencesProxyPersistence(context: Context) : RegistryPersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY, value).apply()
    }

    companion object {
        internal const val PREFERENCES_NAME = "server_proxy_config"
        private const val KEY = "proxy_snapshot"
    }
}
