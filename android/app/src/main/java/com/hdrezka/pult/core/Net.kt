package com.hdrezka.pult.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Единый HTTP-клиент на всё приложение — аналог session_pool.py из плагина:
 * keep-alive, общий cookie-jar, User-Agent как у десктопного Chrome.
 *
 * Отдельно решается анти-бот Anubis: HDRezka отдаёт JS proof-of-work
 * («Проверяем, что вы не бот!»), который OkHttp пройти не может. При обнаружении
 * такой заглушки запрос отдаётся в ChallengeSolver (скрытый WebView исполнит JS
 * и получит cookie-пропуск), после чего запрос повторяется уже с cookie.
 */
object Net {

    const val TAG = "HdRezkaPult"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36"

    /** Контекст приложения — нужен ChallengeSolver'у для WebView. */
    @Volatile
    var appContext: Context? = null

    // Простой in-memory cookie-jar: HDRezka выдаёт сессионные cookie, которые
    // надо возвращать на последующих запросах (иначе часть ответов пустая).
    private val cookieStore = HashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = cookieStore.getOrPut(host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            // default_cookies из плагина: hdmbbs=1
            if (list.none { it.name == "hdmbbs" }) {
                list.add(
                    Cookie.Builder().name("hdmbbs").value("1")
                        .domain(url.host).path("/").build()
                )
            }
            return list
        }
    }

    /** Прокинуть cookie, полученные WebView'ом (строка "a=1; b=2"), в наш jar. */
    fun injectCookies(url: String, cookieHeader: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val host = httpUrl.host
        val list = cookieStore.getOrPut(host) { mutableListOf() }
        for (part in cookieHeader.split(";")) {
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val name = p.substring(0, eq).trim()
            val value = p.substring(eq + 1).trim()
            list.removeAll { it.name == name }
            list.add(Cookie.Builder().name(name).value(value).domain(host).path("/").build())
        }
        android.util.Log.d(TAG, "injectCookies: $host <- ${cookieHeader.take(80)}")
    }

    val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    // ── низкоуровневое выполнение с обходом анти-бота ────────────────────────────

    private suspend fun exec(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
    }

    /** Признаки анти-бот-заглушки (Anubis / DDoS-Guard / Cloudflare). */
    private fun looksLikeChallenge(body: String): Boolean {
        if (body.isEmpty()) return false
        return body.contains("within.website") ||
            body.contains("Проверяем, что вы не бот") ||
            body.contains("id=\"anubis") ||
            body.contains("anubis_version") ||
            body.contains("ddos-guard", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("Checking your browser", ignoreCase = true)
    }

    /** GET с обходом: при заглушке берём готовый HTML из WebView. */
    private suspend fun fetchHtmlCleared(url: String): Pair<Int, String> {
        val req = baseRequest(url).get().build()
        var result = exec(req)
        if (looksLikeChallenge(result.second)) {
            android.util.Log.d(TAG, "challenge detected on $url, loading via WebView…")
            val html = ChallengeSolver.loadHtml(url)
            result = if (html != null) 200 to html else exec(req)
        }
        return result
    }

    /** POST с обходом: прогреваем cookie через WebView и повторяем запрос. */
    private suspend fun postCleared(request: Request): Pair<Int, String> {
        var result = exec(request)
        if (looksLikeChallenge(result.second)) {
            val origin = "${request.url.scheme}://${request.url.host}"
            android.util.Log.d(TAG, "challenge on POST $origin, warming cookies…")
            if (ChallengeSolver.solve(origin)) result = exec(request)
        }
        return result
    }

    // ── публичные методы (HDRezka) ───────────────────────────────────────────────

    /** GET → распарсенный Jsoup Document. */
    suspend fun getDocument(url: String): Document {
        val (code, body) = fetchHtmlCleared(url)
        val doc = Jsoup.parse(body, url)
        android.util.Log.d(TAG, "GET $url -> $code, len=${body.length}, title='${doc.title()}'")
        if (code !in 200..299) throw HttpException(code, "")
        return doc
    }

    /** GET → сырой HTML-текст. */
    suspend fun getText(url: String): String {
        val (code, body) = fetchHtmlCleared(url)
        if (code !in 200..299) throw HttpException(code, "")
        return body
    }

    /** POST form-urlencoded → JSON-объект ответа (эндпоинты /ajax/... HDRezka). */
    suspend fun postJson(url: String, form: Map<String, String>): JSONObject {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val (code, body) = postCleared(baseRequest(url).post(fb.build()).build())
        if (code !in 200..299) throw HttpException(code, "")
        return JSONObject(body.ifEmpty { "{}" })
    }

    /** POST form-urlencoded → сырой HTML-текст (эндпоинт поиска HDRezka). */
    suspend fun postText(url: String, form: Map<String, String>): String {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val (code, body) = postCleared(baseRequest(url).post(fb.build()).build())
        if (code !in 200..299) throw HttpException(code, "")
        return body
    }

    /** Диагностический GET: возвращает читаемый отчёт для показа в UI (без обхода). */
    suspend fun getDebug(url: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(baseRequest(url).get().build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val doc = Jsoup.parse(body, url)
                val items = doc.select(".b-content__inline_item").size
                val head = body.take(200).replace("\n", " ").replace("\r", " ")
                "URL: $url\n" +
                    "HTTP: ${resp.code}\n" +
                    "Размер тела: ${body.length}\n" +
                    "title: ${doc.title()}\n" +
                    "карточек (.b-content__inline_item): $items\n\n" +
                    "Начало ответа:\n$head"
            }
        } catch (e: Exception) {
            "URL: $url\nИСКЛЮЧЕНИЕ: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ── агент на приставке (LAN, без анти-бота) ──────────────────────────────────

    /** POST JSON-тело → строка ответа. Для общения с агентом на приставке. */
    suspend fun postRawJson(url: String, json: String, timeoutSec: Long = 8): String =
        withContext(Dispatchers.IO) {
            val short = client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            short.newCall(baseRequest(url).post(reqBody).build()).execute().use { resp ->
                resp.body?.string() ?: ""
            }
        }

    /** GET к агенту с коротким таймаутом → строка ответа. */
    suspend fun getRaw(url: String, timeoutSec: Long = 5): String =
        withContext(Dispatchers.IO) {
            val short = client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            short.newCall(baseRequest(url).get().build()).execute().use { resp ->
                resp.body?.string() ?: ""
            }
        }
}

class HttpException(val code: Int, msg: String) : Exception("$code: $msg")
