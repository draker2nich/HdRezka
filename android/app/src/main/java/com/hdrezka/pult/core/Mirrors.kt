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
        "https://rezka-ua.pub",
        "https://hdrezka.ag",
        "https://rezka.ag",
        "https://hdrezka.me",
        "https://rezka-ua.tv",
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

    /**
     * Находит рабочий домен (сеть). Кэширует результат.
     * Проверяем GET'ом и наличием реального каталога (класс b-content), а не
     * HEAD'ом: Cloudflare-заглушка отвечает 200/403 на HEAD и раньше ошибочно
     * принималась за «рабочее зеркало», из-за чего каталог не парсился.
     */
    suspend fun resolve(forceRecheck: Boolean = false): String = withContext(Dispatchers.IO) {
        val forced = normalize(forcedDomain)
        if (forced.isNotEmpty()) return@withContext forced

        val cached = resolved
        if (cached != null && !forceRecheck) return@withContext cached

        for (origin in MIRRORS) {
            if (probe(origin)) {
                resolved = origin
                return@withContext origin
            }
        }
        // ничего не ответило — отдаём первое, пусть конкретный запрос упадёт понятно
        resolved = MIRRORS[0]
        MIRRORS[0]
    }

    /** true, если зеркало отдаёт реальную страницу HDRezka (есть каталог). */
    suspend fun probe(origin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = Net.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(normalize(origin))
                .header("User-Agent", Net.USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                // Достижимо, если это реальный HDRezka ИЛИ анти-бот-заглушка
                // (её мы пройдём через WebView) — главное, что домен живой.
                val isRezka = body.contains("b-content") || body.contains("b-navigation") ||
                    body.contains("b-search")
                val isChallenge = body.contains("within.website") ||
                    body.contains("Проверяем, что вы не бот") ||
                    body.contains("anubis", ignoreCase = true) ||
                    body.contains("ddos-guard", ignoreCase = true) ||
                    body.contains("Just a moment", ignoreCase = true)
                val ok = resp.isSuccessful && (isRezka || isChallenge)
                android.util.Log.d(
                    Net.TAG,
                    "probe ${normalize(origin)} -> ${resp.code}, len=${body.length}, rezka=$isRezka, challenge=$isChallenge"
                )
                ok
            }
        } catch (e: Exception) {
            android.util.Log.d(Net.TAG, "probe $origin -> исключение: ${e.message}")
            false
        }
    }

    /** Применить пользовательский домен немедленно (сброс кэша авто-резолва). */
    fun applyForced(domain: String) {
        forcedDomain = domain.trim()
        resetCache()
    }
}
