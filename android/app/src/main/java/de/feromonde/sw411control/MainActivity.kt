package de.feromonde.sw411control

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val BgTop = Color(0xFF0A1824)
private val BgBottom = Color(0xFF050C13)
private val CardColor = Color(0xE6172735)
private val CardDark = Color(0xD9101D28)
private val Line = Color(0x334A6075)
private val TextPrimary = Color(0xFFF4F7FB)
private val TextMuted = Color(0xFF8FA0B5)
private val Cyan = Color(0xFF22D3EE)
private val Green = Color(0xFF22E39A)
private val Red = Color(0xFFFF6B76)
private val Amber = Color(0xFFF8C45C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    secondary = Green,
                    background = BgBottom,
                    surface = CardColor,
                    onPrimary = Color(0xFF03232A),
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                ),
                typography = Typography()
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgBottom) {
                    Sw411Screen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sw411Screen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf(false) }
    var rebootDialog by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    var powerOffDialog by remember { mutableStateOf(false) }
    var serverDraft by remember(ui.serverUrl) { mutableStateOf(ui.serverUrl) }

    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearMessage()
    }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearError()
    }

    if (serverDialog) {
        AlertDialog(
            onDismissRequest = { serverDialog = false },
            title = { Text("Подключение к Nexbox") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Адрес SW411 Control. Например: http://192.168.2.159",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = serverDraft,
                        onValueChange = { serverDraft = it },
                        label = { Text("Адрес сервера") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setServerUrl(serverDraft)
                    serverDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { serverDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (rebootDialog) {
        AlertDialog(
            onDismissRequest = { rebootDialog = false },
            title = { Text("Перезагрузить SW411?") },
            text = { Text("HDMI-соединение кратковременно прервётся.") },
            confirmButton = {
                TextButton(onClick = {
                    rebootDialog = false
                    vm.reboot()
                }) { Text("Перезагрузить", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { rebootDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (updateDialog) {
        AlertDialog(
            onDismissRequest = { updateDialog = false },
            title = { Text("Обновить SW411 Control?") },
            text = {
                Text(
                    "Nexbox скачает актуальную версию из GitHub и перезапустит сервис. Прошивка самого SW411 не затрагивается."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialog = false
                    vm.updateController()
                }) { Text("Обновить") }
            },
            dismissButton = {
                TextButton(onClick = { updateDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (powerOffDialog) {
        AlertDialog(
            onDismissRequest = { powerOffDialog = false },
            title = { Text("Выключить SW411?") },
            text = { Text("Все HDMI-соединения через переключатель будут прерваны.") },
            confirmButton = {
                TextButton(onClick = {
                    powerOffDialog = false
                    vm.togglePower()
                }) { Text("Выключить", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { powerOffDialog = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DeviceMark()
                    Spacer(Modifier.width(13.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SW411 Control",
                            color = TextPrimary,
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.7).sp
                        )
                        Text(
                            "Нативное управление HDMI переключателем",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Меню", tint = TextPrimary)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Адрес Nexbox") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = {
                                    menuOpen = false
                                    serverDraft = ui.serverUrl
                                    serverDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Обновить SW411 Control") },
                                leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                                onClick = {
                                    menuOpen = false
                                    updateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Открыть веб-панель") },
                                leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
                                onClick = {
                                    menuOpen = false
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.serverUrl)))
                                }
                            )
                        }
                    }
                }

                StatusCard(ui)

                SectionTitle("Быстрые действия")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionTile(
                        title = when (ui.status.power) {
                            true -> "Выключить"
                            false -> "Включить"
                            null -> "Питание"
                        },
                        icon = Icons.Rounded.PowerSettingsNew,
                        highlighted = true,
                        modifier = Modifier.weight(1f),
                        enabled = !ui.busy,
                        onClick = {
                            if (ui.status.power == true) powerOffDialog = true
                            else vm.togglePower()
                        }
                    )
                    ActionTile(
                        title = "Перезагрузить",
                        icon = Icons.Rounded.RestartAlt,
                        danger = true,
                        modifier = Modifier.weight(1f),
                        enabled = !ui.busy,
                        onClick = { rebootDialog = true }
                    )
                    ActionTile(
                        title = "Обновить",
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.weight(1f),
                        enabled = !ui.busy,
                        onClick = vm::refresh
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionTitle("Входы", modifier = Modifier.weight(1f))
                    Text("Выберите источник", color = Color(0xFF66B6FF), fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(4) { index ->
                        InputTile(
                            label = ui.status.inputNames.getOrElse(index) { "HDMI ${index + 1}" },
                            active = ui.status.input == index + 1,
                            enabled = !ui.busy,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.selectInput(index + 1) }
                        )
                    }
                }

                SectionTitle("Режим")
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    border = BorderStroke(1.dp, Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ModeRow(
                        icon = Icons.Rounded.SwapHoriz,
                        title = "Автопереключение",
                        subtitle = "SW411 автоматически выбирает активный источник",
                        checked = ui.status.autoSwitch == true,
                        enabled = !ui.busy && ui.status.connected,
                        onChecked = vm::setAuto
                    )
                    HorizontalDivider(color = Line)
                    ModeRow(
                        icon = Icons.Rounded.VolumeUp,
                        title = "eARC / ARC",
                        subtitle = "Звук с телевизора через HDMI eARC/ARC",
                        checked = ui.status.earc == true,
                        enabled = !ui.busy && ui.status.connected,
                        onChecked = vm::setEarc
                    )
                    HorizontalDivider(color = Line)
                    AudioEdidInfo()
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SectionTitle("Системный журнал", modifier = Modifier.weight(1f))
                    LiveBadge(ui.sseConnected)
                }

                LogsCard(ui.logs)

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DeviceMark() {
    Card(
        modifier = Modifier.size(54.dp),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A38)),
        border = BorderStroke(1.dp, Line)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 29.dp, height = 17.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Transparent)
                    .then(Modifier.background(Color(0xFF203545)))
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BgTop)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(ui: AppUiState) {
    val s = ui.status
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (s.connected) Green else Red)
                )
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        if (s.connected) "Подключено" else "Нет связи",
                        color = if (s.connected) Green else Red,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (s.connected) {
                            if (ui.sseConnected) "Live-status подключён" else "Устройство готово, live-status переподключается"
                        } else {
                            s.error ?: "SW411 Control недоступен"
                        },
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                Metric("Порт", s.port ?: "—", Modifier.weight(1f))
                Metric("Прошивка", s.firmware ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("Активный вход", s.inputName ?: s.input?.let { "HDMI $it" } ?: "—", Modifier.weight(1f))
                Metric(
                    "Температура",
                    s.temperature?.let {
                        val text = if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)
                        "$text °C"
                    } ?: "—",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        color = TextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
private fun ActionTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val border = when {
        danger -> Color(0x66FF6B76)
        highlighted -> Color(0x9922D3EE)
        else -> Line
    }
    val tint = when {
        highlighted -> Color(0xFF5EE7F8)
        danger -> Color(0xFFDCE6F2)
        else -> Color(0xFFA7B8CC)
    }

    Card(
        modifier = modifier
            .height(100.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) Color(0xB213404A) else Color(0xE6152533)
        ),
        border = BorderStroke(1.dp, border)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(29.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InputTile(
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(105.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xD0134650) else Color(0xE6152533)
        ),
        border = BorderStroke(1.dp, if (active) Cyan else Line)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(width = 27.dp, height = 14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) Color(0xFFBFF9FF) else Color(0xFF718398))
            )
            Spacer(Modifier.height(10.dp))
            Text(
                label,
                color = if (active) TextPrimary else Color(0xFFA9B5C5),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (active) Cyan else Color(0xFF596B80))
            )
        }
    }
}

@Composable
private fun ModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color(0xFFA9B9CC), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled
        )
    }
}

@Composable
private fun AudioEdidInfo() {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeUp, null, tint = Color(0xFFA9B9CC), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Audio EDID", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("2.0 / 5.1 / 7.1 / PASS", color = TextMuted, fontSize = 12.sp)
            }
            Text("CLI нет", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("2.0", "5.1", "7.1", "PASS").forEach { mode ->
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = CardDark,
                        disabledContentColor = TextMuted
                    ),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(mode, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Без подтверждённой serial-команды приложение не отправляет непроверенные Audio EDID команды.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun LiveBadge(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (connected) Green else Amber)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (connected) "LIVE" else "RECONNECT",
            color = if (connected) Green else Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LogsCard(logs: List<Sw411Log>) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (logs.isEmpty()) {
            Text(
                "Журнал пока пуст",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Column(Modifier.padding(horizontal = 14.dp)) {
                logs.take(8).forEachIndexed { index, log ->
                    Row(
                        modifier = Modifier.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when (log.level) {
                                        "error" -> Red
                                        "warn" -> Amber
                                        else -> Cyan
                                    }
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(log.time, color = Color(0xFF74869B), fontSize = 11.sp, modifier = Modifier.width(60.dp))
                        Text(log.message, color = Color(0xFFC8D2DF), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    if (index != logs.take(8).lastIndex) {
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    }
}
