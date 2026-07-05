package com.hdrezka.pult.agent

import com.hdrezka.pult.core.Net
import org.json.JSONObject

/**
 * Клиент HTTP-агента приставки. Полностью соответствует AGENT_API.md:
 * /ping, /status, /play, /control, /osd. Телефон уже распарсил ссылку —
 * сюда уходит готовый URL и команды пульта.
 */
class AgentClient(private val host: String, private val port: Int) {

    private val base = "http://$host:$port"

    suspend fun ping(): AgentDevice? = runCatching {
        val o = JSONObject(Net.getRaw("$base/ping"))
        if (!o.optBoolean("ok")) return null
        AgentDevice(
            name = o.optString("name", host),
            host = host,
            port = port,
            version = o.optString("version", ""),
        )
    }.getOrNull()

    suspend fun status(): PlaybackStatus? = runCatching {
        val o = JSONObject(Net.getRaw("$base/status"))
        if (!o.optBoolean("ok")) return null
        PlaybackStatus(
            playing = o.optBoolean("playing"),
            paused = o.optBoolean("paused"),
            position = o.optIntOrNull("position"),
            duration = o.optIntOrNull("duration"),
            title = o.optString("title", ""),
            season = o.optIntOrNull("season"),
            episode = o.optIntOrNull("episode"),
            translator = o.optString("translator", ""),
            url = o.optString("url", ""),
        )
    }.getOrNull()

    suspend fun play(req: PlayRequest): Result<Unit> = call("$base/play", JSONObject().apply {
        put("url", req.url)
        put("title", req.title)
        put("referer", req.referer)
        put("user_agent", req.userAgent)
        req.season?.let { put("season", it) }
        req.episode?.let { put("episode", it) }
        if (req.translator.isNotEmpty()) put("translator", req.translator)
        req.engine?.let { put("engine", it) }
    })

    suspend fun control(cmd: String, value: Int? = null): Result<Unit> =
        call("$base/control", JSONObject().apply {
            put("cmd", cmd)
            value?.let { put("value", it) }
        })

    suspend fun osd(text: String, timeout: Int = 4): Result<Unit> =
        call("$base/osd", JSONObject().apply {
            put("text", text)
            put("timeout", timeout)
        })

    private suspend fun call(url: String, body: JSONObject): Result<Unit> = runCatching {
        val resp = Net.postRawJson(url, body.toString())
        val o = JSONObject(resp.ifEmpty { "{}" })
        if (!o.optBoolean("ok")) {
            throw Exception(o.optString("error", "агент вернул ошибку"))
        }
    }
}

/** org.json возвращает 0 для отсутствующих int — нам нужен именно null. */
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)
