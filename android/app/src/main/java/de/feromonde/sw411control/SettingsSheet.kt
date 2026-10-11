package de.feromonde.sw411control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WebSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Keep sheets in the Activity window so navigation and keyboard insets come
    // from the same window as the screen, including older Android/OEM devices.
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onDismiss)
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f))
            .safeDrawingPadding().imePadding().padding(12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 620.dp).fillMaxWidth()
                .heightIn(max = maxHeight * .92f).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0C1722)).border(1.dp, Line, RoundedCornerShape(24.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { paneTitle = title; isTraversalGroup = true }
                .padding(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 15.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    WebTile(Modifier.size(38.dp).semantics { contentDescription = "Закрыть" }, radius = 12.dp,
                        brush = SolidColor(Color(0xFF1A2836)), border = Color.Transparent, onClick = onDismiss) {
                        Text("×", color = Color(0xFFD5DEEA), fontSize = 22.sp)
                    }
                }
                content()
            }
        }
}

@Composable
internal fun SettingsSheet(ui: AppUiState, onDismiss: () -> Unit,
    onSave: (ControllerSettings) -> Unit, onMessage: (String) -> Unit) {
    val cfg = ui.settings
    var draft by remember(cfg) { mutableStateOf(cfg ?: ControllerSettings()) }
    var portText by remember(cfg) { mutableStateOf((cfg?.mqtt?.port ?: 1883).toString()) }
    var validation by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val enabled = !ui.settingsSaving
    WebSheet("Настройки", onDismiss) {
        if (ui.settingsLoading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan)
            }
        } else if (cfg != null) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .alpha(if (enabled) 1f else .62f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                FormSection("Имена входов", divider = false) {
                    (0..3).chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { index ->
                                WebField("HDMI ${index + 1}", draft.inputNames[index], modifier = Modifier.weight(1f), enabled = enabled) { value ->
                                    draft = draft.copy(inputNames = draft.inputNames.toMutableList().also { it[index] = value.take(32) })
                                }
                            }
                        }
                    }
                }
                FormSection("Home Assistant REST API") {
                    Text("Bearer token", color = Muted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WebField("Bearer token", cfg.apiToken, Modifier.weight(1f), labelVisible = false,
                            readOnly = true, mono = true, onChange = {})
                        WebTile(Modifier.height(40.dp), radius = 12.dp, brush = SolidColor(Color(0xFF1A2937)), onClick = {
                            val clip = ClipData.newPlainText("SW411 API token", cfg.apiToken)
                            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            onMessage("API token скопирован")
                        }) { Text("Копировать", color = Color(0xFFDBE7F3), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                    }
                    Note("Состояние: GET /api/ha/state · Заголовок Authorization: Bearer TOKEN")
                }
                FormSection("MQTT + Home Assistant Discovery") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.mqtt.enabled, { draft = draft.copy(mqtt = draft.mqtt.copy(enabled = it)) }, enabled = enabled,
                            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = Muted),
                            modifier = Modifier.size(30.dp).semantics { contentDescription = "Включить MQTT" })
                        Spacer(Modifier.width(10.dp))
                        Text("Включить MQTT", color = TextPrimary, fontSize = 16.sp)
                    }
                    WebField("MQTT broker", draft.mqtt.host, enabled = enabled, placeholder = "192.168.2.x") {
                        draft = draft.copy(mqtt = draft.mqtt.copy(host = it))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WebField("Порт", portText, Modifier.weight(1f), enabled = enabled, keyboardType = KeyboardType.Number) {
                            portText = it.filter(Char::isDigit).take(5)
                        }
                        WebField("Пользователь", draft.mqtt.username, Modifier.weight(1f), enabled = enabled) {
                            draft = draft.copy(mqtt = draft.mqtt.copy(username = it))
                        }
                    }
                    WebField("Пароль (пусто = не менять)", draft.mqtt.password, enabled = enabled, password = true) {
                        draft = draft.copy(mqtt = draft.mqtt.copy(password = it))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WebField("Base topic", draft.mqtt.baseTopic, Modifier.weight(1f), enabled = enabled) {
                            draft = draft.copy(mqtt = draft.mqtt.copy(baseTopic = it))
                        }
                        WebField("Discovery prefix", draft.mqtt.discoveryPrefix, Modifier.weight(1f), enabled = enabled) {
                            draft = draft.copy(mqtt = draft.mqtt.copy(discoveryPrefix = it))
                        }
                    }
                    Note(when { !cfg.mqtt.enabled -> "MQTT выключен"; cfg.mqtt.connected -> "MQTT подключён"; else -> "MQTT включён, соединения пока нет" })
                }
            }
            val error = validation ?: ui.error
            if (error != null) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Отмена", enabled = enabled, modifier = Modifier.weight(1f), onClick = onDismiss)
                SheetButton(if (enabled) "Сохранить" else "Сохранение…", primary = true, enabled = enabled,
                    modifier = Modifier.weight(1f), onClick = {
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1..65535) validation = "Порт MQTT должен быть от 1 до 65535"
                        else { validation = null; onSave(draft.copy(mqtt = draft.mqtt.copy(port = port))) }
                    })
            }
        }
    }
}

@Composable
internal fun WebField(label: String, value: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, labelVisible: Boolean = true, readOnly: Boolean = false,
    mono: Boolean = false, password: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "", onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (labelVisible) Text(label, color = Muted, fontSize = 11.sp)
        BasicTextField(value = value, onValueChange = onChange, singleLine = true, enabled = enabled, readOnly = readOnly,
            textStyle = TextStyle(color = TextPrimary, fontSize = if (mono) 11.sp else 16.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif),
            cursorBrush = SolidColor(Cyan), visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF101E2B))
                .border(1.dp, if (focused) Cyan.copy(alpha = .55f) else Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner -> Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Muted, fontSize = 16.sp)
                inner()
            } })
    }
}

@Composable
private fun FormSection(title: String, divider: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (divider) WebDivider()
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = if (divider) 5.dp else 0.dp))
        content()
    }
}
@Composable
private fun Note(text: String) { Text(text, color = Muted, fontSize = 11.sp, lineHeight = 15.4.sp) }

@Composable
internal fun ServerSheet(server: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var draft by remember { mutableStateOf(server) }
    var invalid by remember { mutableStateOf(false) }
    WebSheet("Адрес сервера", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            WebField("SW411 Control на Nexbox", draft, keyboardType = KeyboardType.Uri) { draft = it; invalid = false }
            Text(if (invalid) "Введи корректный адрес http:// или https://" else "Например: http://192.168.2.159",
                color = if (invalid) Red else Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton("Отмена", modifier = Modifier.weight(1f), onClick = onDismiss)
            SheetButton("Сохранить", primary = true, modifier = Modifier.weight(1f), onClick = { invalid = !onSave(draft) })
        }
    }
}
@Composable
internal fun ConfirmSheet(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WebSheet(title, onDismiss) {
        Text(message, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false)
            .verticalScroll(rememberScrollState()).padding(bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton("Отмена", modifier = Modifier.weight(1f), onClick = onDismiss)
            SheetButton("Подтвердить", primary = true, modifier = Modifier.weight(1f), onClick = onConfirm)
        }
    }
}
