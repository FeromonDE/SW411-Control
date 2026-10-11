package de.feromonde.sw411control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
internal fun NativePanel(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val canControl = !ui.busy && ui.status.connected
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg,
        surface = Color(0xFF0C1722), onBackground = TextPrimary, onSurface = TextPrimary)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Bg).drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF09131D), Color(0xFF061019), Color(0xFF050C13))))
            drawRect(Brush.radialGradient(listOf(Cyan.copy(alpha = .13f), Color.Transparent),
                center = Offset(size.width, -size.height * .1f), radius = 640.dp.toPx()))
            drawRect(Brush.radialGradient(listOf(Color(0xFF4A69FF).copy(alpha = .10f), Color.Transparent),
                center = Offset(-size.width * .2f, size.height * .1f), radius = 570.dp.toPx()))
        }.safeDrawingPadding()) {
            val viewportWidth = maxWidth.value
            val wide = viewportWidth >= 560
            val padding = if (wide) 24.dp else 16.dp
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 650.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = padding)
                .padding(top = 22.dp, bottom = 28.dp).alpha(if (ui.busy) .62f else 1f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    WebTile(Modifier.size(52.dp), border = Color.White.copy(alpha = .05f), radius = 17.dp,
                        brush = Brush.linearGradient(listOf(Color(0xF2293948), Color(0xF2121D28))),
                        onClick = {}, enabled = false) { WebIcon(R.drawable.web_device, 28.dp) }
                    Column(Modifier.weight(1f)) {
                        val titleSize = (viewportWidth * .07f).coerceIn(26f, 36f)
                        Text("SW411 Control", fontSize = titleSize.sp, lineHeight = (titleSize * 1.02f).sp,
                            letterSpacing = (-titleSize * .03f).sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.height(5.dp))
                        Text("Управление HDMI переключателем", color = Muted, fontSize = 14.sp, lineHeight = 17.sp)
                    }
                    Box {
                        WebTile(Modifier.size(46.dp).semantics { contentDescription = "Меню" }, radius = 15.dp,
                            onClick = { menuOpen = true }) { WebIcon(R.drawable.web_more, 24.dp, Color(0xFFC7D2DF)) }
                        if (menuOpen) {
                            val y = with(LocalDensity.current) { 54.dp.roundToPx() }
                            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, y),
                                onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = true)) {
                                Column(Modifier.width(minOf(310.dp, maxWidth - 32.dp))
                                    .shadow(22.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFA0D1822)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(8.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("SW411 Control", color = Muted, fontSize = 12.sp)
                                        Text("v${ui.controllerVersion}", color = Muted, fontSize = 12.sp)
                                    }
                                    MenuAction("Настройки", "Имена входов, MQTT, Home Assistant", R.drawable.web_settings,
                                        enabled = !ui.busy) { menuOpen = false; vm.openSettings() }
                                    MenuAction("Адрес сервера", "Подключение к Nexbox", R.drawable.web_device,
                                        enabled = !ui.busy) { menuOpen = false; serverOpen = true }
                                    HoldTile("Обновить из GitHub", Modifier.fillMaxWidth(), enabled = !ui.busy,
                                        radius = 14.dp,
                                        onHold = { menuOpen = false; vm.updateController() },
                                        onShortPress = { vm.notifyMessage("Для обновления удерживай 1,5 сек.") },
                                        onAccessibleClick = { menuOpen = false; confirmation = "update" }) {
                                        MenuCopy("Обновить из GitHub", "Удерживай 1,5 сек.", R.drawable.web_update)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                StatusPanel(ui, wide)
                Section("Быстрые действия") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (viewportWidth < 360) 7.dp else 10.dp)) {
                        WebTile(Modifier.weight(1f).heightIn(min = if (viewportWidth < 360) 84.dp else 90.dp),
                            enabled = canControl, border = Cyan.copy(alpha = .48f),
                            brush = Brush.linearGradient(listOf(Color(0xBF0D4A55), Color(0xBF0D2C36))),
                            onClick = { if (ui.status.power == true) confirmation = "power" else vm.togglePower() }) {
                            ActionCopy(when (ui.status.power) { true -> "Выключить"; false -> "Включить"; null -> "Питание" },
                                R.drawable.web_power, Color(0xFF5EE7F8))
                        }
                        HoldTile("Перезагрузить", Modifier.weight(1f).heightIn(min = if (viewportWidth < 360) 84.dp else 90.dp),
                            canControl, border = Red.copy(alpha = .28f), onHold = vm::reboot,
                            onShortPress = { vm.notifyMessage("Для перезагрузки удерживай 1,5 сек.") },
                            onAccessibleClick = { confirmation = "reboot" }) {
                            ActionCopy("Перезагрузить", R.drawable.web_reboot)
                        }
                        WebTile(Modifier.weight(1f).heightIn(min = if (viewportWidth < 360) 84.dp else 90.dp),
                            enabled = !ui.busy, onClick = vm::refresh) { ActionCopy("Обновить", R.drawable.web_refresh) }
                    }
                }
                Section("Входы", "Выберите источник") {
                    val columns = if (viewportWidth < 360) 2 else 4
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        (1..4).chunked(columns).forEach { inputs ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                inputs.forEach { input ->
                                    InputTile(ui.status.inputNames[input - 1], ui.status.input == input, canControl,
                                        Modifier.weight(1f)) { vm.selectInput(input) }
                                }
                            }
                        }
                    }
                }
                Section("Режим") {
                    WebPanel {
                        ModeRow(R.drawable.web_auto, "Автопереключение", "SW411 автоматически выбирает активный источник") {
                            WebSwitch("Автопереключение", ui.status.autoSwitch == true, canControl, vm::setAuto)
                        }
                        WebDivider()
                        ModeRow(R.drawable.web_earc, "eARC / ARC", "Звук с телевизора и HDMI-CEC аудио") {
                            WebSwitch("eARC", ui.status.earc == true, canControl, vm::setEarc)
                        }
                        WebDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) {
                            ModeCopy(R.drawable.web_audio, "Audio EDID", "2.0 / 5.1 / 7.1 / PASS") { WebBadge("USB CLI нет") }
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 15.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf("2.0", "5.1", "7.1", "PASS").forEach { text ->
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).border(1.dp, Line, RoundedCornerShape(13.dp))
                                        .background(Color(0xB80D1822)).padding(horizontal = 4.dp, vertical = 10.dp).alpha(.6f),
                                        contentAlignment = Alignment.Center) {
                                        Text(text, color = Color(0xFF8292A6), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                            Text("Прошивка SW411 не публикует команду Audio EDID в serial CLI. Я не отправляю непроверенные команды устройству.",
                                color = Muted, fontSize = 11.sp, lineHeight = 15.4.sp,
                                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 13.dp))
                        }
                        WebDivider()
                        ModeRow(R.drawable.web_manual, "Ручной режим", "Активен, когда автопереключение выключено") {
                            WebBadge(when (ui.status.autoSwitch) { true -> "ВЫКЛ"; false -> "ВКЛ"; null -> "—" })
                        }
                    }
                }
                Section("Системный журнал", live = ui.sseConnected) { LogsPanel(ui.logs) }
            }
            val notice = ui.error ?: ui.message
            LaunchedEffect(notice) { if (notice != null) { delay(2800); vm.clearNotice() } }
            if (notice != null) {
                Text(notice, color = TextPrimary, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 18.dp)
                        .widthIn(max = 610.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF5121D28)).border(1.dp, if (ui.error != null) Red.copy(alpha = .45f) else Line,
                            RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 13.dp))
            }
        }
        if (ui.settingsOpen) SettingsSheet(ui, vm::closeSettings, vm::saveSettings, vm::notifyMessage)
        if (serverOpen) ServerSheet(ui.serverUrl, onDismiss = { serverOpen = false },
            onSave = { if (vm.setServerUrl(it)) { serverOpen = false; true } else false })
        if (confirmation != null) {
            val action = confirmation
            ConfirmSheet(title = when (action) { "power" -> "Выключить SW411?"; "reboot" -> "Перезагрузить SW411?"; else -> "Обновить SW411 Control?" },
                message = if (action == "update") "Nexbox скачает актуальную версию из GitHub и перезапустит сервис." else "HDMI-соединение кратковременно прервётся.",
                onDismiss = { confirmation = null }, onConfirm = {
                    confirmation = null
                    when (action) { "power" -> vm.togglePower(); "reboot" -> vm.reboot(); else -> vm.updateController() }
                })
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, live: Boolean? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 25.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = TextPrimary, fontSize = 20.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, color = Color(0xFF66B6FF), fontSize = 12.sp)
            if (live != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (live) Green else Amber, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(if (live) "LIVE" else "RECONNECT", color = Color(0xFF66B6FF), fontSize = 12.sp)
            }
        }
        content()
    }
}

