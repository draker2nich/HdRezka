package com.hdrezka.pult.core

import android.util.Base64
import org.jsoup.nodes.Document

/**
 * Парсер страницы фильма/сериала HDRezka — порт api.py на Jsoup.
 * Один экземпляр = одна страница; данные страницы и переводов кэшируются,
 * чтобы переходы карточка→сезоны→серии→поток не били в сеть повторно
 * (та же экономия, что делал плагин на приставке).
 */
class HdRezkaApi(rawUrl: String) {

    val url: String = if (rawUrl.contains(".html")) {
        rawUrl.substringBefore(".html") + ".html"
    } else {
        rawUrl.trimEnd('/')
    }

    val origin: String = run {
        val m = Regex("^(https?://[^/]+)").find(rawUrl)
        m?.groupValues?.get(1) ?: Mirrors.peek()
    }

    private var doc: Document? = null
    private var pageHtml: String = ""
    private val seriesCache = HashMap<Int, SeriesInfo?>()
    private var translatorsCache: List<Translator>? = null

    private fun soup(): Document = doc ?: throw IllegalStateException("not loaded")

    /** Загружает страницу. Бросает при Sign In / Verify / сетевой ошибке. */
    suspend fun load() {
        val document = Net.getDocument(url)
        val title = document.title()
        if (title == "Sign In") throw Exception("Требуется вход в аккаунт")
        if (title == "Verify") throw Exception("Требуется пройти капчу")
        doc = document
        pageHtml = document.html()
    }

    // ── базовые поля ─────────────────────────────────────────────────────────

    val name: String by lazy {
        soup().selectFirst(".b-post__title")?.text()?.split("/")?.firstOrNull()?.trim() ?: "—"
    }

    val id: Int by lazy {
        val fromPost = soup().selectFirst("#post_id")?.attr("value")
        val fromIssue = soup().selectFirst("#send-video-issue")?.attr("data-id")
        val fromFav = soup().selectFirst("#user-favorites-holder")?.attr("data-post_id")
        val fromUrl = url.substringAfterLast("/").substringBefore("-")
        (fromPost?.toIntOrNull()
            ?: fromIssue?.toIntOrNull()
            ?: fromFav?.toIntOrNull()
            ?: fromUrl.toIntOrNull()
            ?: 0)
    }

    val type: ContentType by lazy {
        when (soup().selectFirst("meta[property=og:type]")?.attr("content")) {
            "video.tv_series" -> ContentType.SERIES
            "video.movie" -> ContentType.MOVIE
            else -> ContentType.OTHER
        }
    }

    val releaseYear: Int? by lazy {
        val href = soup().selectFirst(".b-content__main .b-post__info a[href*=/year/]")?.attr("href")
        href?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
    }

    val rating: Double? by lazy {
        val wrap = soup().selectFirst(".b-post__rating") ?: return@lazy null
        wrap.selectFirst(".num")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()
    }

    val posterUrl: String? by lazy {
        soup().selectFirst(".b-sidecover img")?.attr("src")
    }

    val description: String by lazy {
        soup().selectFirst(".b-post__description_text")?.text()?.trim() ?: ""
    }

    // ── переводы ───────────────────────────────────────────────────────────────

    fun translators(): List<Translator> {
        translatorsCache?.let { return it }
        val result = mutableListOf<Translator>()
        val list = soup().selectFirst("#translators-list")
        if (list != null) {
            for (child in list.children()) {
                val trId = child.attr("data-translator_id").toIntOrNull() ?: continue
                var trName = child.text().trim()
                val premium = child.classNames().contains("b-prem_translator")
                val img = child.selectFirst("img")
                val lang = img?.attr("title")
                if (!lang.isNullOrEmpty() && !trName.contains(lang)) {
                    trName = "$trName ($lang)"
                }
                result.add(Translator(trId, trName, premium))
            }
        }
        if (result.isEmpty()) {
            // авто-детект единственного перевода (порт getTranslationID/Name)
            val autoId = detectSingleTranslatorId()
            if (autoId != null) {
                result.add(Translator(autoId, detectSingleTranslatorName() ?: "Оригинал", false))
            }
        }
        translatorsCache = result
        return result
    }

    private fun detectSingleTranslatorId(): Int? {
        val event = if (type == ContentType.SERIES) "initCDNSeriesEvents" else "initCDNMoviesEvents"
        val marker = "sof.tv.$event"
        if (!pageHtml.contains(marker)) return null
        val tail = pageHtml.substringAfterLast(marker).substringBefore("{")
        val parts = tail.split(",")
        return parts.getOrNull(1)?.trim()?.toIntOrNull()
    }

    private fun detectSingleTranslatorName(): String? {
        val info = soup().selectFirst(".b-post__info") ?: return null
        for (tr in info.select("tr")) {
            if (tr.text().contains("переводе")) {
                return tr.select("td").lastOrNull()?.text()?.trim()
            }
        }
        return null
    }

    // ── сезоны/эпизоды одного перевода ──────────────────────────────────────────

