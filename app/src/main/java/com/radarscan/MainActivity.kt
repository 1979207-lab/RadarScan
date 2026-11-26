package com.radarscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import java.net.HttpURLConnection
import java.util.Locale
import kotlin.math.*

data class WifiEntry(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val capabilities: String,
    val distanceMeters: Double
)

data class SignalSample(val time: String, val rssi: Int)

data class ChannelLoad(val channel: Int, val count: Int, val band: String)
data class LanDevice(val ip: String, val host: String)

class RadarViewModel : ViewModel() {
    var networks by mutableStateOf<List<WifiEntry>>(emptyList())
        private set
    var status by mutableStateOf("Нажмите «Сканировать», чтобы найти сети")
        private set
    var busy by mutableStateOf(false)
        private set
    var routerName by mutableStateOf("Не определён")
        private set
    var routerIp by mutableStateOf("—")
        private set
    var routerBssid by mutableStateOf("—")
        private set
    var routerRssi by mutableIntStateOf(-100)
        private set
    var routerFrequency by mutableIntStateOf(0)
        private set
    var latency by mutableStateOf("Не проверялась")
        private set
    var pingBusy by mutableStateOf(false)
        private set
    var history by mutableStateOf<List<SignalSample>>(emptyList())
        private set
    var lastScan by mutableStateOf("Ещё не сканировали")
        private set
    var lanDevices by mutableStateOf<List<LanDevice>>(emptyList())
        private set
    var lanStatus by mutableStateOf("Сканирование локальной сети ещё не запускалось")
        private set
    var lanBusy by mutableStateOf(false)
        private set
    var internetStatus by mutableStateOf("Тест ещё не запускался")
        private set
    var internetBusy by mutableStateOf(false)
        private set
    var downloadSpeed by mutableStateOf("—")
        private set
    var uploadSpeed by mutableStateOf("—")
        private set
    var internetLatency by mutableStateOf("—")
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set

    fun loadFavorites(context: Context) {
        favorites = context.getSharedPreferences("radarscan_settings", Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    }

    fun toggleFavorite(context: Context, bssid: String) {
        val next = favorites.toMutableSet()
        if (!next.add(bssid)) next.remove(bssid)
        favorites = next
        context.getSharedPreferences("radarscan_settings", Context.MODE_PRIVATE)
            .edit().putStringSet("favorites", next).apply()
    }

    fun scanLocalNetwork(context: Context, wifi: WifiManager) {
        if (lanBusy) return
        viewModelScope.launch {
            lanBusy = true
            lanStatus = "Ищу устройства в вашей Wi-Fi сети…"
            try {
                @Suppress("DEPRECATION")
                val ip = wifi.connectionInfo.ipAddress
                val local = listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255)
                if (ip == 0 || local[0] == 0) {
                    lanStatus = "Не удалось определить локальный IP. Подключитесь к Wi-Fi."
                    return@launch
                }
                val prefix = "${local[3]}.${local[2]}.${local[1]}."
                val found = withContext(Dispatchers.IO) {
                    (1..254).map { n ->
                        async {
                            val address = "$prefix$n"
                            try {
                                val reachable = InetAddress.getByName(address).isReachable(450)
                                if (reachable) LanDevice(address, InetAddress.getByName(address).canonicalHostName) else null
                            } catch (_: Exception) { null }
                        }
                    }.awaitAll().filterNotNull()
                }
                lanDevices = found.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
                lanStatus = "Найдено устройств: ${found.size}. Часть устройств может не отвечать на системную проверку."
            } catch (_: SecurityException) {
                lanStatus = "Нет доступа к локальной сети."
            } catch (_: Exception) {
                lanStatus = "Не удалось просканировать локальную сеть."
            } finally { lanBusy = false }
        }
    }

