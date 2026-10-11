package de.feromonde.sw411control

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class Sw411State(
    val connected: Boolean = false,
    val firmware: String? = null,
    val input: Int? = null,
    val inputName: String? = null,
    val inputNames: List<String> = listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"),
    val power: Boolean? = null,
    val autoSwitch: Boolean? = null,
    val earc: Boolean? = null,
    val temperature: Double? = null,
    val port: String? = null,
    val error: String? = null
)

data class Sw411Log(val time: String, val message: String, val level: String)
data class MqttSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val baseTopic: String = "sw411",
    val discoveryPrefix: String = "homeassistant",
    val connected: Boolean = false
)
data class ControllerSettings(
    val inputNames: List<String> = listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"),
    val apiToken: String = "",
    val mqtt: MqttSettings = MqttSettings()
)
data class UpdateStatus(val state: String, val targetVersion: String?, val error: String?)

class Sw411Api(baseUrl: String) {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS).build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun status(): Sw411State = json("/api/status?force=1").toState()
    suspend fun controllerVersion(): String = json("/api/app").optString("version", "—")
    suspend fun logs(): List<Sw411Log> = withContext(Dispatchers.IO) {
        val array = JSONArray(request("/api/logs"))
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Sw411Log(item.optString("time"), item.optString("message"), item.optString("level", "info"))
        }
    }
    suspend fun settings(): ControllerSettings = json("/api/settings").toSettings()
    suspend fun saveSettings(settings: ControllerSettings): ControllerSettings {
        require(settings.inputNames.size == 4)
        require(settings.mqtt.port in 1..65535) { "Порт MQTT должен быть от 1 до 65535" }
        val mqtt = settings.mqtt
        val payload = JSONObject().put("input_names", JSONArray(settings.inputNames.mapIndexed { i, name ->
            name.trim().take(32).ifEmpty { "HDMI ${i + 1}" }
        })).put("mqtt", JSONObject()
            .put("enabled", mqtt.enabled).put("host", mqtt.host.trim()).put("port", mqtt.port)
            .put("username", mqtt.username.trim()).put("password", mqtt.password)
            .put("base_topic", mqtt.baseTopic.trim().ifEmpty { "sw411" })
            .put("discovery_prefix", mqtt.discoveryPrefix.trim().ifEmpty { "homeassistant" }))
        // An empty password retains the broker's existing password on the server.
        // The generated HA token is read-only and is never sent back in this PUT.
        return json("/api/settings", "PUT", payload).toSettings()
    }
    suspend fun selectInput(input: Int) {
        require(input in 1..4)
        json("/api/input/$input", "POST")
    }
    suspend fun setAuto(enabled: Boolean) { json("/api/auto", "POST", JSONObject().put("enabled", enabled)) }
    suspend fun setEarc(enabled: Boolean) { json("/api/earc", "POST", JSONObject().put("enabled", enabled)) }
    suspend fun togglePower(): Boolean? = json("/api/power/toggle", "POST").nullableBoolean("power")
    suspend fun reboot() { json("/api/reboot", "POST") }
    suspend fun updateController() { json("/api/update-control", "POST") }
    suspend fun updateStatus(): UpdateStatus {
        val data = json("/api/update-status")
        return UpdateStatus(data.optString("state"), data.nullableString("target_version"), data.nullableString("error"))
    }

    suspend fun streamEvents(onConnected: (Boolean) -> Unit, onState: (Sw411State) -> Unit) {
        while (currentCoroutineContext().isActive) {
            try {
                readStream(onConnected, onState)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The foreground session reconnects when the controller comes back.
            } finally {
                onConnected(false)
            }
            delay(1500)
        }
    }

    private suspend fun readStream(onConnected: (Boolean) -> Unit, onState: (Sw411State) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = streamClient.newCall(Request.Builder().url(baseUrl + "/api/events")
                .header("Accept", "text/event-stream").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(it.isSuccessful) { "SSE HTTP ${it.code}" }
                            val source = it.body?.source() ?: error("Пустой SSE ответ")
                            if (continuation.isActive) onConnected(true)
                            val data = StringBuilder()
                            while (continuation.isActive) {
                                val line = source.readUtf8Line() ?: break
                                if (line.isEmpty() && data.isNotEmpty()) {
                                    onState(JSONObject(data.toString()).toState())
                                    data.clear()
                                } else if (line.startsWith("data:")) {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trimStart())
                                }
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private suspend fun json(path: String, method: String = "GET", payload: JSONObject = JSONObject()): JSONObject {
        val text = request(path, method, payload)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
    private suspend fun request(path: String, method: String = "GET", payload: JSONObject = JSONObject()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl + path)
            if (method != "GET") builder.method(method, payload.toString().toRequestBody(jsonType))
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    runCatching { JSONObject(text).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
                }
                text
            }
        }

    companion object {
        fun normalizeBaseUrl(value: String): String {
            val text = value.trim().let { if ("://" in it) it else "http://$it" }
            val uri = runCatching { URI(text) }.getOrNull()
            require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)) { "Введи корректный адрес http:// или https://" }
            return text.trimEnd('/')
        }
    }
}

private fun JSONObject.names(): List<String> {
    val values = optJSONArray("input_names")
    return List(4) { index -> values?.optString(index)?.takeIf { it.isNotBlank() } ?: "HDMI ${index + 1}" }
}
private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.nullableBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else optBoolean(key)
private fun JSONObject.toState(): Sw411State {
    val names = names()
    val input = if (has("input") && !isNull("input")) optInt("input").takeIf { it in 1..4 } else null
    return Sw411State(optBoolean("connected"), nullableString("firmware"), input,
        nullableString("input_name") ?: input?.let { names[it - 1] }, names,
        nullableBoolean("power"), nullableBoolean("auto_switch"), nullableBoolean("earc"),
        if (has("temperature") && !isNull("temperature")) optDouble("temperature") else null,
        nullableString("port"), nullableString("error"))
}
private fun JSONObject.toSettings(): ControllerSettings {
    val mqtt = optJSONObject("mqtt") ?: JSONObject()
    return ControllerSettings(names(), optString("api_token"), MqttSettings(
        enabled = mqtt.optBoolean("enabled"), host = mqtt.optString("host"), port = mqtt.optInt("port", 1883),
        username = mqtt.optString("username"), baseTopic = mqtt.optString("base_topic", "sw411"),
        discoveryPrefix = mqtt.optString("discovery_prefix", "homeassistant"), connected = mqtt.optBoolean("connected")))
}