    suspend fun seriesInfoFor(trId: Int): SeriesInfo? {
        if (seriesCache.containsKey(trId)) return seriesCache[trId]
        val resp = Net.postJson(
            "$origin/ajax/get_cdn_series/",
            mapOf(
                "id" to id.toString(),
                "translator_id" to trId.toString(),
                "action" to "get_episodes",
            )
        )
        var result: SeriesInfo? = null
        if (resp.optBoolean("success", false)) {
            val seasons = parseSeasons(resp.optString("seasons"))
            val episodes = parseEpisodes(resp.optString("episodes"))
            val trName = translators().firstOrNull { it.id == trId }?.name ?: ""
            val premium = translators().firstOrNull { it.id == trId }?.premium ?: false
            result = SeriesInfo(trName, premium, seasons, episodes)
        }
        seriesCache[trId] = result
        return result
    }

    private fun parseSeasons(html: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        org.jsoup.Jsoup.parse(html).select(".b-simple_season__item").forEach {
            val sid = it.attr("data-tab_id").toIntOrNull() ?: return@forEach
            out[sid] = it.text()
        }
        return out
    }

    private fun parseEpisodes(html: String): Map<Int, Map<Int, String>> {
        val out = LinkedHashMap<Int, LinkedHashMap<Int, String>>()
        org.jsoup.Jsoup.parse(html).select(".b-simple_episode__item").forEach {
            val sid = it.attr("data-season_id").toIntOrNull() ?: return@forEach
            val eid = it.attr("data-episode_id").toIntOrNull() ?: return@forEach
            out.getOrPut(sid) { LinkedHashMap() }[eid] = it.text()
        }
        return out
    }

    // ── получение потока ─────────────────────────────────────────────────────────

    suspend fun getStream(season: Int?, episode: Int?, translatorId: Int): StreamResult {
        return if (type == ContentType.SERIES) {
            require(season != null && episode != null) { "Для сериала нужны сезон и эпизод" }
            val info = seriesInfoFor(translatorId) ?: throw Exception("Не удалось получить данные перевода")
            val eps = info.episodes[season] ?: throw Exception("Сезон $season не найден")
            if (!eps.containsKey(episode)) throw Exception("Эпизод $episode в сезоне $season не найден")
            makeStreamRequest(
                mapOf(
                    "id" to id.toString(),
                    "translator_id" to translatorId.toString(),
                    "season" to season.toString(),
                    "episode" to episode.toString(),
                    "action" to "get_stream",
                )
            )
        } else {
            makeStreamRequest(
                mapOf(
                    "id" to id.toString(),
                    "translator_id" to translatorId.toString(),
                    "action" to "get_movie",
                )
            )
        }
    }

    private suspend fun makeStreamRequest(data: Map<String, String>): StreamResult {
        val resp = Net.postJson("$origin/ajax/get_cdn_series/", data)
        val urlField = resp.optString("url")
        if (!resp.optBoolean("success", false) || urlField.isNullOrEmpty()) {
            throw Exception("Не удалось получить ссылку на поток")
        }
        val decoded = clearTrash(urlField)
        val videos = LinkedHashMap<String, MutableList<String>>()
        for (chunk in decoded.split(",")) {
            if (!chunk.contains("[")) continue
            val afterBracket = chunk.substringAfter("[")
            val quality = stripHtml(afterBracket.substringBefore("]")).trim()
            val rest = afterBracket.substringAfter("]")
            val links = rest.split(" or ").filter { it.endsWith(".mp4") }
            for (link in links) {
                videos.getOrPut(quality) { mutableListOf() }.add(link.trim())
            }
        }
        return StreamResult(videos)
    }

    companion object {
        private val HTML_TAG = Regex("<[^>]*>")
        private fun stripHtml(s: String) = HTML_TAG.replace(s, "")

        // Набор "мусорных" base64-кодов, которыми HDRezka зашумляет ссылку.
        private val TRASH_CODES: List<String> = buildList {
            val symbols = listOf("@", "#", "!", "^", "$")
            for (len in 2..3) {
                combos(symbols, len).forEach { combo ->
                    add(Base64.encodeToString(combo.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                }
            }
        }

        private fun combos(symbols: List<String>, len: Int): List<String> {
            if (len == 0) return listOf("")
            val smaller = combos(symbols, len - 1)
            val out = ArrayList<String>(symbols.size * smaller.size)
            for (s in symbols) for (tail in smaller) out.add(s + tail)
            return out
        }

        /** Раскодировать зашумлённую ссылку HDRezka — порт clearTrash из api.py. */
        fun clearTrash(data: String): String {
            var s = data.replace("#h", "").split("//_//").joinToString("")
            for (code in TRASH_CODES) s = s.replace(code, "")
            return try {
                // выравниваем паддинг под требования Android-декодера
                val rem = s.length % 4
                if (rem == 2) s += "=="
                else if (rem == 3) s += "="
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                s
            }
        }
    }
}
