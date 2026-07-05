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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hdrezka.pult.core.CatalogItem
import com.hdrezka.pult.core.ContentType
import com.hdrezka.pult.core.HdRezkaSearch
import com.hdrezka.pult.data.HistoryEntry
import kotlinx.coroutines.CoroutineScope
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

@Composable
private fun EmptyState(text: String, icon: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        if (icon) {
            Icon(
                Icons.Filled.Search, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RetryState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text("Не удалось загрузить", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            message, fontSize = 13.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Проверь домен в настройках.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Повторить")
        }
    }
}

/** Загружает данные по key и рисует loading/error(с повтором)/успех. */
@Composable
private fun <T> AsyncContent(
    key: Any?,
    modifier: Modifier,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    var reload by remember(key) { mutableStateOf(0) }
    val state by produceState<Result<T>?>(initialValue = null, key, reload) {
        value = null
        value = runCatching { load() }
    }
    Box(modifier) {
        val s = state
        when {
            s == null -> CenteredBox { CircularProgressIndicator() }
            s.isFailure -> CenteredBox {
                RetryState(s.exceptionOrNull()?.message ?: "неизвестная ошибка") { reload++ }
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

/** Постер-ячейка каталога: обложка, скрим, бейдж рейтинга и года, название. */
@Composable
private fun PosterCell(item: CatalogItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
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
            // нижний градиент для читаемости бейджа года
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            )
            item.year?.let {
                Text(
                    it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
            item.rating?.let {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC24B), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(it), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            item.title,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
        )
    }
}

/**
 * Адаптивная сетка постеров с бесконечной подгрузкой страниц.
 * Число колонок подстраивается под ширину экрана (телефон/планшет/альбом).
 */
@Composable
private fun PagedPosterGrid(
    key: Any?,
    modifier: Modifier,
    load: suspend (page: Int) -> List<CatalogItem>,
    onClick: (CatalogItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items = remember(key) { mutableStateListOf<CatalogItem>() }
    var page by remember(key) { mutableStateOf(0) }
    var loading by remember(key) { mutableStateOf(false) }
    var end by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()

    suspend fun loadNext() {
        if (loading || end) return
        loading = true
        error = null
        val next = page + 1
        runCatching { load(next) }
            .onSuccess { list ->
                // отсекаем дубликаты между страницами (ключ сетки = url)
                val fresh = list.filter { l -> items.none { it.url == l.url } }
                if (list.isEmpty()) end = true else { items.addAll(fresh); page = next }
            }
            .onFailure { error = it.message ?: "неизвестная ошибка" }
        loading = false
    }

    LaunchedEffect(key) { loadNext() }

    val shouldLoadMore by remember(key) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            items.isNotEmpty() && last >= items.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !loading && !end) loadNext()
    }

    when {
        items.isEmpty() && loading -> CenteredBox(modifier) { CircularProgressIndicator() }
        items.isEmpty() && error != null ->
            CenteredBox(modifier) { RetryState(error!!) { scope.launchLoad { loadNext() } } }
        items.isEmpty() -> CenteredBox(modifier) { EmptyState("Ничего не найдено") }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            state = gridState,
            modifier = modifier,
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items, key = { it.url }) { item -> PosterCell(item) { onClick(item) } }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

// маленький помощник, чтобы дергать suspend-блок из onClick
private fun CoroutineScope.launchLoad(block: suspend () -> Unit) {
    launch { block() }
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

            // строка приставки — тап открывает выбор устройства
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.navigate(Screen.Devices) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Cast, null,
                    tint = if (device != null) MaterialTheme.colorScheme.primary else Color.Gray,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (device != null) device.name else "Приставка не выбрана — нажми, чтобы найти",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // вкладки сортировки
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
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

            PagedPosterGrid(
                key = catPath to filter,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                load = { p -> vm.catalogPage(catPath, filter, p) },
            ) { item -> vm.navigate(Screen.Details(item.url, item.title)) }
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
                    val selected = vm.selectedDevice?.host == d.host && vm.selectedDevice?.port == d.port
                    ClickableRow(
                        (if (selected) "✓ " else "") + d.name,
                        subtitle = "${d.host}:${d.port}  ·  v${d.version}",
                    ) {
                        vm.selectDevice(d)
                        vm.back()
                    }
                }
            }
            if (!vm.scanning && vm.devices.isEmpty()) {
                Text(
                    "Приставки не найдены. Убедись, что плагин запущен и агент включён, " +
                        "телефон и приставка в одной Wi-Fi-сети, или задай адрес вручную.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        HistoryScreen(vm)
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
                    IconButton(onClick = { if (query.isNotBlank()) submitted = query.trim() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Искать")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitted = query.trim() }),
            )
            val q = submitted
            if (q == null) {
                CenteredBox(Modifier.fillMaxSize()) { EmptyState("Введите запрос") }
            } else {
                PagedPosterGrid(
                    key = q,
                    modifier = Modifier.fillMaxSize(),
                    load = { p -> vm.searchResultsPage(q, p) },
                ) { item -> vm.navigate(Screen.Details(item.url, item.title)) }
            }
        }
    }
}

@Composable
private fun HistoryScreen(vm: AppViewModel) {
    val entries = remember { vm.history.list().toMutableStateList() }
    AppScaffold(title = "Продолжить просмотр", onBack = { vm.back() }) { padding ->
        if (entries.isEmpty()) {
            CenteredBox(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("Вы ещё ничего не смотрели", icon = false)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.url }) { e ->
                    HistoryRow(
                        e,
                        onOpen = { vm.navigate(Screen.Details(e.url, e.title)) },
                        onDelete = { vm.deleteHistory(e.url); entries.remove(e) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
            Text(e.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                if (e.season != null && e.episode != null) append("S${e.season}E${e.episode}")
                if (!e.translatorName.isNullOrEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(e.translatorName)
                }
            }
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

// ── Details (постер, описание, озвучки) ──────────────────────────────────────────

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
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Box(
                            Modifier
                                .width(120.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            api.posterUrl?.let {
                                AsyncImage(
                                    model = it, contentDescription = api.name,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString { append(api.name); api.releaseYear?.let { append(" ($it)") } },
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            api.rating?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC24B), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("%.1f".format(it), fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                if (api.type == ContentType.SERIES) "Сериал" else "Фильм",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val trId = progress?.translatorId
                            if (progress != null && trId != null) {
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = {
                                    vm.navigate(
                                        Screen.Quality(
                                            trId, progress.translatorName ?: "",
                                            progress.season, progress.episode, progress.position,
                                        )
                                    )
                                }) {
                                    Icon(Icons.Filled.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (progress.season != null && progress.episode != null)
                                            "Продолжить S${progress.season}E${progress.episode}"
                                        else "Продолжить",
                                    )
                                }
                            }
                        }
                    }
                }
                if (api.description.isNotBlank()) {
                    item {
                        Text(
                            api.description,
                            Modifier.padding(horizontal = 16.dp),
                            fontSize = 14.sp, lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Озвучки", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold)
                }
                if (translators.isEmpty()) {
                    item { Text("Озвучки не найдены", Modifier.padding(16.dp)) }
                } else {
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

// ── Seasons ─────────────────────────────────────────────────────────────────────

@Composable
fun SeasonsScreen(vm: AppViewModel, screen: Screen.Seasons) {
    AppScaffold(title = "Сезоны · ${screen.translatorName}", onBack = { vm.back() }) { padding ->
        AsyncContent(
            key = screen,
            modifier = Modifier.fillMaxSize().padding(padding),
            load = { vm.seasons(screen.translatorId) },
        ) { seasons ->
            if (seasons.isEmpty()) CenteredBox { EmptyState("Сезоны не найдены", icon = false) }
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
            if (episodes.isEmpty()) CenteredBox { EmptyState("Эпизоды не найдены", icon = false) }
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
        scope.launchLoad {
            casting = true
            val err = vm.cast(
                screen.translatorId, screen.translatorName,
                screen.season, screen.episode, url, screen.resumeSec,
            )
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
                castError?.let {
                    Text("Ошибка: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                if (vm.selectedDevice == null) {
                    Text(
                        "Приставка не выбрана — выбери устройство, чтобы запустить воспроизведение.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (qualities.isEmpty()) {
                    CenteredBox { EmptyState("Ссылки не найдены", icon = false) }
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
            vm.pollStatus()
            delay(1000)
        }
    }
    val status = vm.status

    AppScaffold(title = "Пульт", onBack = { vm.back() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        status?.title?.ifEmpty { "—" } ?: "Нет связи с приставкой",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (status?.season != null && status.episode != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "S${status.season}E${status.episode} · ${status.translator}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    val pos = status?.position ?: 0
                    val dur = status?.duration ?: 0
                    if (dur > 0) {
                        LinearProgressIndicator(
                            progress = { (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${fmt(pos)} / ${fmt(dur)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            if (status?.playing == true) "Идёт воспроизведение" else "Ожидание…",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.control("seek", -15) }) { Text("−15с") }
                FilledIconButton(onClick = { vm.control("toggle") }, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (status?.paused == true) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "Пауза/Продолжить",
                        modifier = Modifier.size(34.dp),
                    )
                }
                OutlinedButton(onClick = { vm.control("seek", 15) }) { Text("+15с") }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { vm.control("voldown") }) {
                    Icon(Icons.Filled.VolumeDown, contentDescription = "Тише")
                }
                Spacer(Modifier.width(16.dp))
                FilledTonalIconButton(onClick = { vm.control("mute") }) {
                    Icon(Icons.Filled.VolumeOff, contentDescription = "Без звука")
                }
                Spacer(Modifier.width(16.dp))
                FilledTonalIconButton(onClick = { vm.control("volup") }) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "Громче")
                }
            }

            Spacer(Modifier.weight(1f))

            Button(onClick = { vm.control("stop"); vm.back() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Стоп")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) {
                Text("Другая серия / озвучка")
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
                        scope.launchLoad {
                            testing = true
                            testResult = "Проверка…"
                            testResult = runCatching { vm.testConnection(forcedDomain) }
                                .getOrElse { "Ошибка: ${it.message}" }
                            testing = false
                        }
                    },
                ) { Text("Проверить связь") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launchLoad {
                        testing = true
                        testResult = "Диагностика…"
                        testResult = runCatching { vm.diagnose(forcedDomain) }
                            .getOrElse { "Ошибка: ${it.message}" }
                        testing = false
                    }
                },
            ) { Text("Диагностика (что отдаёт сайт)") }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
