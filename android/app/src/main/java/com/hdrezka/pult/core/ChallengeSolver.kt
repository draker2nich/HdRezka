package com.hdrezka.pult.core

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Обход анти-бота Anubis (JS proof-of-work «Проверяем, что вы не бот!»).
 *
 * OkHttp не исполняет JS, поэтому страницу открываем в скрытом WebView: он сам
 * считает PoW и оказывается на реальной странице. Для GET-страниц забираем
 * готовый HTML прямо из WebView (гарантированно проходит проверку — тот же
 * отпечаток запроса), а полученные cookie копируем в OkHttp-jar, чтобы
 * последующие AJAX-POST (серии/потоки/поиск) тоже проходили.
 */
object ChallengeSolver {

    private val mutex = Mutex()
    private const val TIMEOUT_MS = 45000L

    /** Прогреть cookie (для POST): открыть origin в WebView, дождаться прохода. */
    suspend fun solve(origin: String): Boolean = loadHtml(origin) != null

    /** Загрузить URL через WebView и вернуть HTML реальной страницы (или null). */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadHtml(url: String): String? {
        val ctx = Net.appContext ?: return null
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(ctx)
                    var finished = false
                    val handler = Handler(Looper.getMainLooper())
                    lateinit var timeout: Runnable

                    fun finish(html: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacks(timeout)
                        CookieManager.getInstance().getCookie(url)?.let { Net.injectCookies(url, it) }
                        try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(html)
                    }

                    timeout = Runnable {
                        android.util.Log.d(Net.TAG, "challenge timeout on $url")
                        finish(null)
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = Net.USER_AGENT
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            val title = view.title ?: ""
                            val stillChallenge = title.isBlank() ||
                                title.contains("бот", true) || title.contains("bot", true) ||
                                title.contains("moment", true) || title.contains("Проверя", true) ||
                                title.contains("Attention", true) || title.contains("DDoS", true)
                            android.util.Log.d(
                                Net.TAG,
                                "solver onPageFinished url=$pageUrl title='$title' stillChallenge=$stillChallenge"
                            )
                            if (stillChallenge) return
                            // Реальная страница — забираем её HTML.
                            view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                                val html = try {
                                    // value приходит как JSON-строка ("...<...")
                                    org.json.JSONArray("[$value]").getString(0)
                                } catch (_: Exception) {
                                    null
                                }
                                finish(html)
                            }
                        }
                    }
                    handler.postDelayed(timeout, TIMEOUT_MS)
                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        handler.post {
                            if (!finished) {
                                finished = true
                                try { webView.destroy() } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }
}
