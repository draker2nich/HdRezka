@file:OptIn(ExperimentalMaterial3Api::class)

package com.hdrezka.pult.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hdrezka.pult.core.CatalogItem
import com.hdrezka.pult.core.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── общие компоненты ──────────────────────────────────────────────────────────

@Composable
private fun AppScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    body: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = { actions() },
            )
        },
        content = body,
    )
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Загружает данные по key и рисует loading/error/успех. */
@Composable
private fun <T> AsyncContent(
    key: Any?,
    padding: PaddingValues,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    val state by produceState<Result<T>?>(initialValue = null, key) {
        value = runCatching { load() }
    }
    Box(Modifier.fillMaxSize().padding(padding)) {
        val s = state
        when {
            s == null -> CenteredBox { CircularProgressIndicator() }
            s.isFailure -> CenteredBox {
                Text(
                    "Ошибка: ${s.exceptionOrNull()?.message ?: "неизвестно"}",
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> content(s.getOrThrow())
        }
    }
}

@Composable
private fun ClickableRow(text: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, fontSize = 17.sp)
        if (subtitle != null) {
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
    HorizontalDivider()
}

// ── Home ──────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    val device = vm.selectedDevice
    AppScaffold(
        title = "HDRezka Пульт",
        onBack = null,
        actions = {
            IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Настройки")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            // Строка приставки
            OutlinedButton(
                onClick = { vm.navigate(Screen.Devices) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Cast, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (device != null) "Приставка: ${device.name}" else "Выбрать приставку")
            }

            Spacer(Modifier.height(16.dp))

            // Поиск
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по названию") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        if (query.isNotBlank())
                            vm.navigate(Screen.Results(title = "Поиск: $query", query = query))
                    }) { Icon(Icons.Filled.Search, contentDescription = "Искать") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank())
                        vm.navigate(Screen.Results(title = "Поиск: $query", query = query))
                }),
            )

            Spacer(Modifier.height(20.dp))

            Text("Каталог", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            com.hdrezka.pult.core.HdRezkaSearch.CATEGORIES.forEach { (path, label) ->
                ClickableRow(label) {
                    vm.navigate(Screen.Results(title = label, categoryPath = path))
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.navigate(Screen.Results(title = "Продолжить просмотр")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Продолжить просмотр") }
        }
    }
}

// ── Devices ─────────────────────────────────────────────────────────────────────

@Composable
fun DevicesScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.scanDevices() }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8123") }

    AppScaffold(
        title = "Приставки в сети",
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { vm.scanDevices() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(vm.devices) { d ->
                    ClickableRow(d.name, subtitle = "${d.host}:${d.port}  v${d.version}") {
                        vm.selectDevice(d)
                        vm.back()
                    }
                }
            }
            if (!vm.scanning && vm.devices.isEmpty()) {
                Text(
                    "Приставки не найдены. Убедись, что плагин запущен и агент включён, " +
                        "или задай адрес вручную.",
                    Modifier.padding(16.dp),
                )
            }

            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                Text("Указать вручную", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualHost, onValueChange = { manualHost = it },
                    label = { Text("IP приставки") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualPort, onValueChange = { manualPort = it },
                    label = { Text("Порт") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val port = manualPort.toIntOrNull() ?: 8123
                        if (manualHost.isNotBlank()) {
                            vm.selectManual(manualHost.trim(), port)
                            vm.back()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Использовать этот адрес") }
            }
        }
    }
}

// ── Results (поиск / категория / история) ────────────────────────────────────────

@Composable
fun ResultsScreen(vm: AppViewModel, screen: Screen.Results) {
    AppScaffold(title = screen.title, onBack = { vm.back() }) { padding ->
        when {
            screen.query == null && screen.categoryPath == null -> {
                // История
                val entries = remember { vm.history.list() }
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text("Вы ещё ничего не смотрели")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                        items(entries) { e ->
                            val sub = if (e.season != null && e.episode != null)
                                "S${e.season}E${e.episode} · ${e.translatorName ?: ""}" else e.translatorName
                            ClickableRow(e.title, subtitle = sub) {
                                vm.navigate(Screen.Details(e.url, e.title))
                            }
                        }
                    }
                }
            }
            else -> {
                AsyncContent(
                    key = screen,
                    padding = padding,
                    load = {
                        if (screen.query != null) vm.search(screen.query)
                        else vm.categoryFirstPage(screen.categoryPath!!)
                    },
                ) { items ->
                    if (items.isEmpty()) {
                        CenteredBox { Text("Ничего не найдено") }
                    } else {
                        ResultsList(items) { item ->
                            vm.navigate(Screen.Details(item.url, item.title))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsList(items: List<CatalogItem>, onClick: (CatalogItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(item) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.image != null) {
                    AsyncImage(
                        model = item.image,
                        contentDescription = null,
                        modifier = Modifier.width(60.dp).height(88.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 16.sp)
                    if (item.rating != null) {
                        Text("★ ${item.rating}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

// ── Details (озвучки) ──────────────────────────────────────────────────────────

@Composable
fun DetailsScreen(vm: AppViewModel, screen: Screen.Details) {
    AppScaffold(title = screen.titleHint.ifEmpty { "Загрузка…" }, onBack = { vm.back() }) { padding ->
        AsyncContent(key = screen.url, padding = padding, load = { vm.loadDetails(screen.url) }) { api ->
            val translators = remember(api) { api.translators() }
            val progress = remember(api) { vm.history.get(api.url) }
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        buildString {
                            append(api.name)
                            api.releaseYear?.let { append(" ($it)") }
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    api.rating?.let { Text("Рейтинг: $it", color = MaterialTheme.colorScheme.primary) }

                    val trId = progress?.translatorId
                    if (progress != null && trId != null) {
                        val trName = progress.translatorName ?: ""
                        val season = progress.season
                        val episode = progress.episode
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            vm.navigate(Screen.Quality(trId, trName, season, episode))
                        }) {
                            Text(
                                if (season != null && episode != null)
                                    "Продолжить: S${season}E${episode}"
                                else "Продолжить просмотр"
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text("Озвучки", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold)

                if (translators.isEmpty()) {
                    Text("Озвучки не найдены", Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(translators) { tr ->
                            val label = if (tr.premium) "${tr.name}  [PREMIUM]" else tr.name
                            ClickableRow(label) {
                                if (api.type == ContentType.SERIES)
                                    vm.navigate(Screen.Seasons(tr.id, tr.name))
                                else
                                    vm.navigate(Screen.Quality(tr.id, tr.name, null, null))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Seasons ─────────────────────────────────────────────────────────────────────

@Composable
fun SeasonsScreen(vm: AppViewModel, screen: Screen.Seasons) {
    AppScaffold(title = "Сезоны · ${screen.translatorName}", onBack = { vm.back() }) { padding ->
        AsyncContent(key = screen, padding = padding, load = { vm.seasons(screen.translatorId) }) { seasons ->
            if (seasons.isEmpty()) CenteredBox { Text("Сезоны не найдены") }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(seasons) { s ->
                    ClickableRow("Сезон $s") {
                        vm.navigate(Screen.Episodes(screen.translatorId, screen.translatorName, s))
                    }
                }
            }
        }
    }
}

// ── Episodes ────────────────────────────────────────────────────────────────────

@Composable
fun EpisodesScreen(vm: AppViewModel, screen: Screen.Episodes) {
    AppScaffold(title = "Сезон ${screen.season}", onBack = { vm.back() }) { padding ->
        AsyncContent(
            key = screen,
            padding = padding,
            load = { vm.episodes(screen.translatorId, screen.season) },
        ) { episodes ->
            if (episodes.isEmpty()) CenteredBox { Text("Эпизоды не найдены") }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(episodes) { (ep, text) ->
                    ClickableRow("Эпизод $ep", subtitle = text.ifBlank { null }) {
                        vm.navigate(
                            Screen.Quality(screen.translatorId, screen.translatorName, screen.season, ep)
                        )
                    }
                }
            }
        }
    }
}

// ── Quality (выбор качества + отправка на приставку) ──────────────────────────────

@Composable
fun QualityScreen(vm: AppViewModel, screen: Screen.Quality) {
    val scope = rememberCoroutineScope()
    var castError by remember { mutableStateOf<String?>(null) }
    var casting by remember { mutableStateOf(false) }

    fun doCast(url: String) {
        if (vm.selectedDevice == null) {
            vm.navigate(Screen.Devices)
            return
        }
        scope.launch {
            casting = true
            val err = vm.cast(screen.translatorId, screen.translatorName, screen.season, screen.episode, url)
            casting = false
            if (err == null) {
                vm.navigate(Screen.Remote)
            } else {
                castError = err
            }
        }
    }

    AppScaffold(title = "Качество · ${screen.translatorName}", onBack = { vm.back() }) { padding ->
        AsyncContent(
            key = screen,
            padding = padding,
            load = { vm.stream(screen.season, screen.episode, screen.translatorId) },
        ) { stream ->
            val qualities = stream.qualities
            // Автовыбор качества → сразу каст
            LaunchedEffect(stream) {
                if (vm.prefs.autoQuality && qualities.isNotEmpty() && vm.selectedDevice != null) {
                    val best = vm.pickQuality(qualities)
                    stream.forQuality(best)?.firstOrNull()?.let { doCast(it) }
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (casting) LinearProgressIndicator(Modifier.fillMaxWidth())
                castError?.let { Text("Ошибка: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (qualities.isEmpty()) {
                    CenteredBox { Text("Ссылки не найдены") }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(qualities) { q ->
                            ClickableRow(q) {
                                stream.forQuality(q)?.firstOrNull()?.let { doCast(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Remote (пульт) ───────────────────────────────────────────────────────────────

@Composable
fun RemoteScreen(vm: AppViewModel) {
    // Периодический опрос статуса
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshStatus()
            delay(1000)
        }
    }
    val status = vm.status

    AppScaffold(title = "Пульт", onBack = { vm.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                status?.title?.ifEmpty { "—" } ?: "Нет связи с приставкой",
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            if (status?.season != null && status.episode != null) {
                Text("S${status.season}E${status.episode} · ${status.translator}")
            }

            Spacer(Modifier.height(24.dp))

            val pos = status?.position ?: 0
            val dur = status?.duration ?: 0
            if (dur > 0) {
                LinearProgressIndicator(
                    progress = { pos.toFloat() / dur.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("${fmt(pos)} / ${fmt(dur)}")
            } else {
                Text(if (status?.playing == true) "Идёт воспроизведение" else "Ожидание…")
            }

            Spacer(Modifier.height(32.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.control("seek", -15) }) { Text("−15с") }
                Button(
                    onClick = { vm.control("toggle") },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (status?.paused == true) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "Пауза/Продолжить",
                    )
                }
                OutlinedButton(onClick = { vm.control("seek", 15) }) { Text("+15с") }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = { vm.control("voldown") }) { Text("Тише") }
                OutlinedButton(onClick = { vm.control("volup") }) { Text("Громче") }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { vm.control("stop"); vm.back() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Стоп")
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.back() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Выбрать другую серию / озвучку") }
        }
    }
}

private fun fmt(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Settings ────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var autoQuality by remember { mutableStateOf(vm.prefs.autoQuality) }
    var targetQuality by remember { mutableStateOf(vm.prefs.targetQuality.toString()) }
    var preferred by remember { mutableStateOf(vm.prefs.preferredTranslator) }
    var forcedDomain by remember { mutableStateOf(vm.prefs.forcedDomain) }
    var port by remember { mutableStateOf(vm.prefs.selectedPort.toString()) }

    AppScaffold(title = "Настройки", onBack = {
        // сохраняем при выходе
        vm.prefs.autoQuality = autoQuality
        vm.prefs.targetQuality = targetQuality.toIntOrNull() ?: 720
        vm.prefs.preferredTranslator = preferred
        vm.prefs.forcedDomain = forcedDomain.trim()
        vm.prefs.selectedPort = port.toIntOrNull() ?: 8123
        com.hdrezka.pult.core.Mirrors.forcedDomain = forcedDomain.trim()
        com.hdrezka.pult.core.Mirrors.resetCache()
        vm.back()
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Автовыбор качества", Modifier.weight(1f))
                Switch(checked = autoQuality, onCheckedChange = { autoQuality = it })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = targetQuality, onValueChange = { targetQuality = it },
                label = { Text("Целевое качество (напр. 720)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = preferred, onValueChange = { preferred = it },
                label = { Text("Предпочитаемая озвучка (часть названия)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = forcedDomain, onValueChange = { forcedDomain = it },
                label = { Text("Домен принудительно (пусто = авто)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text("Порт агента приставки") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
