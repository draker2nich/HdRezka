package com.hdrezka.pult.core

/**
 * Поиск и листинг категорий HDRezka — порт search.py и _fetchCategoryPage.
 * В отличие от приставки, где каталог был лишним, на телефоне это основной
 * способ навигации, поэтому категории тут остаются.
 */
object HdRezkaSearch {

    /** Разделы каталога (путь → подпись). "" = главная («Все»). */
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "" to "Все",
        "/films/" to "Фильмы",
        "/series/" to "Сериалы",
        "/cartoons/" to "Мультфильмы",
        "/animation/" to "Аниме",
    )

    /** Вкладки сортировки (значение ?filter= → подпись). */
    val FILTERS: List<Pair<String, String>> = listOf(
        "last" to "Последние",
        "popular" to "Популярные",
        "soon" to "В ожидании",
    )

    /**
     * Лента каталога: раздел + сортировка + страница.
     * categoryPath: "" (главная/все) или "/films/" и т.п.
     */
    suspend fun catalog(
        origin: String, categoryPath: String, filter: String, page: Int,
    ): List<CatalogItem> {
        val base = origin.trimEnd('/') + (categoryPath.ifEmpty { "/" })
        val paged = if (page > 1) base.trimEnd('/') + "/page/$page/" else base
        val sep = if (paged.contains("?")) "&" else "?"
        val url = if (filter.isNotEmpty()) "$paged${sep}filter=$filter" else paged
        return parseInlineItems(Net.getDocument(url))
    }

    /** Быстрый поиск по названию (autocomplete-эндпоинт). */
    suspend fun fastSearch(origin: String, query: String): List<CatalogItem> {
        // Как в плагине: POST q → тело ответа это HTML-фрагмент со списком.
        val html = Net.postText("$origin/engine/ajax/search.php", mapOf("q" to query))
        val doc = org.jsoup.Jsoup.parse(html)
        val out = mutableListOf<CatalogItem>()
        for (li in doc.select(".b-search__section_list li")) {
            val title = li.selectFirst("span.enty")?.text()?.trim() ?: continue
            val url = li.selectFirst("a")?.attr("href") ?: continue
            val rating = li.selectFirst("span.rating")?.text()?.trim()?.toDoubleOrNull()
            out.add(CatalogItem(title = title, url = url, rating = rating))
        }
        return out
    }

    /** Полностраничный поиск (используется для скролла результатов). */
    suspend fun searchPage(origin: String, query: String, page: Int): List<CatalogItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$origin/search/?do=search&subaction=search&q=$encoded&page=$page"
        val doc = Net.getDocument(url)
        return parseInlineItems(doc)
    }

    /** Листинг категории (Фильмы/Сериалы/...) постранично. */
    suspend fun categoryPage(origin: String, path: String, page: Int): List<CatalogItem> {
        val base = origin.trimEnd('/') + path
        val url = if (page > 1) "${base}page/$page/" else base
        val doc = Net.getDocument(url)
        return parseInlineItems(doc)
    }

    private val YEAR_RE = Regex("\\b(19|20)\\d{2}\\b")

    private fun parseInlineItems(doc: org.jsoup.nodes.Document): List<CatalogItem> {
        val out = mutableListOf<CatalogItem>()
        for (item in doc.select(".b-content__inline_item")) {
            val linkBox = item.selectFirst(".b-content__inline_item-link")
            val a = linkBox?.selectFirst("a") ?: continue
            val url = a.attr("href").ifEmpty { item.attr("data-url") }
            if (url.isEmpty()) continue
            val title = a.text().trim()
            val img = item.selectFirst(".b-content__inline_item-cover img")?.attr("src")
            val cat = item.selectFirst(".cat")?.classNames()?.firstOrNull { it != "cat" }
            // вторая строка карточки: "2024, США, Боевик" — оттуда год и инфо
            val infoText = linkBox.selectFirst("div")?.text()?.trim()
            val year = infoText?.let { YEAR_RE.find(it)?.value }
            out.add(
                CatalogItem(
                    title = title, url = url, image = img,
                    category = cat, year = year, info = infoText,
                )
            )
        }
        return out
    }
}
