package com.hdrezka.pult.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
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
 * Как и в плагине (там был requests verify=False), мы терпимо относимся к
 * сертификатам зеркал HDRezka: часть зеркал живёт на кривых/самоподписанных
 * сертификатах, и без этого скрапинг просто падает. Это персональный
 * LAN-инструмент, риск осознанный и ограничен доменами HDRezka.
 */
object Net {

    const val TAG = "HdRezkaPult"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36"

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

    /** GET → распарсенный Jsoup Document. Выполняется на IO-потоке. */
    suspend fun getDocument(url: String): Document = withContext(Dispatchers.IO) {
        client.newCall(baseRequest(url).get().build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val doc = Jsoup.parse(body, url)
            android.util.Log.d(
                TAG,
                "GET $url -> ${resp.code}, len=${body.length}, title='${doc.title()}'"
            )
            if (!resp.isSuccessful) throw HttpException(resp.code, resp.message)
            doc
        }
    }

    /** Диагностический GET: возвращает читаемый отчёт для показа в UI. */
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

    /** GET → сырой HTML-текст. */
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(baseRequest(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, resp.message)
            resp.body?.string() ?: ""
        }
    }

    /** POST form-urlencoded → JSON-объект ответа (эндпоинты /ajax/... HDRezka). */
    suspend fun postJson(url: String, form: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            client.newCall(baseRequest(url).post(fb.build()).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, resp.message)
                JSONObject(resp.body?.string() ?: "{}")
            }
        }

    /** POST form-urlencoded → сырой HTML-текст (эндпоинт поиска HDRezka). */
    suspend fun postText(url: String, form: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            client.newCall(baseRequest(url).post(fb.build()).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, resp.message)
                resp.body?.string() ?: ""
            }
        }

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