@Composable
private fun StatusPanel(ui: AppUiState, wide: Boolean) {
    val state = ui.status
    WebPanel {
        Column(Modifier.fillMaxWidth().drawBehind {
            drawCircle(Cyan.copy(alpha = .07f), 105.dp.toPx(), Offset(size.width - 5.dp.toPx(), -15.dp.toPx()))
        }.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(Modifier.size(15.dp).drawBehind {
                    val color = if (state.connected) Green else Red
                    drawCircle(color.copy(alpha = .11f), size.width / 2 + 8.dp.toPx())
                }.background(if (state.connected) Green else Red, CircleShape))
                Column {
                    Text(if (ui.connectionPending) "Подключение…" else if (state.connected) "Подключено" else "Нет связи",
                        color = if (state.connected) Green else TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(2.dp))
                    Text(if (ui.connectionPending) "Проверяем /dev/sw411" else if (state.connected) "Устройство готово к работе"
                        else state.error ?: "SW411 не найден", color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            val metrics = listOf("Порт" to (state.port ?: "/dev/sw411"), "Прошивка" to (state.firmware ?: "—"),
                "Активный вход" to (state.inputName ?: "—"), "Температура" to (state.temperature?.let {
                    "${if (it % 1 == 0.0) it.toInt().toString() else it.toString()} °C"
                } ?: "—"))
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Line), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                metrics.chunked(if (wide) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        row.forEach { (label, value) ->
                            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xA808111A))
                                .padding(horizontal = if (wide) 15.dp else 14.dp, vertical = if (wide) 15.dp else 13.dp)) {
                                Text(label, color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCopy(title: String, icon: Int, color: Color = Color(0xFF9FB7D3)) {
    Column(Modifier.padding(horizontal = 7.dp, vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WebIcon(icon, 25.dp, color)
        Text(title, color = if (color == Color(0xFF9FB7D3)) TextPrimary else color, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InputTile(label: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (active) Color(0xFFEAFFFF) else Color(0xFFAAB7C8)
    val brush = if (active) Brush.radialGradient(listOf(Color(0xCC155D6A), Color(0xC709333D))) else TileBrush
    WebTile(modifier.heightIn(min = 93.dp).then(if (active) Modifier.shadow(12.dp, RoundedCornerShape(18.dp),
        ambientColor = Cyan.copy(alpha = .25f), spotColor = Cyan.copy(alpha = .25f)) else Modifier),
        enabled, border = if (active) Cyan else Line, brush = brush, onClick = onClick) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WebIcon(R.drawable.web_hdmi, 28.dp, color)
            Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(7.dp).background(if (active) Cyan else Color(0xFF58697C), CircleShape))
        }
    }
}

@Composable
internal fun WebDivider() { Box(Modifier.fillMaxWidth().height(1.dp).background(Line)) }

@Composable
private fun ModeRow(icon: Int, title: String, subtitle: String, action: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) { ModeCopy(icon, title, subtitle, action) }
}
@Composable
private fun ModeCopy(icon: Int, title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { WebIcon(icon, tint = Color(0xFFA9B9CC)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        action()
    }
}
@Composable
private fun MenuAction(title: String, subtitle: String, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    WebTile(Modifier.fillMaxWidth(), enabled, border = Color.Transparent, radius = 14.dp,
        brush = Brush.linearGradient(listOf(Color(0xB81F2D3B), Color(0xB81F2D3B))), onClick = onClick) {
        MenuCopy(title, subtitle, icon)
    }
}
@Composable
private fun MenuCopy(title: String, subtitle: String, icon: Int) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WebIcon(icon, 25.dp, Cyan)
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}
@Composable
private fun LogsPanel(logs: List<Sw411Log>) {
    val entries = if (logs.isEmpty()) listOf(Sw411Log("—", "Журнал пока пуст", "")) else logs.take(8)
    WebPanel {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            entries.forEachIndexed { index, entry ->
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(10.dp).padding(top = 5.dp)) {
                        Box(Modifier.size(7.dp).background(when (entry.level) {
                            "info" -> Cyan; "warn" -> Amber; "error" -> Red; else -> Color(0xFF7F91A7)
                        }, CircleShape))
                    }
                    Text(entry.time, color = Color(0xFF74869B), fontSize = 12.sp, modifier = Modifier.width(62.dp))
                    Text(entry.message, color = Color(0xFFC8D2DF), fontSize = 12.sp, lineHeight = 16.56.sp,
                        modifier = Modifier.weight(1f))
                }
                if (index < entries.lastIndex) WebDivider()
            }
        }
    }
}
