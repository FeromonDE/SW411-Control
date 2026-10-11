package de.feromonde.sw411control

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val serverUrl: String = "http://192.168.2.159",
    val status: Sw411State = Sw411State(),
    val logs: List<Sw411Log> = emptyList(),
    val sseConnected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("sw411", 0)
    private val _ui = MutableStateFlow(
        AppUiState(
            serverUrl = prefs.getString("server_url", "http://192.168.2.159")
                ?: "http://192.168.2.159"
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private var api = Sw411Api(_ui.value.serverUrl)
    private var streamJob: Job? = null

    init {
        reconnect()
    }

    fun setServerUrl(value: String) {
        val normalized = Sw411Api.normalizeBaseUrl(value)
        prefs.edit().putString("server_url", normalized).apply()
        _ui.update { it.copy(serverUrl = normalized, error = null, message = "Адрес сохранён") }
        reconnect()
    }

    fun reconnect() {
        streamJob?.cancel()
        api = Sw411Api(_ui.value.serverUrl)

        viewModelScope.launch {
            refresh()
        }

        streamJob = viewModelScope.launch {
            api.streamEvents(
                onConnected = { connected ->
                    _ui.update { it.copy(sseConnected = connected) }
                },
                onState = { state ->
                    _ui.update {
                        it.copy(
                            status = state,
                            error = state.error
                        )
                    }
                }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val state = api.status()
                val logs = runCatching { api.logs() }.getOrDefault(_ui.value.logs)
                _ui.update {
                    it.copy(
                        status = state,
                        logs = logs,
                        error = state.error
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        status = it.status.copy(connected = false),
                        error = e.message ?: "Нет связи с SW411 Control"
                    )
                }
            }
        }
    }

    fun selectInput(input: Int) {
        val names = _ui.value.status.inputNames
        _ui.update {
            it.copy(
                status = it.status.copy(
                    input = input,
                    inputName = names.getOrNull(input - 1)
                ),
                busy = true
            )
        }

        viewModelScope.launch {
            try {
                api.selectInput(input)
                _ui.update {
                    it.copy(
                        busy = false,
                        message = "${names.getOrNull(input - 1) ?: "HDMI $input"} выбран"
                    )
                }
                refreshLogsSoon()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
                refresh()
            }
        }
    }

    fun setAuto(enabled: Boolean) {
        _ui.update { it.copy(status = it.status.copy(autoSwitch = enabled), busy = true) }
        viewModelScope.launch {
            try {
                api.setAuto(enabled)
                _ui.update {
                    it.copy(
                        busy = false,
                        message = if (enabled) "Автопереключение включено" else "Ручной режим включён"
                    )
                }
                refreshLogsSoon()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
                refresh()
            }
        }
    }

    fun setEarc(enabled: Boolean) {
        _ui.update { it.copy(status = it.status.copy(earc = enabled), busy = true) }
        viewModelScope.launch {
            try {
                api.setEarc(enabled)
                _ui.update {
                    it.copy(
                        busy = false,
                        message = if (enabled) "eARC включён" else "eARC выключен"
                    )
                }
                refreshLogsSoon()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
                refresh()
            }
        }
    }

    fun togglePower() {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val newPower = api.togglePower()
                _ui.update {
                    it.copy(
                        busy = false,
                        status = if (newPower == null) it.status else it.status.copy(power = newPower),
                        message = if (newPower == false) "SW411 выключается" else "Команда питания отправлена"
                    )
                }
                refreshLogsSoon()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun reboot() {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                api.reboot()
                _ui.update { it.copy(busy = false, message = "SW411 перезагружается…") }
                delay(1800)
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun updateController() {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                api.updateController()
                _ui.update { it.copy(message = "Обновление SW411 Control запущено…") }
                streamJob?.cancel()

                repeat(150) {
                    delay(1200)
                    try {
                        when (val update = api.updateStatus()) {
                            is UpdateStatus -> when (update.state) {
                                "success" -> {
                                    val state = runCatching { api.status() }.getOrDefault(_ui.value.status)
                                    _ui.update {
                                        it.copy(
                                            busy = false,
                                            status = state,
                                            message = update.targetVersion?.let { v ->
                                                "SW411 Control v$v установлен"
                                            } ?: "SW411 Control обновлён"
                                        )
                                    }
                                    reconnect()
                                    return@launch
                                }
                                "failed" -> {
                                    _ui.update {
                                        it.copy(
                                            busy = false,
                                            error = update.error ?: "Обновление завершилось ошибкой"
                                        )
                                    }
                                    reconnect()
                                    return@launch
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Expected while the server is being restarted.
                    }
                }

                _ui.update {
                    it.copy(
                        busy = false,
                        error = "Обновление не завершилось за 3 минуты"
                    )
                }
                reconnect()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    private fun refreshLogsSoon() {
        viewModelScope.launch {
            delay(500)
            val logs = runCatching { api.logs() }.getOrNull() ?: return@launch
            _ui.update { it.copy(logs = logs) }
        }
    }
}
