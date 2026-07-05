package com.hdrezka.pult.data

import android.content.Context

/** Настройки приложения — аналог config.plugins.hdrezka из settings.py. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hdrezka_pult", Context.MODE_PRIVATE)

    var selectedHost: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var selectedPort: Int
        get() = sp.getInt("port", 8123)
        set(v) = sp.edit().putInt("port", v).apply()

    var selectedName: String
        get() = sp.getString("device_name", "") ?: ""
        set(v) = sp.edit().putString("device_name", v).apply()

    var preferredTranslator: String
        get() = sp.getString("preferred_translator", "") ?: ""
        set(v) = sp.edit().putString("preferred_translator", v).apply()

    var autoQuality: Boolean
        get() = sp.getBoolean("auto_quality", true)
        set(v) = sp.edit().putBoolean("auto_quality", v).apply()

    var targetQuality: Int
        get() = sp.getInt("target_quality", 720)
        set(v) = sp.edit().putInt("target_quality", v).apply()

    var forcedDomain: String
        get() = sp.getString("forced_domain", "") ?: ""
        set(v) = sp.edit().putString("forced_domain", v).apply()
}
