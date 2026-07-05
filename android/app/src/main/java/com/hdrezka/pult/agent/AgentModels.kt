package com.hdrezka.pult.agent

/** Приставка-агент, найденная в сети (ответ на discovery / ping). */
data class AgentDevice(
    val name: String,
    val host: String,
    val port: Int,
    val version: String = "",
)

/** Снимок состояния воспроизведения (ответ GET /status). */
data class PlaybackStatus(
    val playing: Boolean = false,
    val paused: Boolean = false,
    val position: Int? = null,   // сек
    val duration: Int? = null,   // сек
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val translator: String = "",
    val url: String = "",
)

/** Запрос на запуск потока (POST /play). */
data class PlayRequest(
    val url: String,
    val title: String,
    val referer: String,
    val userAgent: String,
    val season: Int? = null,
    val episode: Int? = null,
    val translator: String = "",
    val engine: String? = null,
)