    fun runInternetTest() {
        if (internetBusy) return
        viewModelScope.launch {
            internetBusy = true
            internetStatus = "Проверяю соединение…"
            downloadSpeed = "Измеряется…"
            uploadSpeed = "Измеряется…"
            val result = withContext(Dispatchers.IO) {
                var down = "Не удалось"
                var up = "Не удалось"
                var ping = "—"
                try {
                    val startPing = System.nanoTime()
                    URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection().apply {
                        connectTimeout = 6000; readTimeout = 6000
                        getInputStream().use { it.readBytes() }
                    }
                    ping = "${(System.nanoTime() - startPing) / 1_000_000} мс"
                } catch (_: Exception) { ping = "Нет ответа" }
                try {
                    val bytes = 5_000_000
                    val c = (URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection)
                    c.connectTimeout = 10000; c.readTimeout = 15000
                    val start = System.nanoTime()
                    var total = 0L
                    c.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
                    }
                    val seconds = ((System.nanoTime() - start) / 1e9).coerceAtLeast(.001)
                    down = String.format(Locale.getDefault(), "%.1f Мбит/с", total * 8 / seconds / 1_000_000)
                    c.disconnect()
                } catch (_: Exception) { }
                try {
                    val payload = ByteArray(1_000_000) { 65 }
                    val c = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection)
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
                    c.setFixedLengthStreamingMode(payload.size)
                    val start = System.nanoTime()
                    c.outputStream.use { it.write(payload) }
                    val code = c.responseCode
                    val seconds = ((System.nanoTime() - start) / 1e9).coerceAtLeast(.001)
                    if (code in 200..299) up = String.format(Locale.getDefault(), "%.1f Мбит/с", payload.size * 8 / seconds / 1_000_000)
                    c.disconnect()
                } catch (_: Exception) { }
                Triple(down, up, ping)
            }
            downloadSpeed = result.first
            uploadSpeed = result.second
            internetLatency = result.third
            internetStatus = "Тест завершён. Результаты зависят от сервера и качества Wi-Fi."
            internetBusy = false
        }
    }

    fun createReport(): String = buildString {
        appendLine("RadarScan — отчёт диагностики")
        appendLine("Дата: " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(java.util.Date()))
        appendLine("Подключённая сеть: $routerName")
        appendLine("IP шлюза: $routerIp")
        appendLine("BSSID: $routerBssid")
        appendLine("Сигнал: $routerRssi dBm")
        appendLine("Частота: $routerFrequency MHz")
        appendLine("Отклик шлюза: $latency")
        appendLine("Интернет: ping $internetLatency, download $downloadSpeed, upload $uploadSpeed")
        appendLine()
        appendLine("Wi-Fi сети:")
        networks.forEach { appendLine("${it.ssid}; ${it.bssid}; ${it.rssi} dBm; ${it.frequency} MHz; канал ${it.channel}") }
        appendLine()
        appendLine("Устройства локальной сети:")
        lanDevices.forEach { appendLine("${it.ip}; ${it.host}") }
    }

    @Suppress("DEPRECATION")
    fun refreshRouter(context: Context, wifi: WifiManager) {
        try {
            val info = wifi.connectionInfo
            routerName = info.ssid?.removeSurrounding("\"")?.takeIf {
                it.isNotBlank() && it != "<unknown ssid>"
            } ?: "Сеть не определена"
            routerBssid = info.bssid ?: "Недоступен"
            routerRssi = info.rssi
            routerFrequency = info.frequency

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val props = network?.let { cm.getLinkProperties(it) }
            val gateway = props?.routes?.firstOrNull { it.isDefaultRoute }?.gateway
            routerIp = gateway?.hostAddress ?: "Не определён"
            if (routerIp == "Не определён") latency = "Нет адреса шлюза"
            addHistory(info.rssi)
        } catch (_: SecurityException) {
            routerName = "Нет разрешения на чтение Wi-Fi"
        } catch (_: Exception) {
            routerName = "Нет данных о подключении"
        }
    }

    private fun addHistory(rssi: Int) {
        if (rssi <= -100 || rssi == 0) return
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        if (history.lastOrNull()?.rssi == rssi && history.lastOrNull()?.time == stamp) return
        history = (history + SignalSample(stamp, rssi)).takeLast(40)
    }

    @Suppress("DEPRECATION")
    fun scan(wifi: WifiManager) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "Запрашиваю сканирование Wi-Fi…"
            try {
                val started = wifi.startScan()
                delay(2600)
                val found = wifi.scanResults.orEmpty().map { result ->
                    val channel = when (result.frequency) {
                        2484 -> 14
                        in 2412..2472 -> (result.frequency - 2407) / 5
                        in 5000..5895 -> (result.frequency - 5000) / 5
                        in 5955..7115 -> (result.frequency - 5950) / 5
                        else -> -1
                    }
                    WifiEntry(
                        ssid = result.SSID.ifBlank { "Скрытая сеть" },
                        bssid = result.BSSID ?: "—",
                        rssi = result.level,
                        frequency = result.frequency,
                        channel = channel,
                        capabilities = result.capabilities ?: "Защита не определена",
                        distanceMeters = estimateDistance(result.level)
                    )
                }.distinctBy { it.bssid }.sortedByDescending { it.rssi }

                networks = found
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                lastScan = stamp
                status = when {
                    found.isEmpty() -> "Сети не найдены. Проверьте Wi-Fi и разрешения."
                    !started -> "Android ограничил новый запрос; показаны доступные результаты"
                    else -> "Найдено сетей: ${found.size}"
                }
            } catch (_: SecurityException) {
                status = "Нет разрешения. Разрешите доступ к устройствам поблизости и геолокации."
            } catch (_: Exception) {
                status = "Не удалось получить результаты Wi-Fi сканирования"
            } finally {
                busy = false
            }
        }
    }

    fun checkLatency() {
        val host = routerIp
        if (host == "—" || host == "Не определён" || pingBusy) {
            latency = if (host == "—" || host == "Не определён") "Сначала подключитесь к Wi-Fi" else latency
            return
        }
        viewModelScope.launch {
            pingBusy = true
            latency = "Проверяю…"
            val result = withContext(Dispatchers.IO) {
                try {
                    val address = InetAddress.getByName(host)
                    val start = System.nanoTime()
                    val reachable = address.isReachable(1800)
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    if (reachable) "${String.format(Locale.getDefault(), "%.0f", elapsed)} мс"
                    else "Нет ответа (возможно, ICMP заблокирован)"
                } catch (_: Exception) {
                    "Проверка не удалась"
                }
            }
            latency = result
            pingBusy = false
        }
    }

    private fun estimateDistance(rssi: Int): Double {
        // Грубая модель потерь сигнала: ориентир -45 dBm на расстоянии 1 м.
        // Реальная погрешность зависит от роутера, телефона, стен и помех.
        val value = 10.0.pow((-45.0 - rssi) / (10.0 * 2.2))
        return value.coerceIn(0.5, 50.0)
    }

    fun channels(): List<ChannelLoad> {
        return networks.filter { it.channel > 0 }.groupBy {
            when {
                it.frequency in 2400..2500 -> "2,4 ГГц"
                it.frequency in 4900..5900 -> "5 ГГц"
                it.frequency >= 5955 -> "6 ГГц"
                else -> "Другая"
            } to it.channel
        }.map { (key, values) ->
            ChannelLoad(key.second, values.size, key.first)
        }.sortedWith(compareBy<ChannelLoad> { it.band }.thenBy { it.channel })
    }
}

class MainActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            ask.launch(permissions.toTypedArray())
        }
        setContent { RadarScanApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScanApp(vm: RadarViewModel = viewModel()) {
    val context = LocalContext.current
    val wifi = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    val prefs = remember { context.getSharedPreferences("radarscan_settings", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.refreshRouter(context, wifi); vm.loadFavorites(context) }

    val colors = if (darkMode) {
        darkColorScheme(
            primary = Color(0xFF35D6B0),
            secondary = Color(0xFF65B8FF),
            background = Color(0xFF08121D),
            surface = Color(0xFF142333),
            surfaceVariant = Color(0xFF1D3042)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF087F70),
            secondary = Color(0xFF246FA8),
            background = Color(0xFFF3F7FA),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE5EDF2)
        )
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("RadarScan", fontWeight = FontWeight.Bold)
                            Text("Анализ Wi-Fi сети", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            darkMode = !darkMode
                            prefs.edit().putBoolean("dark_mode", darkMode).apply()
                        }) {
                            Icon(
                                if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Переключить тему"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val tabs = listOf("Обзор", "Сети", "Каналы", "История", "Устройства", "Интернет", "Анализ", "Избранное", "Роутер")
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> Dashboard(vm, wifi, context)
                    1 -> NetworksScreen(vm)
                    2 -> ChannelsScreen(vm)
                    3 -> HistoryScreen(vm)
                    4 -> LanScreen(vm, context, wifi)
                    5 -> InternetScreen(vm)
                    6 -> WeakSpotsScreen(vm)
                    7 -> FavoritesScreen(vm, context)
                    else -> RouterDetailsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun Dashboard(vm: RadarViewModel, wifi: WifiManager, context: Context) {
    LaunchedEffect(wifi) {
        while (true) {
            vm.refreshRouter(context, wifi)
            delay(5000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Карта Wi-Fi окружения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Положение точек условное; радиус рассчитан по уровню сигнала",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("LIVE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Radar(vm.networks, Modifier.fillMaxWidth().height(270.dp).padding(8.dp))
        }
        Button(
            onClick = { vm.scan(wifi); vm.refreshRouter(context, wifi) },
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.busy) "Сканирование…" else "Сканировать Wi-Fi")
        }
        Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Последнее сканирование: ${vm.lastScan}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "RadarScan — отчёт диагностики")
                    putExtra(Intent.EXTRA_TEXT, vm.createReport())
                }
                context.startActivity(Intent.createChooser(share, "Экспорт отчёта"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Экспортировать отчёт") }

        Text("Подключённый роутер", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.routerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                InfoLine("Шлюз / IP роутера", vm.routerIp)
                InfoLine("BSSID", vm.routerBssid)
                InfoLine("Уровень сигнала", if (vm.routerRssi > -100) "${vm.routerRssi} dBm · ${signalLabel(vm.routerRssi)}" else "Нет данных")
                InfoLine("Частота", if (vm.routerFrequency > 0) "${vm.routerFrequency} MHz" else "—")
                InfoLine("Отклик роутера", vm.latency)
                OutlinedButton(onClick = { vm.refreshRouter(context, wifi); vm.checkLatency() },
                    enabled = !vm.pingBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.pingBusy) "Проверяю отклик…" else "Проверить отклик роутера")
                }
                Text("Проверка использует системный ping-подобный запрос. Некоторые роутеры блокируют ICMP.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Найдено сетей", vm.networks.size.toString(), Modifier.weight(1f))
            MetricCard("Сильный сигнал", vm.networks.count { it.rssi >= -60 }.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun NetworksScreen(vm: RadarViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("Обнаруженные точки доступа", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Расстояние — приблизительная оценка по RSSI, не прямое измерение.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        items(vm.networks, key = { it.bssid }) { network ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(network.ssid, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        SignalPill(network.rssi)
                    }
                    Text(network.bssid, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.toggleFavorite(LocalContext.current, network.bssid) }) { Text(if (network.bssid in vm.favorites) "★ В избранном" else "☆ В избранное") }
                    InfoLine("Сигнал", "${network.rssi} dBm · ${signalLabel(network.rssi)}")
                    InfoLine("Оценка расстояния", distanceLabel(network.distanceMeters))
                    InfoLine("Диапазон / канал", "${bandName(network.frequency)} · ${network.channel}")
                    InfoLine("Частота", "${network.frequency} MHz")
                    Text(network.capabilities, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (vm.networks.isEmpty()) item { EmptyMessage("Сетей пока нет. Вернитесь на вкладку «Обзор» и запустите сканирование.") }
    }
}

@Composable
private fun ChannelsScreen(vm: RadarViewModel) {
    val channels = vm.channels()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Загруженность каналов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Показывается число обнаруженных сетей на каждом канале. Это ориентир по соседним точкам, а не измерение радиоэфира.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (channels.isEmpty()) {
            EmptyMessage("Сначала выполните сканирование Wi-Fi.")
        } else {
            channels.forEach { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${item.band} · канал ${item.channel}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${item.count} сет.", color = loadColor(item.count), fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { (item.count / 8f).coerceIn(0.04f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = loadColor(item.count)
                        )
                        Text(loadLabel(item.count), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("Совет: выбирайте менее занятый канал, но учитывайте ширину канала и настройки самого роутера.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryScreen(vm: RadarViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("История уровня сигнала", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("История текущего подключения за время работы приложения.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (vm.history.size < 2) {
                    EmptyMessage("Недостаточно измерений. Откройте обзор и обновите данные подключения несколько раз.")
                } else {
                    SignalHistoryChart(vm.history, Modifier.fillMaxWidth().height(190.dp))
                }
            }
        }
        vm.history.asReversed().forEach { sample ->
            InfoLine(sample.time, "${sample.rssi} dBm · ${signalLabel(sample.rssi)}")
        }
    }
}

@Composable
private fun Radar(networks: List<WifiEntry>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * .42f
        for (ring in 1..4) {
            drawCircle(grid.copy(alpha = .75f), radius * ring / 4f, center, style = Stroke(1.dp.toPx()))
        }
        drawLine(grid, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(grid, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        drawCircle(primary, 8.dp.toPx(), center)
        drawCircle(primary.copy(alpha = .18f), 16.dp.toPx(), center)
        networks.take(45).forEach { item ->
            // Азимут условный: Android не сообщает направление на обычную Wi-Fi точку.
            val angle = ((item.bssid.hashCode().toLong() and 0x7fffffffL) % 360L).toDouble()
            val normalized = (item.distanceMeters / 20.0).coerceIn(.08, 1.0).toFloat()
            val distance = radius * normalized
            val point = Offset(
                center.x + cos(Math.toRadians(angle)).toFloat() * distance,
                center.y + sin(Math.toRadians(angle)).toFloat() * distance
            )
            val color = signalColor(item.rssi)
            drawCircle(color.copy(alpha = .18f), 10.dp.toPx(), point)
            drawCircle(color, 5.dp.toPx(), point)
        }
    }
}

@Composable
private fun SignalHistoryChart(samples: List<SignalSample>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val left = 12.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val width = right - left
        val height = bottom - top
        for (i in 0..4) {
            val y = top + height * i / 4f
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val points = samples.mapIndexed { index, sample ->
            val x = left + width * index / (samples.size - 1).coerceAtLeast(1)
            val normalized = ((sample.rssi.coerceIn(-100, -30) + 100) / 70f)
            Offset(x, bottom - normalized * height)
        }
        points.zipWithNext().forEach { (a, b) ->
            drawLine(line, a, b, 3.dp.toPx(), cap = StrokeCap.Round)
        }
        points.forEach { drawCircle(line, 4.dp.toPx(), it) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignalPill(rssi: Int) {
    Surface(color = signalColor(rssi).copy(alpha = .16f), shape = CircleShape) {
        Text("${rssi} dBm", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = signalColor(rssi), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF27B77A)
    rssi >= -75 -> Color(0xFFE6A23C)
    else -> Color(0xFFE35D68)
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -50 -> "Отличный"
    rssi >= -60 -> "Хороший"
    rssi >= -70 -> "Средний"
    rssi >= -80 -> "Слабый"
    else -> "Очень слабый"
}

private fun distanceLabel(distance: Double): String {
    val low = (distance * .55).coerceAtLeast(.3)
    val high = (distance * 1.8).coerceAtMost(80.0)
    return if (distance < 1.0) {
        "${String.format(Locale.getDefault(), "%.1f", distance)} м (примерно)"
    } else {
        "${String.format(Locale.getDefault(), "%.1f", distance)} м · диапазон ${String.format(Locale.getDefault(), "%.1f–%.1f", low, high)} м"
    }
}

private fun bandName(frequency: Int): String = when {
    frequency in 2400..2500 -> "2,4 ГГц"
    frequency in 4900..5900 -> "5 ГГц"
    frequency >= 5955 -> "6 ГГц"
    else -> "Диапазон неизвестен"
}

private fun loadColor(count: Int): Color = when {
    count >= 5 -> Color(0xFFE35D68)
    count >= 3 -> Color(0xFFE6A23C)
    else -> Color(0xFF27B77A)
}

private fun loadLabel(count: Int): String = when {
    count >= 5 -> "Много обнаруженных сетей"
    count >= 3 -> "Умеренное количество сетей"
    else -> "Мало обнаруженных сетей"
}


@Composable
private fun LanScreen(vm: RadarViewModel, context: Context, wifi: WifiManager) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Устройства в локальной сети", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Поиск выполняется в текущей подсети Wi-Fi. Android и сетевые устройства могут блокировать такие запросы.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.scanLocalNetwork(context, wifi) }, enabled = !vm.lanBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.lanBusy) "Сканирую сеть…" else "Найти устройства")
        }
        Text(vm.lanStatus)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.lanDevices, key = { it.ip }) { device ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.ip, fontWeight = FontWeight.Bold)
                        Text(device.host, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InternetScreen(vm: RadarViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Проверка интернета", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Тест использует публичный сервер Cloudflare. Измерения расходуют мобильный или Wi-Fi трафик.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.runInternetTest() }, enabled = !vm.internetBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.internetBusy) "Выполняется тест…" else "Запустить тест скорости")
        }
        Text(vm.internetStatus)
        MetricCard("Загрузка", vm.downloadSpeed, Modifier.fillMaxWidth())
        MetricCard("Отдача", vm.uploadSpeed, Modifier.fillMaxWidth())
        MetricCard("Отклик сервера", vm.internetLatency, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WeakSpotsScreen(vm: RadarViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Анализ качества Wi-Fi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Подсказки строятся по уровню RSSI обнаруженных сетей рядом с телефоном. Для карты покрытия нужно перемещаться и повторять измерения.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.networks.isEmpty()) EmptyMessage("Сначала выполните сканирование Wi-Fi.")
        vm.networks.sortedBy { it.rssi }.forEach { network ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(network.ssid, fontWeight = FontWeight.SemiBold)
                    SignalPill(network.rssi)
                    Text(when {
                        network.rssi < -80 -> "Очень слабый сигнал: попробуйте приблизиться к роутеру или убрать препятствия."
                        network.rssi < -70 -> "Сигнал слабый: проверьте расположение роутера и помехи."
                        network.rssi < -60 -> "Средний сигнал: для стабильной работы лучше сократить расстояние."
                        else -> "Хороший уровень сигнала."
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: RadarViewModel, context: Context) {
    val saved = vm.networks.filter { it.bssid in vm.favorites }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Избранные сети", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Избранное сохраняется на этом телефоне.")
        if (vm.favorites.isEmpty()) EmptyMessage("Пока нет избранных сетей. Добавьте сеть на вкладке «Сети».")
        else if (saved.isEmpty()) EmptyMessage("Сохранённые сети появятся здесь после следующего сканирования.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(saved, key = { it.bssid }) { network ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(network.ssid, fontWeight = FontWeight.Bold)
                        Text(network.bssid, style = MaterialTheme.typography.labelSmall)
                        InfoLine("Сигнал", "${network.rssi} dBm")
                        TextButton(onClick = { vm.toggleFavorite(context, network.bssid) }) { Text("Убрать из избранного") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterDetailsScreen(vm: RadarViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Информация о роутере", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("Имя сети", vm.routerName)
                InfoLine("IP шлюза", vm.routerIp)
                InfoLine("BSSID", vm.routerBssid)
                InfoLine("Производитель", "Определение по MAC может быть недоступно на Android")
                InfoLine("Сигнал", "${vm.routerRssi} dBm")
                InfoLine("Частота", if (vm.routerFrequency > 0) "${vm.routerFrequency} MHz" else "—")
                InfoLine("Отклик", vm.latency)
            }
        }
        Text("Android может скрывать MAC-адреса и часть сведений о сети из соображений конфиденциальности.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
