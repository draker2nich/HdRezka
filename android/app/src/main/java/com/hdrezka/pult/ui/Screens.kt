@file:OptIn(ExperimentalMaterial3Api::class)

package com.hdrezka.pult.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hdrezka.pult.core.CatalogItem
import com.hdrezka.pult.core.ContentType
import com.hdrezka.pult.core.HdRezkaSearch
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
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
private fun CenteredBox(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

/** Загружает данные по key и рисует loading/error/успех в переданной области. */
@Composable
private fun <T> AsyncContent(
    key: Any?,
    modifier: Modifier,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    val state by produceState<Result<T>?>(initialValue = null, key) {
        value = runCatching { load() }
    }
    Box(modifier) {
        val s = state
        when {
            s == null -> CenteredBox { CircularProgressIndicator() }
            s.isFailure -> CenteredBox {
                Text(
                    "Ошибка загрузки:\n${s.exceptionOrNull()?.message ?: "неизвестно"}\n\n" +
                        "Проверь домен в настройках.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> content(s.getOrThrow())
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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

/** Постер-ячейка каталога: обложка + бейдж года + название. */
@Composable
private fun PosterCell(item: CatalogItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (item.image != null) {
                AsyncImage(
                    model = item.image,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.year != null) {
                Text(
                    item.year,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.title,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun PosterGrid(items: List<CatalogItem>, modifier: Modifier, onClick: (CatalogItem) -> Unit) {
    if (items.isEmpty()) {
        CenteredBox(modifier) { Text("Ничего не найдено") }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
    ) {
        items(items) { item ->
            PosterCell(item) { onClick(item) }
        }
    }
}

// ── Home (каталог постеров + вкладки + чипсы + нижняя навигация) ─────────────────

@Composable
fun HomeScreen(vm: AppViewModel) {
    var catPath by remember { mutableStateOf("") }   // "" = Все
    var filter by remember { mutableStateOf("last") }
    val device = vm.selectedDevice

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true, onClick = {},
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Каталог") },
                )
                NavigationBarItem(
                    selected = false, onClick = { vm.navigate(Screen.Results(title = "", query = "")) },
                    icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Поиск") },
                )
                NavigationBarItem(
                    selected = false, onClick = { vm.navigate(Screen.Results(title = "Продолжить просмотр")) },
                    icon = { Icon(Icons.Filled.History, null) }, label = { Text("История") },
                )
                NavigationBarItem(
                    selected = false, onClick = { vm.navigate(Screen.Devices) },
                    icon = { Icon(Icons.Filled.Cast, null) }, label = { Text("Приставка") },
                )
                NavigationBarItem(
                    selected = false, onClick = { vm.navigate(Screen.Settings) },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Ещё") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // строка приставки
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Cast, null, tint = if (device != null) MaterialTheme.colorScheme.primary else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (device != null) device.name else "Приставка не выбрана",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            // вкладки сортировки
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                HdRezkaSearch.FILTERS.forEach { (value, label) ->
                    Chip(label, selected = filter == value) { filter = value }
                }
            }
            // чипсы разделов
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp)) {
                HdRezkaSearch.CATEGORIES.forEach { (path, label) ->
                    Chip(label, selected = catPath == path) { catPath = path }
                }
            }

            AsyncContent(
                key = catPath to filter,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                load = { vm.catalog(catPath, filter) },
            ) { items ->
                PosterGrid(items, Modifier.fillMaxSize()) { item ->
                    vm.navigate(Screen.Details(item.url, item.title))
                }
            }
        }
    }
}

// ── Devices ─────────────────────────────────────────────────────────────────────

@Composable
fun DevicesScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.scanDevices() }
    var manualHost by remember { mutableStateOf(vm.selectedDevice?.host ?: "") }
    var manualPort by remember { mutableStateOf((vm.selectedDevice?.port ?: 8123).toString()) }

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

// ── Results (поиск / история) ────────────────────────────────────────────────────

@Composable
fun ResultsScreen(vm: AppViewModel, screen: Screen.Results) {
    // История
    if (screen.query == null && screen.categoryPath == null) {
        AppScaffold(title = "Продолжить просмотр", onBack = { vm.back() }) { padding ->
            val entries = remember { vm.history.list() }
            if (entries.isEmpty()) {
                CenteredBox(Modifier.fillMaxSize().padding(padding)) { Text("Вы ещё ничего не смотрели") }
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
        return
    }

    // Поиск с полем ввода
    var query by remember { mutableStateOf(screen.query ?: "") }
    var submitted by remember { mutableStateOf(screen.query?.takeIf { it.isNotBlank() }) }

    AppScaffold(title = "Поиск", onBack = { vm.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Название фильма или сериала") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                trailingIcon = {
                    IconButton(onClick = { if (query.isNotBlank()) submitted = query }) {
                        Icon(Icons.Filled.Search, contentDescription = "Искать")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitted = query }),
            )
            val q = submitted
            if (q == null) {
                CenteredBox(Modifier.fillMaxSize()) { Text("Введите запрос") }
            } else {
                AsyncContent(
                    key = q,
                    modifier = Modifier.fillMaxSize(),
                    load = { vm.search(q) },
                ) { items ->
                    PosterGrid(items, Modifier.fillMaxSize()) { item ->
                        vm.navigate(Screen.Details(item.url, item.title))
                    }
                }
            }
        }
    }
}

// ── Details (озвучки) ──────────────────────────────────────────────────────────

@Composable
fun DetailsScreen(vm: AppViewModel, screen: Screen.Details) {
    AppScaffold(title = screen.titleHint.ifEmpty { "Загрузка…" }, onBack = { vm.back() }) { padding ->
        AsyncContent(
            key = screen.url,
            modifier = Modifier.fillMaxSize().padding(padding),
            load = { vm.loadDetails(screen.url) },
        ) { api ->
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
                        Button(onClick = { vm.navigate(Screen.Quality(trId, trName, season, episode)) }) {
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
        AsyncContent(
            key = screen,
            modifier = Modifier.fillMaxSize().padding(padding),
            load = { vm.seasons(screen.translatorId) },
        ) { seasons ->
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
            modifier = Modifier.fillMaxSize().padding(padding),
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
            if (err == null) vm.navigate(Screen.Remote) else castError = err
        }
    }

    AppScaffold(title = "Качество · ${screen.translatorName}", onBack = { vm.back() }) { padding ->
        AsyncContent(
            key = screen,
            modifier = Modifier.fillMaxSize().padding(padding),
            load = { vm.stream(screen.season, screen.episode, screen.translatorId) },
        ) { stream ->
            val qualities = stream.qualities
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
                Button(onClick = { vm.control("toggle") }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (status?.paused == true) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "Пауза/Продолжить",
                    )
                }
                OutlinedButton(onClick = { vm.control("seek", 15) }) { Text("+15с") }
            }

            Spacer(Modifier.height(24.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { vm.control("voldown") }) { Text("Тише") }
                OutlinedButton(onClick = { vm.control("volup") }) { Text("Громче") }
            }

            Spacer(Modifier.height(24.dp))

            Button(onClick = { vm.control("stop"); vm.back() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Стоп")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать другую серию / озвучку")
            }
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
    val scope = rememberCoroutineScope()
    var autoQuality by remember { mutableStateOf(vm.prefs.autoQuality) }
    var targetQuality by remember { mutableStateOf(vm.prefs.targetQuality.toString()) }
    var preferred by remember { mutableStateOf(vm.prefs.preferredTranslator) }
    var forcedDomain by remember { mutableStateOf(vm.prefs.forcedDomain) }
    var port by remember { mutableStateOf(vm.prefs.selectedPort.toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AppScaffold(title = "Настройки", onBack = {
        // сохраняем при выходе (плюс домен сохраняется сразу по кнопке)
        vm.prefs.autoQuality = autoQuality
        vm.prefs.targetQuality = targetQuality.toIntOrNull() ?: 720
        vm.prefs.preferredTranslator = preferred
        vm.prefs.selectedPort = port.toIntOrNull() ?: 8123
        vm.setForcedDomain(forcedDomain)
        vm.back()
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Домен HDRezka", fontWeight = FontWeight.Bold)
            Text(
                "Если каталог не грузится — впиши рабочее зеркало (напр. hdrezka.ag). " +
                    "Пусто = автоопределение.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = forcedDomain, onValueChange = { forcedDomain = it; testResult = null },
                label = { Text("Домен (пусто = авто)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    vm.setForcedDomain(forcedDomain)
                    testResult = "Домен сохранён"
                }) { Text("Применить") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            testResult = "Проверка…"
                            testResult = runCatching { vm.testConnection(forcedDomain) }
                                .getOrElse { "Ошибка: ${it.message}" }
                            testing = false
                        }
                    },
                ) { Text("Проверить связь") }
            }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

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
                value = port, onValueChange = { port = it },
                label = { Text("Порт агента приставки") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
