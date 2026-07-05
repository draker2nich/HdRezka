package com.hdrezka.pult.core

/** Тип контента со страницы (meta og:type). */
enum class ContentType { SERIES, MOVIE, OTHER }

/** Элемент выдачи поиска или каталога. */
data class CatalogItem(
    val title: String,
    val url: String,
    val rating: Double? = null,
    val image: String? = null,
    val category: String? = null,
    val year: String? = null,
    val info: String? = null,
)

/** Озвучка/перевод. */
data class Translator(
    val id: Int,
    val name: String,
    val premium: Boolean = false,
)

/** Данные одного перевода сериала: сезоны и эпизоды. */
data class SeriesInfo(
    val translatorName: String,
    val premium: Boolean,
    /** season -> подпись сезона */
    val seasons: Map<Int, String>,
    /** season -> (episode -> подпись эпизода) */
    val episodes: Map<Int, Map<Int, String>>,
)

/** Готовый поток: качество -> список зеркальных ссылок (.mp4). */
data class StreamResult(
    val videos: LinkedHashMap<String, MutableList<String>>,
) {
    /** Ссылки для конкретного качества: точное совпадение → иначе кратчайшее. */
    fun forQuality(q: String): List<String>? {
        videos[q]?.let { return it }
        val match = videos.keys.filter { it.contains(q) }.minByOrNull { it.length }
        return match?.let { videos[it] }
    }

    val qualities: List<String> get() = videos.keys.toList()
}
