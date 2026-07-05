package com.hdrezka.pult.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hdrezka.pult.agent.AgentClient
import com.hdrezka.pult.agent.AgentDevice
import com.hdrezka.pult.agent.Discovery
import com.hdrezka.pult.agent.PlaybackStatus
import com.hdrezka.pult.agent.PlayRequest
import com.hdrezka.pult.core.CatalogItem
import com.hdrezka.pult.core.ContentType
import com.hdrezka.pult.core.HdRezkaApi
import com.hdrezka.pult.core.HdRezkaSearch
import com.hdrezka.pult.core.Mirrors
import com.hdrezka.pult.core.StreamResult
import com.hdrezka.pult.data.History
import com.hdrezka.pult.data.Prefs
import kotlinx.coroutines.launch

/**
 * Единый ViewModel приложения: навигация (стек экранов), выбранная
 * приставка, кэш текущей страницы HDRezka и мост к агенту.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val history = History(app)

    // ── навигация ────────────────────────────────────────────────────────────
    val backStack: SnapshotStateList<Screen> = mutableListOf<Screen>(Screen.Home).toMutableStateList()
    val current: Screen get() = backStack.last()

    fun navigate(screen: Screen) { backStack.add(screen) }
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }
    fun goHome() { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    // ── устройства ─────────────────────────────────────────────────────────────
    var devices by mutableStateOf<List<AgentDevice>>(emptyList())
        private set
    var selectedDevice by mutableStateOf<AgentDevice?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set

    val agent: AgentClient?
        get() = selectedDevice?.let { AgentClient(it.host, it.port) }

    // ── текущая страница контента (переиспользуется между экранами) ──────────────
    var currentApi: HdRezkaApi? = null
        private set

    // ── статус воспроизведения ───────────────────────────────────────────────────
    var status by mutableStateOf<PlaybackStatus?>(null)
    private var lastPlayedUrl: String? = null

    init {
        Mirrors.forcedDomain = prefs.forcedDomain
        val host = prefs.selectedHost
        if (host.isNotEmpty()) {
            selectedDevice = AgentDevice(prefs.selectedName.ifEmpty { host }, host, prefs.selectedPort)
        }
    }

    fun scanDevices() {
        viewModelScope.launch {
            scanning = true
            devices = try {
                Discovery.discover(prefs.selectedPort.takeIf { it in 1..65535 } ?: 8123)
            } catch (_: Exception) {
                emptyList()
            }
            scanning = false
        }
    }

    fun selectDevice(d: AgentDevice) {
        selectedDevice = d
        prefs.selectedHost = d.host
        prefs.selectedPort = d.port
        prefs.selectedName = d.name
    }

    fun selectManual(host: String, port: Int) =
        selectDevice(AgentDevice(host, host, port))

    // ── загрузка контента (вызывается из экранов) ────────────────────────────────
    suspend fun search(query: String): List<CatalogItem> {
        val origin = Mirrors.resolve()
        // GET-страница поиска: проходит обход анти-бота через WebView и отдаёт
        // карточки с обложками (в отличие от POST-автокомплита).
        return HdRezkaSearch.searchPage(origin, query, 1)
    }

    suspend fun categoryFirstPage(path: String): List<CatalogItem> {
        val origin = Mirrors.resolve()
        return HdRezkaSearch.categoryPage(origin, path, 1)
    }

    /** Лента каталога для главного экрана (раздел + сортировка). */
    suspend fun catalog(path: String, filter: String): List<CatalogItem> {
        val origin = Mirrors.resolve()
        return HdRezkaSearch.catalog(origin, path, filter, 1)
    }

    /** Немедленно применить/сохранить принудительный домен. */
    fun setForcedDomain(domain: String) {
        prefs.forcedDomain = domain.trim()
        Mirrors.applyForced(domain)
    }

    /** Подробная диагностика: реально ходит на каталог и показывает, что пришло. */
    suspend fun diagnose(domain: String): String {
        Mirrors.applyForced(domain)
        val origin = Mirrors.resolve(forceRecheck = true)
        val url = origin.trimEnd('/') + "/films/?filter=last"
        return "origin: $origin\n" + com.hdrezka.pult.core.Net.getDebug(url)
    }

    /** Проверка связи с HDRezka. Возвращает человекочитаемый результат. */
    suspend fun testConnection(domain: String): String {
        val d = domain.trim()
        return if (d.isEmpty()) {
            val o = Mirrors.resolve(forceRecheck = true)
            if (Mirrors.probe(o)) "OK, авто-домен: $o" else "Авто-домен $o не отвечает"
        } else {
            if (Mirrors.probe(d)) "OK: $d отвечает" else "Не отвечает: $d"
        }
    }

    suspend fun loadDetails(url: String): HdRezkaApi {
        val fullUrl = if (url.startsWith("http")) url else Mirrors.resolve().trimEnd('/') + "/" + url.trimStart('/')
        val api = HdRezkaApi(fullUrl)
        api.load()
        currentApi = api
        return api
    }

    suspend fun seasons(trId: Int): List<Int> {
        val api = currentApi ?: throw IllegalStateException("страница не загружена")
        val info = api.seriesInfoFor(trId) ?: throw Exception("Нет данных перевода")
        return info.seasons.keys.sorted()
    }

    suspend fun episodes(trId: Int, season: Int): List<Pair<Int, String>> {
        val api = currentApi ?: throw IllegalStateException("страница не загружена")
        val info = api.seriesInfoFor(trId) ?: throw Exception("Нет данных перевода")
        val eps = info.episodes[season] ?: emptyMap()
        return eps.keys.sorted().map { it to (eps[it] ?: "") }
    }

    suspend fun stream(season: Int?, episode: Int?, trId: Int): StreamResult {
        val api = currentApi ?: throw IllegalStateException("страница не загружена")
        return api.getStream(season, episode, trId)
    }

    fun pickQuality(qualities: List<String>): String {
        val target = prefs.targetQuality
        val numbered = qualities.map { q -> (Regex("\\d+").find(q)?.value?.toIntOrNull() ?: 0) to q }
        val notAbove = numbered.filter { it.first in 1..target }
        return if (notAbove.isNotEmpty()) notAbove.maxByOrNull { it.first }!!.second
        else numbered.minByOrNull { it.first }!!.second
    }

    val isSeries: Boolean get() = currentApi?.type == ContentType.SERIES

    /**
     * Отправить поток на приставку и записать в историю. Возвращает ошибку
     * текстом, либо null при успехе.
     */
    suspend fun cast(
        trId: Int, trName: String, season: Int?, episode: Int?, streamUrl: String,
    ): String? {
        val api = currentApi ?: return "Страница не загружена"
        val ag = agent ?: return "Приставка не выбрана"
        val req = PlayRequest(
            url = streamUrl,
            title = api.name,
            referer = api.origin,
            userAgent = com.hdrezka.pult.core.Net.USER_AGENT,
            season = season,
            episode = episode,
            translator = trName,
        )
        val res = ag.play(req)
        return if (res.isSuccess) {
            lastPlayedUrl = api.url
            history.set(api.url, api.name, trId, trName, season, episode)
            null
        } else {
            res.exceptionOrNull()?.message ?: "Не удалось запустить на приставке"
        }
    }

    // ── пульт ─────────────────────────────────────────────────────────────────────
    fun refreshStatus() {
        val ag = agent ?: return
        viewModelScope.launch {
            val s = ag.status()
            status = s
            // синхронизируем позицию в историю для «продолжить»
            val url = lastPlayedUrl
            val pos = s?.position
            if (url != null && pos != null && s.playing) {
                history.updatePosition(url, pos)
            }
        }
    }

    fun control(cmd: String, value: Int? = null) {
        val ag = agent ?: return
        viewModelScope.launch { ag.control(cmd, value) }
    }

    /** «Продолжить просмотр»: открыть карточку и сразу перейти к нужному месту. */
    fun continueWatching(url: String, onLoaded: (HdRezkaApi) -> Unit) {
        viewModelScope.launch {
            runCatching { loadDetails(url) }.onSuccess(onLoaded)
        }
    }
}
