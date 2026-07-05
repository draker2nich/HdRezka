package com.hdrezka.pult.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Разрешение рабочего домена HDRezka — порт config.py.
 * Список зеркал по приоритету; первое ответившее кэшируется в памяти.
 * Пользователь может задать принудительный домен в настройках.
 */
object Mirrors {

    val MIRRORS = listOf(
        "https://rezka-ua.tv",
        "https://hdrezka.ag",
        "https://rezka.ag",
    )

    @Volatile
    private var resolved: String? = null

    @Volatile
    var forcedDomain: String = ""

    private fun normalize(value: String): String {
        var v = value.trim().trimEnd('/')
        if (v.isNotEmpty() && !v.contains("://")) v = "https://$v"
        return v
    }

    /** Не лезет в сеть — для мгновенного показа в UI. */
    fun peek(): String {
        val forced = normalize(forcedDomain)
        if (forced.isNotEmpty()) return forced
        return resolved ?: MIRRORS[0]
    }

    fun resetCache() { resolved = null }

    /** Находит рабочий домен (сеть). Кэширует результат. */
    suspend fun resolve(forceRecheck: Boolean = false): String = withContext(Dispatchers.IO) {
        val forced = normalize(forcedDomain)
        if (forced.isNotEmpty()) return@withContext forced

        val cached = resolved
        if (cached != null && !forceRecheck) return@withContext cached

        for (origin in MIRRORS) {
            try {
                val client = Net.client.newBuilder()
                    .callTimeout(5, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(origin).head()
                    .header("User-Agent", Net.USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code < 500) {
                        resolved = origin
                        return@withContext origin
                    }
                }
            } catch (_: Exception) {
                // пробуем следующее зеркало
            }
        }
        // ничего не ответило — отдаём первое, пусть конкретный запрос упадёт понятно
        resolved = MIRRORS[0]
        MIRRORS[0]
    }
}
