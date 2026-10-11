package de.feromonde.sw411control

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val serverUrl: String = "http://192.168.2.159",
    val status: Sw411State = Sw411State(),
    val logs: List<Sw411Log> = emptyList(),
    val controllerVersion: String = "—",
    val connectionPending: Boolean = true,
    val sseConnected: Boolean = false,
    val busy: Boolean = false,
    val settingsOpen: Boolean = false,
    val settingsLoading: Boolean = false,
    val settingsSaving: Boolean = false,
    val settings: ControllerSettings? = null,
    val message: String? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("sw411", 0)
    private val storedUrl = runCatching {
        Sw411Api.normalizeBaseUrl(prefs.getString("server_url", "http://192.168.2.159").orEmpty())
    }.getOrDefault("http://192.168.2.159")
    private val _ui = MutableStateFlow(AppUiState(serverUrl = storedUrl))
    val ui = _ui.asStateFlow()
    private var api = Sw411Api(storedUrl)
    private var streamJob: Job? = null
    private var refreshJob: Job? = null
    private var foreground = false
    private var updating = false
    private var serverRevision = 0
    private var sessionRevision = 0
    private val pending = mutableMapOf<String, Pair<Any, Long>>()

    fun start() {
        foreground = true
        if (streamJob?.isActive == true || updating) return
        val session = ++sessionRevision
        val connection = api
        refresh()
        streamJob = viewModelScope.launch {
            connection.streamEvents(
                onConnected = { connected ->
                    viewModelScope.launch {
                        if (session == sessionRevision) _ui.update { it.copy(sseConnected = connected) }
                    }
                },
                onState = { state ->
                    viewModelScope.launch { if (session == sessionRevision) applyState(state) }
                })
        }
    }

    fun stop() {
        foreground = false
        ++sessionRevision
        streamJob?.cancel()
        refreshJob?.cancel()
        _ui.update { it.copy(sseConnected = false) }
    }

    fun setServerUrl(value: String): Boolean {
        val normalized = runCatching { Sw411Api.normalizeBaseUrl(value) }.getOrElse {
            notifyError(it.message ?: "Некорректный адрес")
            return false
        }
        ++serverRevision
        val restart = foreground
        stop()
        pending.clear()
        api = Sw411Api(normalized)
        prefs.edit().putString("server_url", normalized).apply()
        _ui.value = AppUiState(serverUrl = normalized, message = "Адрес сохранён")
        if (restart) start()
        return true
    }

    fun refresh() {
        refreshJob?.cancel()
        val revision = serverRevision
        val connection = api
        refreshJob = viewModelScope.launch {
            try {
                val state = connection.status()
                if (revision != serverRevision) return@launch
                applyState(state)
                val logs = runCatching { connection.logs() }.getOrDefault(_ui.value.logs)
                val version = runCatching { connection.controllerVersion() }.getOrDefault(_ui.value.controllerVersion)
                if (revision == serverRevision) _ui.update { it.copy(logs = logs, controllerVersion = version) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (revision == serverRevision) _ui.update {
                    it.copy(status = it.status.copy(connected = false, error = error.message), connectionPending = false)
                }
            }
        }
    }

    private fun applyState(state: Sw411State) {
        fun accepted(key: String, actual: Any?): Any? {
            val optimistic = pending[key] ?: return actual
            if (actual == optimistic.first || SystemClock.elapsedRealtime() > optimistic.second) {
                pending.remove(key)
                return actual
            }
            return optimistic.first
        }
        val input = accepted("input", state.input) as Int?
        val merged = state.copy(input = input, inputName = input?.let { state.inputNames[it - 1] },
            autoSwitch = accepted("auto", state.autoSwitch) as Boolean?,
            earc = accepted("earc", state.earc) as Boolean?, power = accepted("power", state.power) as Boolean?)
        _ui.update { it.copy(status = merged, connectionPending = false) }
    }

    private fun optimistic(key: String, value: Any) {
        pending[key] = value to (SystemClock.elapsedRealtime() + 6000)
        applyState(_ui.value.status)
    }

    private fun command(message: String, action: suspend (Sw411Api) -> Unit) {
        if (_ui.value.busy) return
        val revision = serverRevision
        val connection = api
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action(connection)
                if (revision == serverRevision) notifyMessage(message)
                delay(700)
                val logs = runCatching { connection.logs() }.getOrDefault(_ui.value.logs)
                if (revision == serverRevision) _ui.update { it.copy(logs = logs) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (revision == serverRevision) {
                    pending.clear()
                    notifyError(error.message ?: "Ошибка команды")
                    refresh()
                }
            } finally {
                if (revision == serverRevision) _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun selectInput(input: Int) {
        if (_ui.value.busy) return
        optimistic("input", input)
        command("${_ui.value.status.inputNames[input - 1]} выбран") { it.selectInput(input) }
    }
    fun setAuto(enabled: Boolean) {
        if (_ui.value.busy) return
        optimistic("auto", enabled)
        command(if (enabled) "Автопереключение включено" else "Ручной режим включён") { it.setAuto(enabled) }
    }
    fun setEarc(enabled: Boolean) {
        if (_ui.value.busy) return
        optimistic("earc", enabled)
        command(if (enabled) "eARC включён" else "eARC выключен") { it.setEarc(enabled) }
    }
    fun togglePower() = command("Команда питания отправлена") {
        it.togglePower()?.let { power -> optimistic("power", power) }
    }
    fun reboot() = command("SW411 перезагружается…") {
        it.reboot()
        delay(1800)
        refresh()
    }

    fun openSettings() {
        if (_ui.value.settingsSaving) return
        val revision = serverRevision
        val connection = api
        _ui.update { it.copy(settingsOpen = true, settingsLoading = true, settings = null) }
        viewModelScope.launch {
            try {
                val settings = connection.settings()
                if (revision == serverRevision) _ui.update { it.copy(settings = settings, settingsLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (revision == serverRevision) {
                    _ui.update { it.copy(settingsOpen = false, settingsLoading = false) }
                    notifyError(error.message ?: "Не удалось загрузить настройки")
                }
            }
        }
    }
    fun closeSettings() { if (!_ui.value.settingsSaving) _ui.update { it.copy(settingsOpen = false, settings = null) } }
    fun saveSettings(settings: ControllerSettings) {
        if (_ui.value.settingsSaving) return
        val revision = serverRevision
        val connection = api
        _ui.update { it.copy(settingsSaving = true) }
        viewModelScope.launch {
            try {
                val saved = connection.saveSettings(settings)
                if (revision != serverRevision) return@launch
                _ui.update { it.copy(settingsOpen = false, settings = null,
                    status = it.status.copy(inputNames = saved.inputNames,
                        inputName = it.status.input?.let { input -> saved.inputNames[input - 1] })) }
                notifyMessage("Настройки сохранены")
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (revision == serverRevision) notifyError(error.message ?: "Ошибка сохранения")
            } finally {
                if (revision == serverRevision) _ui.update { it.copy(settingsSaving = false) }
            }
        }
    }

    fun updateController() {
        if (_ui.value.busy) return
        val connection = api
        val revision = serverRevision
        updating = true
        ++sessionRevision
        streamJob?.cancel()
        _ui.update { it.copy(busy = true, sseConnected = false) }
        viewModelScope.launch {
            try {
                connection.updateController()
                notifyMessage("Обновление SW411 Control запущено…")
                repeat(150) {
                    delay(1200)
                    if (revision != serverRevision) return@launch
                    val update = runCatching { connection.updateStatus() }.getOrNull() ?: return@repeat
                    if (update.state == "success") {
                        notifyMessage(update.targetVersion?.let { "SW411 Control v$it установлен" } ?: "SW411 Control обновлён")
                        return@launch
                    }
                    if (update.state == "failed") error(update.error ?: "Ошибка обновления")
                }
                error("Обновление не завершилось за 3 минуты")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (revision == serverRevision) notifyError(error.message ?: "Ошибка обновления")
            } finally {
                updating = false
                if (revision == serverRevision) _ui.update { it.copy(busy = false) }
                if (foreground) start()
            }
        }
    }
    fun notifyMessage(message: String) { _ui.update { it.copy(message = message, error = null) } }
    fun notifyError(message: String) { _ui.update { it.copy(error = message, message = null) } }
    fun clearNotice() { _ui.update { it.copy(message = null, error = null) } }
}
