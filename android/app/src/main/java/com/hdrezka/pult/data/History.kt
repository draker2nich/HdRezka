package com.hdrezka.pult.data

import android.content.Context
import org.json.JSONObject

/** Один элемент «Продолжить просмотр». */
data class HistoryEntry(
    val url: String,
    val title: String,
    val translatorId: Int?,
    val translatorName: String?,
    val season: Int?,
    val episode: Int?,
    val position: Int?,   // сек, докуда досмотрели (приходит из /status)
    val updated: Long,
)

/**
 * Персистентная история просмотра — порт history.py. Помним озвучку/сезон/
 * серию и последнюю позицию, чтобы «Продолжить» слало сразу play + seekto.
 */
class History(context: Context) {
    private val sp = context.getSharedPreferences("hdrezka_history", Context.MODE_PRIVATE)
    private val maxItems = 100

    private fun canon(url: String) = url.substringBefore(".html")

    private fun loadAll(): MutableMap<String, JSONObject> {
        val raw = sp.getString("data", "{}") ?: "{}"
        val root = JSONObject(raw)
        val out = LinkedHashMap<String, JSONObject>()
        for (key in root.keys()) out[key] = root.getJSONObject(key)
        return out
    }

    private fun persist(map: Map<String, JSONObject>) {
        // ограничиваем размер, свежие сверху
        val ordered = map.entries.sortedByDescending { it.value.optLong("updated") }
            .take(maxItems)
        val root = JSONObject()
        for (e in ordered) root.put(e.key, e.value)
        sp.edit().putString("data", root.toString()).apply()
    }

    fun get(url: String): HistoryEntry? {
        val o = loadAll()[canon(url)] ?: return null
        return o.toEntry()
    }

    fun set(
        url: String, title: String,
        translatorId: Int?, translatorName: String?,
        season: Int?, episode: Int?, position: Int? = null,
    ) {
        val map = loadAll()
        map[canon(url)] = JSONObject().apply {
            put("url", url)
            put("title", title)
            translatorId?.let { put("translator_id", it) }
            translatorName?.let { put("translator_name", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            position?.let { put("position", it) }
            put("updated", System.currentTimeMillis())
        }
        persist(map)
    }

    /** Обновить только позицию (частые апдейты из /status), не трогая остальное. */
    fun updatePosition(url: String, position: Int) {
        val map = loadAll()
        val o = map[canon(url)] ?: return
        o.put("position", position)
        o.put("updated", System.currentTimeMillis())
        map[canon(url)] = o
        persist(map)
    }

    fun remove(url: String) {
        val map = loadAll()
        map.remove(canon(url))
        persist(map)
    }

    fun list(): List<HistoryEntry> =
        loadAll().values.map { it.toEntry() }.sortedByDescending { it.updated }

    private fun JSONObject.toEntry() = HistoryEntry(
        url = optString("url"),
        title = optString("title"),
        translatorId = if (has("translator_id")) optInt("translator_id") else null,
        translatorName = if (has("translator_name")) optString("translator_name") else null,
        season = if (has("season")) optInt("season") else null,
        episode = if (has("episode")) optInt("episode") else null,
        position = if (has("position")) optInt("position") else null,
        updated = optLong("updated"),
    )
}
